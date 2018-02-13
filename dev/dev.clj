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
   [clojure.set :as set]
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

(def max-threshold-str-medium
  "000FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF")

(def max-threshold-str-easy
  "0FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF")

(defn- max-threshold
  "maximum threshold used in Bitcoin; https://en.bitcoin.it/wiki/Target"
  [str]
  (hex->bignum str))

(def some-keys (repeatedly key/new))
(def coinbase-key (first some-keys))
(defn- coinbase-for [node-number]
  (-> some-keys
      (nth node-number)
      key/->address))

(def coinbase (coinbase-for 0))

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

(def seed-node-ip (.getHostAddress (udp/localhost)))
(def seed-node-port 40404)

(defn- config [transactions blocks-to-mine max-threshold-str coinbase seed-node?]
  {:rpc              {:port 3000
                      :shutdown-timeout-ms 1000}
   :p2p              {:port (if seed-node? seed-node-port nil)
                      :seed-node {:ip seed-node-ip
                                  :port seed-node-port}}
   :scheduler        {:number-of-workers 1
                      :blocks-to-mine blocks-to-mine}
   :miner            {:number-of-rounds 1000
                      :coinbase coinbase
                      :max-threshold (max-threshold max-threshold-str)
                      :max-seed 1000000}
   :blockchain       {:initial-state genesis-block}
   :transaction-pool {:initial-state transactions}
   :ledger           {:initial-state (:transactions genesis-block)}})

;; some convenience data for development

(def transactions [])
(def blocks-to-mine 2)
(def max-threshold-str max-threshold-str-medium)
(def seed-node? true)
(def peer-count 2)

;; tools to construct a network of many nodes

(def seed-node-config (config transactions blocks-to-mine max-threshold-str (coinbase-for 0) seed-node?))

(defn seed-node-system
  "Constructs a system map suitable for interactive development."
  []
  (node/from seed-node-config))

(defn peer-node-config [id]
  (-> seed-node-config
      (update-in [:p2p :port] (constantly nil))
      (update-in [:miner :coinbase] (constantly (coinbase-for id)))
      (update-in [:rpc :port] #(+ % id))))

(defn peer-node-system
  [id]
  (node/from (peer-node-config id)))

(defn create-peers
  "creates a stream of peer nodes"
  [n]
  (->> (range)
       (drop 1)
       (map peer-node-system)
       (take n)))

(defrecord Network [nodes]
  component/Lifecycle
  (start [this]
    (swap! nodes
           (fn [nodes]
             (mapv #(component/start %) nodes)))
    this)
  (stop [this]
    (swap! nodes #(->> (reverse %1)
                       (mapv component/stop)))
    this))

(defn- network-of [{:keys [peer-count]}]
  (let [nodes (concat [(seed-node-system)]
                      (create-peers (dec peer-count)))]
    (Network. (atom nodes))))

(defn- ->nodes [system]
  (-> system
      :nodes
      deref))

(defn- node
  ([system] (node system 0))
  ([system n] (get (->nodes system) n nil)))

(defn- node->p2p-network
  "returns the local data for p2p nodes"
  [node]
  (-> node
      :p2p
      (select-keys [:ip :port :peer-set])
      (update-in [:peer-set] deref)))

(defn- network-connectivity [network]
  (->> network
       ->nodes
       (map node->p2p-network)))

(defn- find-all-peers
  "gathers all peers across the network into a set"
  [lists]
  (reduce (fn [set {:keys [port peer-set]}]
            (into set peer-set)) #{} lists))

(defn- add-missing-peers
  "decorates each peer with the other peers it is missing"
  [peers]
  (let [all-peers (find-all-peers peers)]
    (map (fn [peer]
           (let [known (conj
                        (:peer-set peer)
                        (select-keys peer [:ip :port]))
                 missing (set/difference known all-peers )]
             (assoc peer :missing missing)))
         peers)))

(defn- describe-network-topology [system]
  (let [peers (network-connectivity system)]
    (add-missing-peers peers )))

(defn- fully-connected?
  "indicates if every peer in the system knows about every other peer"
  [system]
  (->> system
       describe-network-topology
       (every? (comp empty? :missing))))

(defn- node->chain [node]
  (-> node
      :state
      state/->blockchain))

(defn- node->best-chain [node]
  (-> node
      :state
      state/->best-chain))

(defn- ->block-tree [system]
  (->> system
       ->nodes
       (map node->chain)))

(defn- ->chain [system]
  (->> system
       ->nodes
       (map node->best-chain)))

(defn- hash-at-block [chain selector]
  (let [block (selector chain)]
    (block/hash block)))

(defn- chain->genesis-block-hash [chain]
  (hash-at-block chain first))

(defn- chain->head-block-hash [chain]
  (hash-at-block chain last))

(defn- apply-chains [f system]
  (->> system
       ->chain
       (map f)))

(defn- genesis-consensus? [system genesis-block-hash]
  (->> system
       (apply-chains chain->genesis-block-hash)
       (every? #(= % genesis-block-hash))))

(defn- chain-consensus?
  "indicates if every peer in the system has the same view of the chain's head"
  [system genesis-block-hash]
  (and
   (genesis-consensus? system genesis-block-hash)
   (apply = (apply-chains chain->head-block-hash system))))

(defn- chains-same-lenth? [system]
  (apply = (apply-chains count system)))

(defn- all-blocks [system]
  (->> system
       ->chain
       (reduce into #{})))

(defn- inspect-chains [system ks]
  (let [chains (->chain system)
        from-chain (fn [ks]
                     (fn [chain]
                       (map #(select-keys % ks) chain)))]
    (->> chains
         (map (from-chain ks)))))

(defn- valid-chain-walk? [last-block block]
  (if (= (:hash last-block)
         (:previous-hash block))
    block
    nil))

(defn- chain-walks-consistent? [system]
  (let [hash-links (inspect-chains system [:previous-hash :hash])]
    (->> hash-links
         (map #(partition-all 2 %))
         (map #(reduce valid-chain-walk? %))
         (map (comp not nil?))
         (every? true?))))

(defn- healthy-network? [system]
  (let [tests [fully-connected?
               chain-consensus?]]
    (every? true?
            (map #(% system) tests))))

(defn ledger [system] (:ledger @(:state system)))
(defn balances [system] (state/->balances (:state system)))

;; launch a network of `peer-count` peers

(set-init (fn [_] (network-of {:peer-count peer-count})))

(comment
  (healthy-network? system)

  (describe-network-topology system)
  (fully-connected? system)

  (chain-consensus? system)
  (chains-same-lenth? system)
  (apply-chains system count)
  (apply =
         (apply-chains system chain->genesis-block-hash))
  (apply-chains system chain->genesis-block-hash)
  (apply-chains system chain->head-block-hash)
  (apply-chains system #(map block/hash %))

  (defn- diff-chains [chains]
    (->> chains
         (apply map vector)
         ))

  (->> system
       ->chains
       (apply map vector)
       )

  (stop)
  (reset)


  (def seed-node
    (->> system
         ->nodes
         first))

  seed-node
  (def seed-p2p
    (:p2p seed-node))
  (def seed-chain
    (-> seed-node
        :state
        deref
        :blockchain))

  seed-chain
  )
