(ns io.stokes.stoken-test
  (:require
   [clojure.test :refer [deftest is]]
   [io.stokes.block :as block]
   [io.stokes.key :as key]
   [io.stokes.hash :as hash]))

(def children-contains-block? #'block/children-contains-block?)
(def blockchain-contains-block? #'block/chain-contains-block?)

;; utilities for simple blocks

(defn- tree->hashes [tree]
  (->> tree
       block/tree->blocks
       (map block/hash)))

(defn- block-of [i]
  {:previous-hash i
   :hash (inc i)})

(def genesis {:hash 0})
(def chain (block/chain-from {:initial-state genesis}))

(defn- inject-block [[chain orphans] next]
  (block/add-to-chain chain (conj orphans (block-of next))))

(defn- inject-blocks
  "chain is a blocktree
   blocks is a seq of numbers"
  [chain blocks]
  (reduce inject-block [chain #{}] (into #{} blocks)))

;;

(deftest can-identity-duplicate-blocks-in-tree
  (let [cs '({:block {:hash 1, :previous-hash 0}}
             {:children ({:block {:hash 2, :previous-hash 1}})})]
    (is (children-contains-block? cs {:hash 1}))))

(deftest blockchain-contains-block
  (let [number-blocks 10
        max-hash (inc number-blocks)
        blocks (range number-blocks)
        [tree _] (inject-blocks chain blocks)]
    (is (every? true?
                (map #(blockchain-contains-block? tree (block-of %)) (random-sample 0.5 (range number-blocks)))))))

(deftest avoid-inserting-duplicates
  (let [blocks (range 3)
        [tree orphans] (inject-blocks chain blocks)
        [next-tree next-orphans] (block/add-to-chain tree (conj orphans {:hash 1
                                                                         :previous-hash 0}))]
    (is (and (= tree next-tree)
             (empty? next-orphans)
             (=  orphans
                 next-orphans)))))

(deftest can-insert-blocks-and-get-back-their-hashes
  (let [number-blocks 10
        max-hash (inc number-blocks)
        blocks (range number-blocks)
        [tree _] (inject-blocks chain blocks)]
    (is (= (tree->hashes tree)
           (range max-hash))))) ;; 11 is to account for hash running to (inc i)

(deftest can-recover-orphans
  (let [number-blocks 10
        max-hash (inc number-blocks)
        blocks (range number-blocks)
        blocks-to-inject (random-sample 0.5 blocks)
        missing-blocks (filter (complement (into #{} blocks-to-inject)) blocks)
        [next-chain orphans] (inject-blocks chain blocks-to-inject)
        [full-chain _] (block/add-to-chain next-chain (apply conj orphans (map block-of missing-blocks)))]
    (is (= (tree->hashes full-chain)
           (range max-hash)))))

(deftest can-match-address-to-public-key
  (let [keys (io.stokes.key/new-pair)
        pub-key (key/->public keys)
        addr (key/->address keys)]
    (is (and (key/yields-address? pub-key addr)
             (not (key/yields-address? pub-key (take 5 addr)))))))

(deftest can-verify-signatures
  (let [keys (io.stokes.key/new-pair)
        msg "hi-there-world"
        {:keys [hash signature] :as sig} (io.stokes.key/sign keys msg)]
    (is (and (key/verify keys sig)
             (key/verify (key/->public keys)
                         hash signature)
             (not (key/verify (key/->public keys)
                              (hash/of hash) signature))))))
