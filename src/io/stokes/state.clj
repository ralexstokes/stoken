(ns io.stokes.state
  (:require
   [io.stokes.transaction :as transaction]
   [io.stokes.transaction-pool :as transaction-pool]
   [io.stokes.block :as block]))

(defn new [{:keys [ledger transaction-pool blockchain]}]
  (atom {:ledger (transaction/ledger ledger)
         :transaction-pool (transaction-pool/new transaction-pool)
         :blockchain (block/chain-from blockchain)
         :orphan-blocks #{}}))

(defn- state-writer [key f & rest]
  (fn [state]
    (apply update state key f rest)))

(defn- reader
  ([state reader]
   (reader @state))
  ([state reader & readers]
   (let [snapshot @state]
     (map #(% snapshot) (conj readers reader)))))

(defn- write!
  "takes a series of `state-writer`s"
  [state & writers]
  (swap! state (apply comp writers)))

(defn- adjust-ledger [transactions]
  (state-writer :ledger
                transaction/apply-transactions-to-ledger transactions

(defn add-transaction [state transaction]
  (write! state
          (state-writer :transaction-pool
                        transaction-pool/add)))

(defn- remove-from-transaction-pool [transactions]
  (state-writer :transaction-pool
                transaction-pool/remove-transactions transactions))

(defn- add-to-chain [block]
  (fn [state]
    (let [orphans (:orphan-blocks state)
          blockchain (:blockchain state)
          [next-chain next-orphans] (block/add-to-chain blockchain (conj orphans block))]
      (merge state {:blockchain next-chain
                    :orphan-blocks next-orphans}))))

(defn add-block [state {:keys [transactions] :as block}]
  (write! state
          (adjust-ledger transactions)
          (remove-from-transaction-pool transactions)
          (add-to-chain block)))

(defn ->ledger [state]
  (-> state
      (reader :ledger)))

(defn ->balances [state]
  (-> (->ledger state)
      transaction/ledger-balances))

(defn ->blockchain [state]
  (-> state
      (reader :blockchain)))

(defn ->best-chain [state]
  (-> (->blockchain state)
      block/best-chain))

(defn ->transactions [state]
  (reader state :transaction-pool))

(defn ->inventory [state]
  (let [[pool blocks] (reader state
                              :transaction-pool
                              (comp block/best-chain :blockchain))]
    {:transactions pool
     :blocks blocks}))

(defn contains-transaction?
  "Indicates if the given transaction is in the mempool or if it is already in the chain"
  [state transaction]
  (let [pool (->transaction-pool state)
        ledger (->ledger state)]
    (or
     (contains? ledger (:hash transaction))
     (contains? pool transaction))))
