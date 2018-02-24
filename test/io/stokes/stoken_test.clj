(ns io.stokes.stoken-test
  (:require
   [clojure.test :refer [deftest is]]
   [io.stokes.block :as block]))

(def children-contains-block? #'block/children-contains-block?)

(deftest can-identity-duplicate-blocks-in-tree
  (let [cs '({:block {:hash 1, :previous-hash 0}}
             {:children ({:block {:hash 2, :previous-hash 1}})})]
    (is (children-contains-block? cs {:hash 1}))))

(deftest avoid-inserting-duplicates
  (let [genesis {:hash 0}
        blocks (map (fn [id] {:hash id
                              :previous-hash (dec id)}) (range 1 3))
        chain (block/chain-from {:initial-state genesis})
        tree (reduce block/add-to-chain chain blocks)]
    (is (= tree
           (block/add-to-chain tree {:hash 1
                                     :previous-hash 0})))))
