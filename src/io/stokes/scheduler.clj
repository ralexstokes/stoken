(ns io.stokes.scheduler
  (:require [com.stuartsierra.component :as component]
            [clojure.core.async :as async]
            [io.stokes.p2p :as p2p]
            [io.stokes.miner :as miner]
            [io.stokes.state :as state]
            [io.stokes.queue :as queue]))

(defn- cancel-miner [{:keys [channel]}]
  (when-let [cancel @channel]
    (async/close! cancel)))

(defn- run-miner [queue {:keys [channel] :as miner} chain transaction-pool]
  (let [cancel (async/chan)]
    (async/go-loop []
      (let [[_ channel] (async/alts! [cancel] :default :continue)]
        (when-not (= channel cancel)
          (if-let [block (miner/mine miner chain transaction-pool)]
            (queue/submit-block queue block)
            (recur)))))
    (reset! channel cancel)))

(defmulti dispatch queue/dispatch)

(defmethod dispatch :block [{:keys [block]} {:keys [state p2p queue] :as scheduler}]
  (state/add-block state block)
  (p2p/send-block p2p block)
  (queue/submit-request-to-mine queue))

(defmethod dispatch :transaction [{:keys [transaction]} {:keys [state p2p]}]
  (state/add-transaction state transaction)
  (p2p/send-transaction p2p transaction))

(defmethod dispatch :inventory [_ {:keys [queue p2p]}]
  (let [inventory (p2p/query-inventory p2p)]
    (queue/submit-inventory queue inventory)))

(defn- dispatch-mine [{:keys [state queue miner]}]
  (let [chain (state/->best-chain state)
        pool (state/->transactions state)]
    (run-miner queue miner chain pool)))

(defn- dispatch-mine-with-counter [{:keys [state queue miner blocks-to-mine] :as scheduler}]
  (let [remaining @blocks-to-mine]
    (when (pos? remaining)
      (dispatch-mine scheduler)
      (swap! blocks-to-mine dec))))

(defmethod dispatch :mine [_ {:keys [state queue miner blocks-to-mine] :as scheduler}]
  (cancel-miner miner)
  ((if blocks-to-mine
     dispatch-mine-with-counter
     dispatch-mine) scheduler))

(defmethod dispatch :default [msg _]
  (println "unknown message type:" msg))

(defn- start-worker [{:keys [queue] :as scheduler}]
  (let [stop (async/chan)]
    (async/go-loop [[msg channel] (async/alts! [stop queue])]
      (when-not (= channel stop)
        (when msg
          (dispatch msg scheduler)
          (recur (async/alts! [stop queue])))))
    stop))

(defn- start-workers [{:keys [number-of-workers] :as scheduler}]
  (doall
   (map (fn [_] (start-worker scheduler)) (range number-of-workers))))

(defn- stop-worker [worker]
  (async/close! worker))

(defn- stop-workers [workers]
  (doall
   (map stop-worker workers)))

(defn- query-peers [queue]
  (queue/submit-request-for-inventory queue))

(defn- begin-mining [queue]
  (queue/submit-request-to-mine queue))

(defn- start [{:keys [queue] :as scheduler}]
  (let [workers (start-workers scheduler)]
    (query-peers queue)
    (begin-mining queue)
    workers))

(defrecord Scheduler [state queue p2p miner]
  component/Lifecycle
  (start [scheduler]
    (println "starting scheduler...")
    (assoc scheduler :workers (start scheduler)))
  (stop [scheduler]
    (println "stopping scheduler...")
    (cancel-miner miner)
    (stop-workers (:workers scheduler))
    (dissoc scheduler :workers)))

(defn- atomize
  "wrap block counter in an atom so we can mutate it later"
  [{:keys [blocks-to-mine] :as config}]
  (if blocks-to-mine
    (assoc config :blocks-to-mine (atom blocks-to-mine))
    config))

(defn new [config]
  (component/using (map->Scheduler (atomize config))
                   [:state :queue :p2p :miner]))
