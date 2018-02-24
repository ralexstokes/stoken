(ns io.stokes.p2p
  (:require [com.stuartsierra.component :as component]
            [manifold.deferred :as d]
            [manifold.stream :as s]
            [aleph.tcp :as tcp]
            [gloss.core :as gloss]
            [gloss.io :as io]
            [clojure.edn :as edn]
            [io.stokes.queue :as queue]
            [clojure.set :as set]
            [io.stokes.block :as block]
            [clj-time.coerce :as coerce])
  (:refer-clojure :exclude [send])
  (:import (java.net DatagramSocket)))

;; peer management

(defn node-id [node]
  (select-keys node [::host ::port]))

(defn- same-node? [x y]
  (= (node-id x)
     (node-id y)))

(defn- ->peer-set [p2p]
  (-> p2p
      :peer-set
      deref))

(defn- add-peer [set local-node peer]
  (swap! set
         (fn [peer-set]
           (if-not (same-node? local-node peer)
             (conj peer-set peer)
             peer-set))))

(defn- merge-peers [set peers]
  (apply conj set peers))

(defn merge-into-peer-set
  "merges peers into the peer-set; returns set of new peers"
  [{:keys [peer-set] :as node} peers]
  (swap! peer-set merge-peers (disj peers (node-id node))))

(defn new-peer
  ([{:keys [host port]}] (new-peer host port))
  ([host port]
   {::host host
    ::port port}))

;; network interface following the example here:
;; http://aleph.io/examples/literate.html#aleph.examples.tcp

(defn to-wire [{:keys [tag] :as msg}]
  (pr-str
   (condp = tag
     :block (update msg :block block/readable)
     msg)))

(defn from-wire [data]
  (let [{:keys [tag] :as msg} (edn/read-string data)]
    (condp = tag
      :block (update msg :block block/from-readable)
      msg)))

(def protocol
  (gloss/compile-frame
   (gloss/finite-frame :uint32
                       (gloss/string :utf-8))
   to-wire
   from-wire))

(defn- wrap-duplex-stream [protocol s]
  (let [out (s/stream)]
    (s/connect
     (s/map #(io/encode protocol %) out)
     s)
    (s/splice
     out
     (io/decode-stream s protocol))))

(defn- new-client [host port]
  @(d/chain (tcp/client {:host host :port port})
            #(wrap-duplex-stream protocol %)))

(defn- lookup-client [clients node-id]
  (node-id @clients))

(defn- add-client [clients host port]
  (let [client (new-client host port)
        peer-id (node-id (new-peer host port))]
    (swap! clients conj {peer-id client})
    client))

(defn- client-for [{:keys [clients]} {:keys [::host ::port]}]
  (let [peer-id (node-id (new-peer host port))]
    (if-let [client (lookup-client clients peer-id)]
      client
      (add-client clients host port))))

(defn- send-message
  [node peer msg]
  (let [client (client-for node peer)]
    @(s/put! client msg)))

(defn- broadcast [node peers msg]
  (run! #(send-message node % msg) peers))

(defn announce
  "Sends all known peers to each peer in the peer-set"
  [node peer-set]
  (let [local (node-id node)
        all-peers (conj peer-set local)]
    (broadcast node (disj peer-set local) (queue/->peer-set all-peers))))

(defn- start-server [handler port]
  (tcp/start-server
   (fn [s info]
     (handler (wrap-duplex-stream protocol s) info))
   {:port (or port
              0)}))

(def server->port aleph.netty/port)

(defn- start-node [handler host port]
  (let [server (start-server handler port)]
    {:server server
     ::host host
     ::port (server->port server)}))

(defn- close-server [server]
  (.close server))

(defn- stop-node [{:keys [server clients] :as node}]
  (when server
    (close-server server))
  (when clients
    (run! (fn [[_ client ]] (s/close! client)) @clients))
  (-> node
      (dissoc :server)
      (dissoc :peer-set)
      (dissoc :clients)))

(defn- decorate-with-sender [info request]
  (-> request
      (assoc ::host (:remote-addr info))
      (assoc ::port (:server-port info))))

(defn make-handler [queue]
  (fn [s info]
    (s/consume
     #(->> %
           (decorate-with-sender info)
           (queue/submit queue))
     s)))

(defrecord Server [host port queue seed-node]
  component/Lifecycle
  (start [server]
    (let [node (start-node (make-handler queue) host port)
          seed-peer (new-peer seed-node)
          peer-set (if (same-node? node seed-peer)
                     #{}
                     #{seed-peer})
          clients {}
          server (merge server node {:peer-set (atom peer-set)
                                     :clients (atom clients)})]
      (announce server peer-set)
      server))
  (stop [server]
    (merge server (stop-node server))))

(defn new [config]
  (component/using (map->Server config)
                   [:queue]))

(defn send-block [p2p block]
  (broadcast p2p (->peer-set p2p) (queue/->block block)))

(defn send-transaction [p2p transaction]
  (broadcast p2p (->peer-set p2p) (queue/->transaction transaction)))

(defn- select-random-peer [peer-set]
  (when-not (empty? peer-set)
    (->> peer-set
         (into [])
         (rand-nth))))

(defn request-inventory [p2p]
  (when-let [random-peer (select-random-peer (->peer-set p2p))]
    (send-message p2p random-peer (queue/inventory-request))))

(defn send-inventory [p2p peer {:keys [blocks transactions]}]
  (run! #(send-message p2p peer (queue/->block %)) blocks)
  (run! #(send-message p2p peer (queue/->transaction %)) transactions))
