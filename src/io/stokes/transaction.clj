(ns io.stokes.transaction
  "This namespace implements an UTXO model to send coins from one address to another like Bitcoin's Pay-to-PubKey scheme."
  (:require [io.stokes.hash :as hash])
  (:refer-clojure :exclude [hash]))

(defn hash [transaction]
  (get transaction :hash (hash/of transaction)))

(defn output-in [ledger hash index]
  (nth (get ledger hash) index))

(defn new-input [ledger previous-transaction-hash previous-transaction-output-index signature public-key]
  {:post [(contains? % :type)]}
  (let [output (output-in ledger
                          previous-transaction-hash
                          previous-transaction-output-index)]
    {:type :input
     :value (:value output)
     :previous-output {:hash  previous-transaction-hash
                       :index previous-transaction-output-index}
     :script {:type :signature
              :signature signature
              :public-key public-key}}))

(defn new-output [value address]
  {:post [(contains? % :type)]}
  {:type :output
   :value value
   :script {:type :address
            :address address}})

(defn new [points]
  (assoc points :hash (hash points)))

(defn for-coinbase [address subsidy block-height]
  (io.stokes.transaction/new
   {:inputs [{:type :coinbase-input
              :block-height block-height}]
    :outputs [(new-output subsidy address)]}))

(defn inputs [transaction]
  (:inputs transaction))
(defn outputs [transaction]
  (:outputs transaction))

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

(defn- apply-transaction-to-ledger [ledger transaction]
  (let [ins (map input->previous-hash (inputs transaction))
        outs (outputs transaction)
        hash (hash transaction)]
    (as-> ledger l
      (apply dissoc l ins)
      (assoc l hash outs))))

(defn apply-transactions-to-ledger [ledger transactions]
  (reduce apply-transaction-to-ledger ledger transactions))

(defn ledger [{:keys [initial-state]}]
  (apply-transactions-to-ledger {} initial-state))

(defn- output->balance [out]
  {(output->address out) (output->value out)})

(defn- outputs->balances [outs]
  (reduce (fn [balances out]
            (merge-with + balances (output->balance out))) {} outs))

(defn- update-balance [balances [_ outs]]
  (merge-with + balances (outputs->balances outs)))

(defn ledger-balances [ledger]
  (reduce update-balance {} ledger))
