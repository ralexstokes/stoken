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

(defn- publish-block [block queue p2p]
  (queue/submit-block queue block)
  (p2p/send-block p2p block))

(defn- run-miner [{:keys [queue miner p2p] :as scheduler} chain transaction-pool]
  (let [channel (:channel miner)
        cancel (async/chan)]
    (async/go-loop []
      (let [[_ channel] (async/alts! [cancel] :default :continue)]
        (when-not (= channel cancel)
          (if-let [block (miner/mine miner chain transaction-pool)]
            (publish-block block queue p2p)
            (recur)))))
    (reset! channel cancel)))

(defmulti dispatch queue/dispatch)

(defmethod dispatch :block [{:keys [block]} {:keys [state queue] :as scheduler}]
  (state/add-block state block)
  (queue/submit-request-to-mine queue))

(defmethod dispatch :transaction [{:keys [transaction]} {:keys [state p2p]}]
  (state/add-transaction state transaction)
  (p2p/send-transaction p2p transaction))

(defmethod dispatch :mine [{{:keys [force?]} :mine} {:keys [state queue miner total-blocks] :as scheduler}]
  (cancel-miner miner)
  (let [chain (state/->best-chain state)
        pool (state/->transactions state)]
    (if total-blocks
      (when (or force?
                (pos? (- total-blocks (count chain))))
        (run-miner scheduler chain pool))
      (run-miner scheduler chain pool))))

(defmethod dispatch :peers [{peer-set :peers} {:keys [p2p]}]
  (let [new-peers (p2p/merge-into-peer-set p2p peer-set)]
    (run! (partial p2p/announce p2p) new-peers)))

(defmethod dispatch :inventory-request [request {:keys [p2p state]}]
  (let [inventory (state/->inventory state)]
    (p2p/send-inventory p2p request inventory)))

(defmethod dispatch :inventory [{{:keys [transactions blocks]} :inventory} {:keys [queue]}]
  (run! (partial queue/submit-transaction queue) transactions)
  (run! (partial queue/submit-block queue) blocks))

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

(defn- query-peers [p2p]
  (p2p/request-inventory p2p))

(defn- begin-mining [queue]
  (queue/submit-request-to-mine queue))

(defn- start [{:keys [queue p2p] :as scheduler}]
  (let [workers (start-workers scheduler)]
    (query-peers p2p)
    (begin-mining queue)
    workers))

(defrecord Scheduler [state queue p2p miner]
  component/Lifecycle
  (start [scheduler]
    (assoc scheduler :workers (start scheduler)))
  (stop [scheduler]
    (cancel-miner miner)
    (stop-workers (:workers scheduler))
    (dissoc scheduler :workers)))

(defn new [config]
  (component/using (map->Scheduler config)
                   [:state :queue :p2p :miner]))
