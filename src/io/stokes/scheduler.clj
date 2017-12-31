(ns io.stokes.scheduler
  (:require [com.stuartsierra.component :as component]
            [clojure.core.async :as async]
            [io.stokes.p2p :as p2p]
            [io.stokes.miner :as miner]
            [io.stokes.state :as state]
            [io.stokes.block :as block]))

(defn- get-best-state-from [p2p & {default :or}]
  (if-let [best-chain (p2p/get-best-state p2p)]
    best-chain
    default))

(defn- start-mining [state]
  )

(defn- init [state p2p]
  (get-best-state-from p2p :or (state/->best-chain state))
  (start-mining state))

(defmulti dispatch :tag)
(defmethod dispatch :new-block [{:keys [block]} {:keys [p2p]} state]
  (state/add-block state block)
  (p2p/send-block p2p block))

(defmethod dispatch :new-transaction [{:keys [transaction]} {:keys [p2p]} state]
  (state/add-transaction state transaction)
  (p2p/send-transaction p2p transaction))

(defn- run [scheduler state]
  (let [stop (async/chan)
        [p2p rpc miner] (map :queue [(:p2p scheduler) (:rpc scheduler) (:miner scheduler)])]
    (async/go-loop []
      (async/alt!
        [p2p rpc miner] ([msg] (dispatch msg scheduler state) (recur))
        [stop]          :done))
    (fn []
      (async/close! stop))))

(defrecord Scheduler [state p2p rpc miner]
  component/Lifecycle
  (start [scheduler]
    (println "starting scheduler...")
    (assoc scheduler :stop
           (run scheduler state)))
  (stop [scheduler]
    (println "stopping scheduler...")
    (when-let [stop (:stop scheduler)]
      (stop))
    (dissoc scheduler :stop)))

(defn new [config]
  (component/using (map->Scheduler {})
                   [:state :p2p :rpc :miner]))
