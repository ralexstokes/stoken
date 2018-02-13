(ns io.stokes.block
  (:require [io.stokes.hash :as hash]
            [clojure.set :as set]
            [clj-time.core :as time]
            [clj-time.coerce :as coerce])
  (:refer-clojure :exclude [hash]))

(def ^:private block-header-keys #{:previous-hash
                                   :difficulty
                                   :transaction-root
                                   :time
                                   :nonce})

(defn header [block]
  (select-keys block block-header-keys))

(defn hash [block]
  (get block :hash
       (some-> block
               header
               hash/of)))

(defn difficulty [block]
  (get block :difficulty 0))

(defn- previous [block]
  (:previous-hash block))

(defn with-nonce [block nonce]
  (assoc block :nonce nonce))

(defn readable
  "returns a human-readable description of the block"
  [block]
  (assoc block :time (coerce/to-date (:time block))))

(def ^:private target-blocktime 10000) ;; milliseconds

(defn- timestamps->blocktimes [[a b]]
  (time/in-seconds
   (time/interval b a)))

(defn- average [default coll]
  (if (seq coll)
    (let [n (count coll)
          sum (reduce + coll)]
      (/ sum n))
    default))

(defn- calculate-average-blocktime
  [timestamps]
  (->> timestamps
       reverse
       (take 4)
       (partition 2)
       (map timestamps->blocktimes)
       (average target-blocktime)))

(defn- calculate-difficulty
  "adjust difficulty so that the average time between blocks is N seconds"
  [block timestamps]
  (let [difficulty (difficulty block)
        average-blocktime (calculate-average-blocktime timestamps)
        next        (if (> average-blocktime target-blocktime)
                      dec
                      inc)]
    (next difficulty)))

(defn- header-from [chain transactions]
  (let [previous-block (last chain)
        block-timestamps (map :time chain)]
    {:previous-hash    (hash previous-block)
     :difficulty       (calculate-difficulty previous-block block-timestamps)
     :transaction-root (-> transactions
                           hash/tree-of
                           hash/root-of)
     :time             (time/now)
     :nonce            0}))

(defn next-template
  "generates a block with `transactions` that satisfies the constraints to be appended to the chain modulo a valid proof-of-work, i.e. a nonce that satisfies the difficulty in the block, also implies a missing block hash in the returned data. note: timestamp in the block header is currently included in the hash pre-image; given that a valid block must be within some time interval, client code MUST refresh this timestamp as needed; if you are having issues, run the proof-of-work routine for a smaller number of rounds"
  [chain transactions]
  {:post [(let [keys (->> %
                          keys
                          (into #{}))]
            (set/subset? block-header-keys keys))]}
  (merge {:transactions transactions}
         (header-from chain transactions)))

(defn- node-of [block & children]
  (merge {:block block} (when children
                          {:children children})))

(defn- node->block [node]
  (:block node))
(defn- node->children [node]
  (:children node))

(defn- parent?
  "indicates if a is a parent of b"
  [a b]
  (= (previous b)
     (hash a)))

(defn- insert [new-block node]
  (let [block (node->block node)
        children (node->children node)]
    (apply node-of block
           (if (parent? block new-block)
             (conj children (node-of new-block))
             (map (partial insert new-block) children)))))

(defn add-to-chain [blockchain block]
  (insert block blockchain))

(defn- total-difficulty [node]
  (let [block (node->block node)
        children (node->children node)]
    (apply + (difficulty block) (map total-difficulty children))))

(defn- fork-choice-rule [nodes]
  (apply max-key total-difficulty nodes))

(defn- collect-best-chain [chain node]
  (let [block (node->block node)
        children (node->children node)
        chain (conj chain block)]
    (if children
      (collect-best-chain chain (fork-choice-rule children))
      chain)))

(defn best-chain
  "accepts the block tree and returns a seq of those blocks on the best chain according to the fork choice rule"
  [blockchain]
  (collect-best-chain [] blockchain))

(defn chain-from [{genesis-block :initial-state}]
  (node-of genesis-block))
