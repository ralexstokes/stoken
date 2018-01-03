(ns io.stokes.state
  (:require
   [io.stokes.ledger :as ledger]
   [io.stokes.transaction :as transaction]
   [io.stokes.transaction-pool :as transaction-pool]
   [io.stokes.block :as block]))

(defn new [{:keys [ledger transaction-pool blockchain]}]
  (atom {:ledger (ledger/from ledger)
         :transaction-pool (transaction-pool/new transaction-pool)
         :blockchain (block/chain-from blockchain)}))

(defn- ->ledger [state]
  (:ledger @state))
(defn- ->blockchain [state]
  (:blockchain @state))
(defn- ->transaction-pool [state]
  (:transaction-pool @state))

(defn- update-ledger [state ledger f]
  (swap! state (fn [state]
                 (assoc state :ledger ledger))))
(defn- update-blockchain [state blockchain]
  (swap! state (fn [state]
                 (assoc state :blockchain blockchain))))
(defn- update-transaction-pool [state pool]
  (swap! state (fn [state]
                 (assoc state :transaction-pool pool))))

(defn add-transaction [state transaction]
  (let [pool (->transaction-pool state)]
    (->> transaction
         (transaction-pool/add pool)
         (update-transaction-pool state))))

(defn- update-state [state key f & rest]
  (swap! state (fn [state]
                 (let [val (key state)]
                   (assoc state key (apply f val rest))))))

(defn- adjust-ledger [state transactions]
  (update-state state
                :ledger
                ledger/apply-transactions transactions))

(defn- adjust-transaction-pool [state transactions]
  (update-state state
                :transaction-pool
                transaction-pool/remove-transactions transactions))

(defn- add-to-chain [state block]
  (update-state state
                :blockchain
                block/add-to-chain block))

(defn add-block [state {:keys [transactions] :as block}]
  (adjust-ledger state transactions)
  (adjust-transaction-pool state transactions)
  (add-to-chain state block))

(defn ->balances [state]
  (-> state
      ->ledger
      ledger/balances))

(defn ->best-chain [state]
  (-> state
      ->blockchain
      block/best-chain))

(defn ->transactions [state]
  (-> state
      ->transaction-pool))
