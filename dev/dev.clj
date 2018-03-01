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

;; some convenience data for development

(def seed-node-host (-> (java.net.InetAddress/getLocalHost)
                        .getHostAddress))
(def seed-node-port 40404)

(def max-threshold-str-hard
  "00000000FFFF0000000000000000000000000000000000000000000000000000")

(def max-threshold-str-medium
  "000FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF")

(def max-threshold-str-easy
  "0FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF")

(def transactions [])
(def total-blocks 20)
(def max-threshold-str max-threshold-str-easy)
(def seed-node? true)
(def peer-count 3)
(def max-seed-for-mining 1000000)

;; mine the genesis block

(defn- hex->bignum [str]
  (BigInteger. str 16))

(defn- max-threshold
  "maximum threshold used in Bitcoin; https://en.bitcoin.it/wiki/Target"
  [str]
  (hex->bignum str))

(defonce some-keys (repeatedly key/new-pair))
(def coinbase-key (first some-keys))

(defn- coinbase-key-for [node-number]
  (nth some-keys node-number))

(defn- coinbase-for [node-number]
  (-> node-number
      coinbase-key-for
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

(defonce genesis-block
  (mine-until-sealed [] (transaction-pool/new {}) coinbase))

;; tools to construct a network of many nodes

(defn- config [transactions total-blocks max-threshold-str coinbase seed-node? max-seed-for-mining]
  {:rpc              {:port 3000
                      :shutdown-timeout-ms 1000}
   :p2p              {:host seed-node-host
                      :port (if seed-node? seed-node-port nil)
                      :seed-node {:host seed-node-host
                                  :port seed-node-port}}
   :scheduler        {:number-of-workers 1
                      :total-blocks total-blocks}
   :miner            {:number-of-rounds 1000
                      :coinbase coinbase
                      :max-threshold (max-threshold max-threshold-str)
                      :max-seed max-seed-for-mining}
   :blockchain       {:initial-state genesis-block}
   :transaction-pool {:initial-state transactions}
   :ledger           {:initial-state (:transactions genesis-block)}})

(def seed-node-config (config transactions total-blocks max-threshold-str (coinbase-for 0) seed-node? max-seed-for-mining))

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
      (select-keys [:io.stokes.p2p/host :io.stokes.p2p/port :peer-set])
      (update-in [:peer-set] deref)))

(defn- network-connectivity [network]
  (->> network
       ->nodes
       (map node->p2p-network)))

