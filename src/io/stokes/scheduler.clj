(ns io.stokes.scheduler
  (:require [com.stuartsierra.component :as component]
            [clojure.core.async :as async]
            [io.stokes.p2p :as p2p]
            [io.stokes.miner :as miner]
            [io.stokes.state :as state]
            [io.stokes.queue :as queue]))

(defn- cancel-miner [miner]
  (when-let [cancel @miner]
    (async/close! cancel)))

(defn- run-miner [queue miner chain]
  (let [cancel (async/chan)
        ;; TODO pick a better ceiling?
        seed (rand-int 10000000)]
    (async/go-loop []
      (let [[_ channel] (async/alts! [cancel] :default :continue)]
        (when-not (= channel cancel)
          (when-let [block (miner/mine chain seed 250)]
            (queue/submit-block queue block))
          (recur))))
    (reset! miner cancel)))

(defn- query-state-from-peers [queue p2p]
  (async/go
    (let [inventory (p2p/query-inventory p2p)]
      (queue/submit-inventory queue inventory))))

(defmulti dispatch queue/dispatch)

(defmethod dispatch :block [{:keys [block]} {:keys [state p2p queue] :as scheduler}]
  (state/add-block state block)
  (p2p/send-block p2p block)
  (queue/submit-request-to-mine queue))

(defmethod dispatch :transaction [{:keys [transaction]} {:keys [state p2p]}]
  (state/add-transaction state transaction)
  (p2p/send-transaction p2p transaction))

(defmethod dispatch :inventory [_ {:keys [queue p2p]}]
  (query-state-from-peers queue p2p))

(defmethod dispatch :mine [_ {:keys [state queue miner]}]
  (cancel-miner miner)
  (let [chain (state/->best-chain state)]
    (run-miner queue miner chain)))

(defmethod dispatch :default [msg _]
  (println "unknown message type:" msg))

(defn- start-worker [{:keys [queue miner] :as scheduler}]
  (let [stop (async/chan)]
    (async/go-loop [[msg channel] (async/alts! [stop queue])]
      (when-not (= channel stop)
        (when msg
          (dispatch msg scheduler)
          (recur (async/alts! [stop queue])))))
    stop))

(defn- start-workers [{:keys [number-of-workers] :as scheduler}]
  (loop [workers []
         count number-of-workers]
    (if (zero? count)
      workers
      (recur (conj workers (start-worker scheduler))
             (dec count)))))

(defn- stop-worker [worker]
  (async/close! worker))

(defn- stop-workers [workers]
  (doall
   (map stop-worker workers)))

(defn- query-peers [queue]
  (queue/submit-request-for-inventory queue))

(defn- begin-mining [queue]
  (queue/submit-request-to-mine queue))

(defn- start [{:keys [queue state miner] :as scheduler}]
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

(defn new [config]
  (component/using (map->Scheduler config)
                   [:state :queue :p2p :miner]))
