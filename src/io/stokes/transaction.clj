(ns io.stokes.transaction
  "This namespace implements an UTXO model to send coins from one address to another like Bitcoin's Pay-to-PubKey scheme."
  (:require [io.stokes.hash :as hash]
            [io.stokes.key :as key])
  (:refer-clojure :exclude [hash]))

;; transactions

(defn hash [transaction]
  (get transaction :hash (hash/of transaction)))

(defn output->out-point [output]
  (when output
    (select-keys output [:hash :index])))

(defn output-from-out-point
  "out-point has keys `:hash` and `:index` which index into the ledger and return the UXTO at that joint key, if any"
  [ledger out-point]
  (let [out-point (select-keys out-point [:hash :index])]
    (ledger out-point)))

(defn generate-output-proof [keys {:keys [hash index]}]
  {:public-key (key/->public keys)
   :hash hash
   :index index
   :signature (key/sign-hash-with-keys (key/->private keys) hash)})

(defn new-input
  "previous-outputs refer to unspent transaction outputs; proofs contains one proof corresponding to each output that convinces a verifier that this transaction is able to spend the referenced output"
  [previous-outputs proofs]
  {:post [(contains? % :type)]}
  {:type :input
   :value (reduce + (map :value previous-outputs))
   :scripts (map (fn [[a b]] (merge a b {:script-type :signature}))
                 (map vector previous-outputs proofs))})

(defn new-output [value address]
  {:post [(contains? % :type)]}
  {:type :output
   :value value
   :script {:script-type :address
            :address address}})

(defn new
  ([ins-and-outs]
   (let [hash (hash ins-and-outs)]
     (merge ins-and-outs {:hash hash
                          :outputs (map-indexed (fn [i output]
                                                  (merge output {:hash hash
                                                                 :index i})) (:outputs ins-and-outs))})))
  ([inputs outputs] (io.stokes.transaction/new {:inputs inputs
                                                :outputs outputs})))

(defn for-coinbase [address subsidy block-height]
  (io.stokes.transaction/new
   {:inputs [{:type :coinbase-input
              :block-height block-height}]
    :outputs [(new-output subsidy address)]}))

(defn inputs [transaction]
  (:inputs transaction))
(defn outputs [transaction]
  (:outputs transaction))

(defn input->value [in]
  (:value in))
(defn input->previous-outputs [in]
  (some->> in
           :scripts
           (map #(select-keys % [:value :script :hash :index]))
           (map #(assoc % :type :output))))

(defn output->value [out]
  (:value out))
(defn output->address [out]
  (get-in out [:script :address]))

(defn- valid-pay-to-pubkey-hash? [{:keys [hash script signature public-key]}]
  (let [address (:address script)]
    (and (key/yields-address? public-key address)
         (key/validates-signature? public-key signature hash))))

(defn- contains-output? [ledger output]
  (get ledger (output->out-point output)))

(defmulti valid-input? (fn [ledger input] (:type input)))

(defmethod valid-input? :input [ledger input]
  (every?
   identity
   (concat
    (map (partial contains-output? ledger) (input->previous-outputs input))
    (map valid-pay-to-pubkey-hash? (:scripts input)))))

(defmethod valid-input? :coinbase-input [_ input]
  (select-keys input [:block-height]))

(defn- valid-inputs?
  "determines if each transaction input is valid according to its type"
  [ledger transaction]
  (every? true? (map #(valid-input? ledger %) (inputs transaction))))

(defn valid-balances? [transaction]
  (>= (reduce + (map input->value (inputs transaction)))
      (reduce + (map output->value (outputs transaction)))))

(defn- valid-outputs? [transaction]
  (valid-balances? transaction))

(defn valid? [ledger transaction]
  (and
   (valid-outputs? transaction)
   (valid-inputs? ledger transaction)))

(defn fee
  "the fee is difference between the input value and the output value"
  [transaction]
  (let [ins (inputs transaction)
        outs (outputs transaction)]
    (- (reduce + (map :value ins))
       (reduce + (map :value outs)))))

;; ledger -- the utxo set
;; mapping from out-points to outputs

(defn- remove-previous-outputs [transaction ledger]
  (->> (inputs transaction)
       (mapcat input->previous-outputs)
       (map output->out-point)
       (apply dissoc ledger)))

(defn- add-outputs [transaction ledger]
  (let [hash (hash transaction)
        outputs (outputs transaction)
        out-points (map output->out-point outputs)]
    (merge ledger (into {}
                        (map vector out-points outputs)))))

(defn- apply-transaction-to-ledger [ledger transaction]
  (->> ledger
       (remove-previous-outputs transaction)
       (add-outputs transaction)))

(defn apply-transactions-to-ledger [ledger transactions]
  (reduce apply-transaction-to-ledger ledger transactions))

(defn ledger [{:keys [initial-state]}]
  (apply-transactions-to-ledger {} initial-state))

(defn- output->balances [balances output]
  (let [address (output->address output)
        value (output->value output)]
    (update balances address (fnil + 0) value)))

(defn ledger-balances [ledger]
  (->> ledger
       vals
       (reduce output->balances {})))
