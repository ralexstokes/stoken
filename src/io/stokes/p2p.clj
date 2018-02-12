(ns io.stokes.p2p
  (:require [com.stuartsierra.component :as component]
            [clojure.edn :as edn]
            [clojure.string :as string]
            [udp-wrapper.core :as udp]
            [gossip.core :as gossip]
            [gossip.utils :as gossip-utils]
            [io.stokes.queue :as queue])
  (:refer-clojure :exclude [send])
  (:import (java.net InetAddress
                     InetSocketAddress
                     DatagramPacket
                     DatagramSocket
                     SocketException)))

(defn- create-node [ip port]
  (ref (gossip/create-node ip port)))

(def ^:private ip (.getHostAddress (udp/localhost)))
(def ^:private packet-size 512)

(defn- create-gossip-node
  "constructs a node for use in the gossip network. allows for specification of a certain port (intended for seed nodes) and otherwise uses an ephemeral port on the local machine"
  [port]
  (let [socket (if port
                 (DatagramSocket. port)
                 (DatagramSocket.))]
    {:node (create-node ip (.getLocalPort socket))
     :socket socket}))

(defn- configure-node [socket node]
  (gossip/schedule-heartbeat-send socket node (* 1000 60 5))
  (gossip/schedule-heartbeat-dec socket node (* 1000 60 5)))

(defn- same-node? [node another-node]
  (= (gossip/get-id @node)
     (gossip/get-id @another-node)))

(defn- join [socket node seed-node]
  (when (not (same-node? node seed-node))
    (gossip/send-initial socket node (gossip/get-id @seed-node))))

(def ^:private gossip-core-data-marker #"--")

(defn- inject-queue [queue packet]
  (let [[type data] (string/split (udp/get-data packet) gossip-core-data-marker)
        work (edn/read-string data)]
    (when (= type "gossip")
      (queue/submit queue work))))

(defn- gossip-handler [queue]
  (fn [socket node packet]
    (inject-queue queue packet)
    (gossip/handle-message socket node packet)))

(defn- gossip-receive-loop [socket node queue]
  (gossip-utils/receive socket (udp/empty-packet packet-size) node (gossip-handler queue)))

(defn- start-node [{:keys [node socket] :as gossip} queue seed-node]
  (let [future (gossip-receive-loop socket node queue)]
    (configure-node socket node)
    (join socket node seed-node)
    (assoc gossip :future future)))

(defn- stop-node [{:keys [node socket future] :as gossip} seed-node]
  (when (not (same-node? node seed-node))
    (gossip/unsubscribe socket node))
  (future-cancel future)
  (udp/close-udp-server socket))

(defrecord Server [port queue seed-node]
  component/Lifecycle
  (start [server]
    (let [gossip (create-gossip-node port)]
      (assoc server :gossip (start-node gossip queue seed-node))))
  (stop [server]
    (when-let [gossip (:gossip server)]
      (stop-node gossip (:seed-node server)))
    (merge server {:gossip nil})))

(defn new [config]
  (component/using (map->Server (update config
                                        :seed-node
                                        (fn [{:keys [ip port]}]
                                          (create-node ip port))))
                   [:queue]))

(defn- send [{{:keys [socket node]} :gossip} data]
  (gossip/gossip socket node (prn-str data)))

(defn get-best-chain [p2p & {:keys [:or default]}]
  ;; TODO ask peers
  default)

(defn query-inventory
  "query-inventory requests blocks and transactions from peers"
  [p2p]
  {:blocks []
   :transactions []})

(defn send-block [p2p block]
  (send p2p (queue/->block block)))

(defn send-transaction [p2p transaction]
  (send p2p (queue/->transaction transaction)))
