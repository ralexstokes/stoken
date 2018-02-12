(ns io.stokes.p2p
  (:require [com.stuartsierra.component :as component]
            [clojure.edn :as edn]
            [clojure.string :as string]
            [udp-wrapper.core :as udp]
            [io.stokes.queue :as queue]
            [clojure.set :as set]
            [clojure.pprint :as pp]
            [io.stokes.block :as block]
            [clj-time.coerce :as coerce])
  (:refer-clojure :exclude [send])
  (:import (java.net DatagramSocket)))

(def ^:private ip (.getHostAddress (udp/localhost)))
(def ^:private packet-size 1024)

(defn- new-peer
  ([{:keys [ip port]}] (new-peer ip port))
  ([ip port]
   {:ip ip
    :port port}))

(defn- make-packet [ip port msg]
  (udp/packet
   (udp/get-bytes-utf8 (prn-str msg))
   (udp/make-address ip)
   port))

(defn node-id [node]
  (select-keys node [:ip :port]))

(defn- same-node? [x y]
  (= (node-id x)
     (node-id y)))

(defn- new-peer-set [& peers]
  (atom (into #{} peers)))

(defn- add-peer [set local-node peer]
  (swap! set
         (fn [peer-set]
           (if-not (same-node? local-node peer)
             (conj peer-set peer)
             peer-set))))

(defn- send-message
  ([{:keys [socket peer-set] :as node} msg]
   (run! #(send-message node % msg)
         @peer-set))
  ([{:keys [socket]} {:keys [ip port]} msg]
   (let [packet (make-packet ip port msg)]
     (udp/send-message socket packet))))

(defn- recv-message [socket packet]
  (udp/receive-message socket packet)
  (let [ip (udp/get-ip packet)
        port (udp/get-port packet)
        data (udp/get-data packet)]
    (merge (edn/read-string data)
           {::ip ip
            ::port port})))

(defn- start-recv-loop [socket handler]
  (let [packet (udp/empty-packet packet-size)]
    (future (while true
              (handler (recv-message socket packet))))))

(defn- start-node [port handler]
  (let [peer-set (new-peer-set)
        socket (if port
                 (DatagramSocket. port)
                 (DatagramSocket.))
        recv-loop (start-recv-loop socket handler)]
    {:peer-set peer-set
     :socket socket
     :recv-loop recv-loop
     :ip (.getHostAddress (udp/localhost))
     :port (.getLocalPort socket)}))

(defn- stop-node [{:keys [socket recv-loop] :as node}]
  (when recv-loop
    (future-cancel recv-loop))
  (when socket
    (udp/close-udp-server socket))
  {:socket nil
   :recv-loop nil})

(defn merge-into-peer-set
  "merges new-peers into the peer-set; returns set of new peers"
  [{:keys [peer-set] :as node} peers]
  (let [new-peers (set/difference peers @peer-set)
        insertion (comp (partial add-peer peer-set (node-id node))
                        node-id)]
    (run! insertion new-peers)
    new-peers))

(defn- ->peer-set [p2p]
  (-> p2p
      :peer-set
      deref))

(defn announce [local remote]
  (when-not (same-node? local remote)
    (let [all-peers (conj (->peer-set local)
                          (node-id local))]
      (send-message local remote (queue/->peer-set all-peers)))))

(defn- msg-handler [queue]
  (fn [msg]
    (queue/submit queue (if (= :block (:tag msg))
                          (update-in msg [:block :time]
                                     coerce/from-date)
                          msg))))

(defrecord Server [port queue seed-node]
  component/Lifecycle
  (start [server]
    (let [node (start-node port msg-handler)
          seed-node (new-peer seed-node)]
      (announce node seed-node)
      (merge server node)))
  (stop [server]
    (merge server (stop-node server))))

(defn new [config]
  (component/using (map->Server config)
                   [:queue]))

(defn get-best-chain [p2p & {:keys [:or default]}]
  ;; TODO ask peers
  default)

(defn query-inventory
  "query-inventory requests blocks and transactions from peers"
  [p2p]
  {:blocks []
   :transactions []})

(defn send-block [p2p block]
  (send-message p2p (-> block
                        block/readable
                        queue/->block)))

(defn send-transaction [p2p transaction]
  (send-message p2p (queue/->transaction transaction)))
