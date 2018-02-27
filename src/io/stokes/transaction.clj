(ns io.stokes.transaction
  "This namespace implements an UTXO model to send coins from one address to another like Bitcoin's Pay-to-PubKey scheme."
  (:require [io.stokes.hash :as hash])
  (:refer-clojure :exclude [hash]))

;; transactions

(defn hash [transaction]
  (get transaction :hash (hash/of transaction)))

(defn output->out-point [output]
  (select-keys output [:hash :index]))

(defn output-in [ledger hash index]
  (when-let [outputs (ledger hash)]
    (merge (nth outputs index)
           {:hash hash
            :index index})))

(defn new-input [previous-outputs signature public-key]
  {:post [(contains? % :type)]}
  {:type :input
   :value (reduce + (map :value previous-outputs))
   :previous-outputs previous-outputs
   :script {:type :signature
            :signature signature
            :public-key public-key}})

(defn new-output [value address & [hash index]]
  {:post [(contains? % :type)]}
  (merge {:type :output
          :value value
          :script {:type :address
                   :address address}}
         {:hash hash
          :index index}))

(defn new
  ([ins-and-outs] (assoc ins-and-outs :hash (hash ins-and-outs)))
  ([inputs outputs] (io.stokes.transaction/new {:inputs inputs
                                                :outputs outputs})))

(defn for-coinbase [address subsidy block-height]
  (io.stokes.transaction/new
   {:inputs [{:type :coinbase-input
              :block-height block-height}]
    ;; use hash of address + block height as backing address
    :outputs [(new-output subsidy address (hash/of (str address block-height)) 0)]}))

(defn inputs [transaction]
  (:inputs transaction))
(defn outputs [transaction]
  (:outputs transaction))

(defn input->value [in]
  (:value in))
(defn input->previous-hash [in]
  (get-in in [:previous-output :hash]))

(defn output->value [out]
  (:value out))
(defn output->address [out]
  (get-in out [:script :address]))

(defn fee
  "the fee is difference between the input value and the output value"
  [transaction]
  (let [ins (inputs transaction)
        outs (outputs transaction)]
    (- (reduce + (map :value ins))
       (reduce + (map :value outs)))))

;; ledger -- the utxo set
;; mapping from out-points to outputs

(defn- apply-transaction-to-ledger [ledger transaction]
  (let [previous-outputs (map :previous-outputs (inputs transaction))
        previous-out-points (map output->out-point previous-outputs)
        hash (hash transaction)
        outputs (outputs transaction)
        out-points (map output->out-point outputs)
        new-entries (into {} (map vector out-points outputs))]
    (as-> ledger l
      (apply dissoc l previous-out-points)
      (merge l new-entries))))

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
