(ns dev
  "Tools for interactive development with the REPL. This file should
  not be included in a production build of the application.

  Call `(reset)` to reload modified code and (re)start the system.

  The system under development is `system`, referred from
  `com.stuartsierra.component.repl/system`.

  See also https://github.com/stuartsierra/component.repl"
  (:require
   [clojure.java.io :as io]
   [clojure.java.javadoc :refer [javadoc]]
   [clojure.pprint :refer [pprint]]
   [clojure.reflect :refer [reflect]]
   [clojure.repl :refer [apropos dir doc find-doc pst source]]
   [clojure.set :as set]
   [clojure.string :as string]
   [clojure.test :as test]
   [clojure.edn :as edn]
   [clojure.tools.namespace.repl :refer [refresh refresh-all clear]]
   [com.stuartsierra.component :as component]
   [com.stuartsierra.component.repl :refer [reset set-init start stop system]]
   [crypto.random :as rand]
   [udp-wrapper.core :as udp]
   [io.stokes.node :as node]
   [io.stokes.rpc :as rpc]
   [io.stokes.p2p :as p2p]
   [io.stokes.block :as block]
   [io.stokes.transaction :as transaction]
   [io.stokes.miner :as miner]
   [io.stokes.state :as state]
   [io.stokes.queue :as queue]
   [io.stokes.hash :as hash]
   [io.stokes.key :as key]
   [io.stokes.transaction-pool :as transaction-pool]))

(def pp pprint)

;; Do not try to load source code from 'resources' directory
(clojure.tools.namespace.repl/set-refresh-dirs "dev" "src" "test")

(defn- hex->bignum [str]
  (BigInteger. str 16))

(def max-threshold-str-hard
  "00000000FFFF0000000000000000000000000000000000000000000000000000")

(def max-threshold-str-easy
  "0FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF")

(defn- max-threshold
  "maximum threshold used in Bitcoin; https://en.bitcoin.it/wiki/Target"
  [str]
  (hex->bignum str))

(def some-keys (take 10 (repeatedly key/new)))
(def coinbase-key (first some-keys))
(def coinbase (key/->address coinbase-key))

(defn mine-until-sealed [chain transaction-pool coinbase]
  (let [miner {:coinbase coinbase
               :max-threshold (max-threshold max-threshold-str-easy)
               :max-seed 1000000}]
    (loop [block (miner/mine miner chain transaction-pool)]
      (if block
        block
        (recur (miner/mine miner chain transaction-pool))))))

(def genesis-block
  (mine-until-sealed [] (transaction-pool/new {}) coinbase))

(def genesis-string (pr-str (block/readable genesis-block)))

(def seed-node-ip (.getHostAddress (udp/localhost)))
(def seed-node-port 57177)

(defn- config [transactions blocks-to-mine easy-mining? coinbase seed-node?]
  {:rpc              {:port 3000
                      :shutdown-timeout-ms 1000}
   :p2p              {:port (if seed-node? seed-node-port nil)
                      :seed-node {:ip seed-node-ip
                                  :port seed-node-port}}
   :scheduler        {:number-of-workers 1
                      :blocks-to-mine blocks-to-mine}
   :miner            {:number-of-rounds 1000
                      :coinbase coinbase
                      :max-threshold (max-threshold (if easy-mining?
                                                      max-threshold-str-easy
                                                      max-threshold-str-hard))
                      :max-seed 1000000}
   :blockchain       {:initial-state genesis-block}
   :transaction-pool {:initial-state transactions}
   :ledger           {:initial-state (:transactions genesis-block)}})

;; some convenience data for development

(def transactions [])
(def blocks-to-mine 2)
(def easy-mining? true)
(def seed-node? true)
(def peer-count 2) ;; excludes the seed node

(def seed-node-config (config transactions blocks-to-mine easy-mining? coinbase seed-node?))

(defn seed-node-system
  "Constructs a system map suitable for interactive development."
  []
  (node/from seed-node-config))

(defn peer-node-config [sequence-number]
  (-> seed-node-config
      (update-in
       [:p2p :port]
       (constantly nil))
      (update-in
       [:rpc :port]
       (fn [port]
         (+ port sequence-number 1)))))

(defn peer-node-system
  [n]
  (node/from (peer-node-config n)))

(defn create-peers
  "creates a stream of peer nodes"
  [n]
  (->> (range)
       (map peer-node-system)
       (take n)))

(defn network-of [{:keys [peer-count]}]
  (let [nodes (concat [(seed-node-system)]
                      (create-peers peer-count))]
    {:nodes (atom nodes)}))

(defn ->nodes [system]
  (-> system
      :nodes
      deref))

(defn apply->nodes [system f]
  (->> system
       ->nodes
       (map f)))

(defn node
  ([system] (node system 0))
  ([system n] (get (->nodes system) n nil)))

(defn node->p2p-info
  "returns the local data for p2p nodes"
  [node]
  (-> node
      :p2p
      :gossip
      :node
      deref))

(defn node->chain [node]
  (-> node
      :state
      state/->best-chain))

(defn ledger [system] (:ledger @(:state system)))
(defn balances [system] (state/->balances (:state system)))

(defn with-node
  ([f] (with-node 0 f))
  ([n f] (-> system
             (node n)
             f)))

(defrecord Network [nodes]
  component/Lifecycle
  (start [this]
    (swap! nodes #(mapv component/start %1))
    this)
  (stop [this]
    (swap! nodes #(->> (reverse %1)
                       (mapv component/stop)))
    this))

(set-init (fn [_] (map->Network
                   (network-of {:peer-count peer-count}))))
