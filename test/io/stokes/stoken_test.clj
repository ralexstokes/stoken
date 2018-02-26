(ns io.stokes.stoken-test
  (:require
   [clojure.test :refer [deftest is]]
   [io.stokes.block :as block]))

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


(comment
  (def b '{:hash "2c8c13ae3fd7f371d8a94ee3470a08d488d5ebc69cd6b3361a1ff560d6cfcef", :difficulty 2, :time #inst "2018-02-24T20:43:02.369-00:00", :port 40404, :host "10.0.30.229", :transaction-root "ecb1ac2585f2184f5b8f409925f8bc17c81ca9888060b92b9b6485d7b481345", :transactions ({:ins [{:type :coinbase-input, :block-height 1}], :outs [{:type :output, :value 128, :script {:type :address, :address "kpFJBvihsMaPtKTkBavk66Ksf1H"}}]}), :previous-hash "7216994e1ae0258fa96d3d089aa05dd097c66084654fa68a481fdb024bfb0d3", :nonce 72}))
