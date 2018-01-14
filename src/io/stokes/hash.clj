(ns io.stokes.hash
  (:require [digest]))

(defn of-seq [data]
  "of-seq expects data to have some canonical ordering:"
  (-> data
      pr-str
      digest/sha-256
      digest/sha-256))

(defn- associative->sequential [data]
  "e.g. turn a map into a sequence sorted by key's value"
  (let [keys (sort (keys data))]
    (reduce #(conj %1 [%2 (data %2)]) [] keys)))

(defn of [data]
  (if (associative? data)
    (of-seq (associative->sequential data))
    (of-seq data)))

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

(defn tree-of [data]
  "builds a binary Merkle tree out of the seq `data`"
  (when (seq data)
    (let [leaves (->> data
                      (make-even)
                      (map make-leaf))]
      (build-tree leaves))))

(defn root-of [hash-tree]
  "returns the root hash of a Merkle tree as produced by `tree-of`"
  (get hash-tree :hash (of "")))