(defn- find-all-peers
  "gathers all peers across the network into a set"
  [lists]
  (reduce (fn [set {:keys [:io.stokes.p2p/host :io.stokes.p2p/port]}]
            (conj set (p2p/new-peer host port))) #{} lists))

(defn- add-missing-peers
  "decorates each peer with the other peers it is missing"
  [peers]
  (let [all-peers (find-all-peers peers)]
    (map (fn [peer]
           (let [known (conj
                        (:peer-set peer)
                        (p2p/node-id peer))
                 missing (set/difference all-peers known)]
             (assoc peer :missing missing)))
         peers)))

(defn- describe-network-topology [system]
  (let [peers (network-connectivity system)]
    (add-missing-peers peers)))

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

(defn- head-consensus? [system]
  (apply = (apply-chains chain->head-block-hash system)))

(defn- chain-consensus?
  "indicates if every peer in the system has the same view of the chain's head"
  [system genesis-block-hash]
  (and
   (genesis-consensus? system genesis-block-hash)
   (chain-walks-consistent? system)
   (head-consensus? system)))

(defn- chains-same-lenth? [system]
  (apply = (apply-chains count system)))

(defn- all-blocks [system]
  (->> system
       ->block-tree
       (mapcat block/tree->blocks)
       distinct))

(defn- compare-chains-by-hash [system]
  (->> (apply-chains #(map :hash %) system)
       (apply map vector)
       (drop-while #(apply = %))))

(defn- chain-status [system genesis-block]
  (let [genesis-hash (:hash genesis-block)
        genesis-consensus? (genesis-consensus? system genesis-hash)
        block-counts (apply-chains count system)
        heads (apply-chains chain->head-block-hash system)
        head-consensus? (head-consensus? system)]
    {:genesis-hash genesis-hash
     :genesis-consensus? genesis-consensus?
     :block-counts block-counts
     :heads heads
     :head-consensus? head-consensus?}))

(defn- ->balance [node]
  (->> node
       :state
       state/->balances))

(defn- balances [system]
  (->> system
       ->nodes
       (map ->balance)))

(defn- ->ledger [node]
  (->> node
       :state
       state/->ledger))

(defn- ledgers [system]
  (->> system
       ->nodes
       (map ->ledger)))

(defn- same-balances? [system]
  (->> system
       balances
       (apply =)))

(defn- same-ledgers? [system]
  (->> system
       ledgers
       (apply =)))

(defn- from-node
  ([system ks] (from-node system ks first))
  ([system ks selector]
   (->> (select-keys (->> system
                          ->nodes
                          selector) ks)
        vals
        (into []))))

(defn- transaction-for
  "makes a transaction that spends `value` from the `out-points` and then returns the remainder to the sender"
  [ledger from-keys to-keys value out-points]
  (let [previous-outputs (map (partial transaction/output-from-out-point ledger) out-points)
        proofs (map (partial transaction/generate-output-proof from-keys) previous-outputs)
        input (transaction/new-input previous-outputs proofs)
        inputs [input]
        outputs [(transaction/new-output value (key/->address to-keys))
                 (transaction/new-output (- (transaction/input->value input)
                                            value) (key/->address from-keys))]]
    (transaction/new inputs outputs)))

(defn inject-transaction
  ([system transaction-data]
   (let [[queue state] (from-node system [:queue :state])
         ledger (:ledger @state)]
     (inject-transaction queue ledger transaction-data)))
  ([queue ledger {:keys [from-keys to-keys value previous-out-points]}]
   (let [transaction (transaction-for ledger from-keys to-keys value previous-out-points)]
     (queue/submit-transaction queue transaction))))


(defn ledger-reconciles-chain [node]
  (let [balance (->balance node)
        total-coins-on-ledger (reduce + (vals balance))
        blockchain (node->best-chain node)
        transactions (mapcat :transactions blockchain)
        coinbase-transactions (filter transaction/coinbase? transactions)
        coinbase-outputs (mapcat transaction/outputs coinbase-transactions)
        total-coins-on-chain (reduce + (map transaction/output->value coinbase-outputs))]
    (>= total-coins-on-chain
        total-coins-on-ledger)))

(defn each-node-reconciles-ledger-with-chain [system]
  (every? true? (map ledger-reconciles-chain (->nodes system))))

(defn- healthy-network? [system genesis-block]
  (let [tests [fully-connected?
               #(chain-consensus? % (:hash genesis-block))
               same-ledgers?
               each-node-reconciles-ledger-with-chain]]
    (every? true?
            (map #(% system) tests))))

;; launch a network of `peer-count` peers

(set-init (fn [_] (network-of {:peer-count peer-count})))

(comment ;; some utilities
  (apply-chains #(map :hash %) system)
  (apply-chains #(map :transactions %) system)
  (apply-chains #(map :difficulty %) system)
  (chain-walks-consistent? system)
  (compare-chains-by-hash system)
  (apply-chains chain->genesis-block-hash system)
  (apply-chains chain->head-block-hash system))

(comment
  ;; tests transaction construction and validation
  ;; should move into test/ but need a way to restore genesis state and the requisite key first
  (let [ledger (->> system
                    ->nodes
                    first
                    :state
                    deref
                    :ledger)
        from-keys      (coinbase-key-for 0)
        to-keys        (coinbase-key-for 1)
        value 10
        transactions (:transactions genesis-block)
        genesis-transaction (first transactions)
        out-points [(-> genesis-transaction
                        :outputs
                        first
                        (select-keys [:hash :index]))] ;; have to treat coinbase output in special way
        transaction (transaction-for ledger from-keys to-keys value out-points)]
    (#'transaction/apply-transaction-to-ledger ledger transaction))
  )

(comment
  ;; transaction
  (let [transactions (:transactions genesis-block)
        genesis-transaction (first transactions)
        out-points [(-> genesis-transaction
                        :outputs
                        first
                        (select-keys [:hash :index]))]]
    (inject-transaction system {:from-keys (coinbase-key-for 0)
                                :to-keys (coinbase-key-for 1)
                                :previous-out-points out-points
                                :value 10}))

  (->> system
       ->nodes
       (map :state)
       (map state/->transactions)
       (map #(map :hash %))
       (apply map vector))

  (->> system
       ->nodes
       (map :state)
       (map deref)
       (map :ledger))

  (->> system
       ->nodes
       (map :queue)
       (map #(queue/submit-request-to-mine % :force? true)))


  (let [states (mapcat #(from-node system [:state] (fn [nodes] (nth nodes %))) (range 3))
        ledgers (map #(-> %
                          deref
                          :ledger) states)]
    (apply = ledgers))

  (def seed-node
    (->> system
         ->nodes
         first))

  ;; p2p
  (describe-network-topology system)

  ;; blockchain
  (chain-status system genesis-block)

  ;; network level checks
  (healthy-network? system genesis-block)

  (fully-connected? system)

  (chain-consensus? system (:hash genesis-block))
  (chains-same-lenth? system)

  (each-node-reconciles-ledger-with-chain system)

  (ledger-reconciles-chain (->> system
                                ->nodes
                                second))

  (same-ledgers? system)
  (same-balances? system)
  (balances system)

  ;; control
  (stop)
  (reset)
  )

