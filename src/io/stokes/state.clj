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
  (swap! state (apply comp (reverse writers))))

(defn add-transaction [state transaction]
  (write! state
          (state-writer :transaction-pool
                        transaction-pool/add transaction)))

(defn- adjust-ledger
  "should be called atomically with `add-to-chain`; see `add-block`"
  [state]
  (let [transactions (->> state
                          :blockchain
                          block/best-chain
                          last
                          :transactions)]
    (update state :ledger
            (fn [ledger] (transaction/apply-transactions-to-ledger ledger transactions)))))

(defn- remove-from-transaction-pool
  "should be called atomically with `add-to-chain`; see `add-block`"
  [state]
  (let [transactions (-> state
                         :blockchain
                         block/best-chain
                         last
                         :transactions)]
    (update state :transaction-pool
            transaction-pool/remove-transactions transactions)))

(defn- add-to-chain [block]
  (fn [state]
    (let [orphans (:orphan-blocks state)
          blockchain (:blockchain state)
          [next-chain next-orphans] (block/add-to-chain blockchain (conj orphans block))]
      (merge state {:blockchain next-chain
                    :orphan-blocks next-orphans}))))

(defn add-block [state block]
  (write! state
          (add-to-chain block) ;; this happens first so we can include the proper transactions in the next actions
          adjust-ledger
          remove-from-transaction-pool))

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

(defn contains-block?
  "Indicates if the given block is in the chain or the orphan set"
  [state block]
  (let [[blockchain orphan-blocks] (reader state
                                           :blockchain
                                           :orphan-blocks)]
    (or (block/chain-contains-block? blockchain block)
        (contains? orphan-blocks block))))

(defn contains-transaction?
  "Indicates if the given transaction is in the mempool"
  [state transaction]
  (let [pool (reader state :transaction-pool)]
    (contains? pool transaction)))
