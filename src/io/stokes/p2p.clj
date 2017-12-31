(ns io.stokes.p2p
  (:require [com.stuartsierra.component :as component]
            [clojure.core.async :as async]))

(defn send-block [p2p block]
  (println "publishing new block to peers!!!"))
(defn send-transaction [p2p transaction]
  (println "publishing transaction to peers!!!"))

(defn- send-empty-block [queue]
  (async/go-loop [block {:nonce 1 :tag :new-block}]
    (Thread/sleep 1000)
    (async/>! queue block)
    (recur (update block :nonce inc))))

(defrecord Server [port state queue]
  component/Lifecycle
  (start [server]
    (println "starting p2p server...")
    (let [c (async/chan)]
      (send-empty-block queue)
      (assoc server :channel c)))
  (stop [server]
    (println "stopping p2p server...")
    (let [{:keys [channel]} server]
      (async/close! channel))
    server))

(defn recv [p2p]
  "test async infra"
  (async/<!! (:channel p2p)))

(defn get-best-state [p2p & {:keys [:or default]}]
  ;; TODO ask buddies
  default)

(defn new [config]
  (component/using (map->Server (assoc config :queue (async/chan)))
                   [:state]))
