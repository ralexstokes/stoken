(ns io.stokes.block
  (:require [io.stokes.hash :as hash]
            [digest]
            [clj-time.coerce :as coerce]))

(def target-blocktime 10000) ;; milliseconds

(defn- timestamps->blocktimes [[a b]]
  (- a b))

(defn- average [seq]
  (let [n (count seq)
        sum (reduce + seq)]
    (/ sum n)))

(defn- calculate-average-blocktime [timestamps]
  "if there is not enough data in timestamps to generate an average we just return the target blocktime"
  (let [blocktimes (->> timestamps
                        reverse
                        (take 4)
                        (partition 2)
                        (map timestamps->blocktimes))]
    (if (= 0 (count blocktimes))
      target-blocktime
      (average blocktimes))))

(defn- calculate-difficulty [block timestamps]
  "adjust difficulty so that the average time between blocks is N seconds"
  (let [difficulty (block :difficulty)
        average-blocktime (calculate-average-blocktime timestamps)
        next        (cond (> average-blocktime target-blocktime) dec
                          (< average-blocktime target-blocktime) inc
                          :else                                  identity) ]
    (next difficulty)))

(defn- header [transactions previous-block block-timestamps]
  {:previous-hash    (previous-block :hash)
   :height           (inc (previous-block :height))
   :difficulty       (calculate-difficulty previous-block block-timestamps)
   :transaction-root (-> transactions
                         hash/tree-of
                         hash/root-of)
   :nonce            0})

(defn from [transactions previous-block block-timestamps]
  (let [block (merge {:transactions transactions}
                     (header transactions previous-block block-timestamps))]
    (assoc block :hash (hash/of block))))

(defn chain-from [{:keys [initial-state]}]
  [initial-state])

(defn add-to-chain [blockchain block]
  ;; TODO handle reorgs
  (let [height (block :height)]
    (assoc blockchain height block)))

(defn serialize [block]
  "work around limitations of clj-time object"
  (assoc block :time (coerce/to-date (:time block))))

(defn deserialize [block]
  "work around limitations of clj-time object"
  (assoc block :time (coerce/from-date (:time block))))

(defn best-chain [blockchain]
  ;; TODO find workiest chain
  blockchain)
