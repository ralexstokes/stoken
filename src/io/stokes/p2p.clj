(ns io.stokes.p2p
  (:require [com.stuartsierra.component :as component]
            [clojure.edn :as edn])
  (:refer-clojure :exclude [send])
  (:import (java.net InetAddress
                     InetSocketAddress
                     DatagramPacket
                     DatagramSocket
                     SocketException)))

(defn- empty-packet [n]
  (DatagramPacket. (byte-array n) n))

;; (def localhost (.getLocalHost InetAddress))
(def localhost "localhost")
(def packet-size 512)

(defn- msg-of [host port msg]
  (let [str (prn-str msg)
        payload (.getBytes str)
        length (min (alength payload) packet-size)
        address (InetSocketAddress. host port)]
    (DatagramPacket. payload
                     length
                     address)))

(defn- send [{:keys [socket port]} msg]
  (.send socket (msg-of localhost port msg)))

(defn- recieve-from [socket]
  (let [packet (empty-packet packet-size)]
    (.receive socket packet)
    (String. (.getData packet)
             0 (.getLength packet))))

(defn receive [{:keys [socket]}]
  (recieve-from socket))

(defn- valid? [msg]
  true)

(defn- parse-str [str]
  (edn/read-string str))

(defn- start [port]
  (let [socket (DatagramSocket. port)]
    {:socket socket
     :stop (fn []
             (.close socket))}))

(defrecord Server [port]
  component/Lifecycle
  (start [server]
    (println "starting p2p server...")
    (merge server (start port)))
  (stop [server]
    (println "stopping p2p server...")
    (when-let [stop (:stop server)]
      (stop))
    (merge server {:socket nil
                   :stop nil})))

(defn new [config]
  (component/using (map->Server config)
                   []))

(defn get-best-chain [p2p & {:keys [:or default]}]
  ;; TODO ask peers
  default)

(defn send-block [p2p block]
  (println "publishing new block to peers!!!"))
  ;; (send p2p block))

(defn send-transaction [p2p transaction]
  (println "sending transaction to peers!!!"))
;; (send p2p transaction))

(defn query-inventory [p2p]
  {:blocks []
   :transactions []})
