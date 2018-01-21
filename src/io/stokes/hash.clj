(ns io.stokes.hash
  (:require [secp256k1.hashes :as digest]))

(defn of-seq
  "of-seq expects data to have some canonical ordering:"
  [data]
  (some-> data
          pr-str
          digest/sha256
          digest/sha256
          java.math.BigInteger.
          (.toString 16)))

(defn- associative->sequential
  "e.g. turn a map into a sequence sorted by key's value"
  [data]
  (let [keys (sort (keys data))]
    (reduce #(conj %1 [%2 (data %2)]) [] keys)))

(defn of [data]
  (of-seq
   (seq
    (if (associative? data)
      (associative->sequential data)
      data))))

(defn- make-node [[left right]]
  {:hash (of (map :hash [left right]))
   :left left
   :right right})

(defn- build-tree [leaves]
  (if (= (count leaves) 1)
    (first leaves)
    (->> leaves
         (partition 2)
         (map make-node)
         build-tree)))

(defn- make-leaf [data]
  {:hash  (of data)
   :left  nil
   :right nil})

(defn- make-even [seq]
  (if (= 0 (mod (count seq) 2))
    seq
    (conj seq (last seq))))

(defn tree-of
  "builds a binary Merkle tree out of the seq `data`"
  [data]
  (when (seq data)
    (let [leaves (->> data
                      (make-even)
                      (map make-leaf))]
      (build-tree leaves))))

(defn root-of
  "returns the root hash of a Merkle tree as produced by `tree-of`"
  [hash-tree]
  (get hash-tree :hash (of "")))
