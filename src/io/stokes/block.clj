(ns io.stokes.block
  (:require [io.stokes.hash :as hash]
            [digest]
            [clj-time.coerce :as coerce]
            [clj-time.core :as time])
  (:refer-clojure :exclude [hash]))

(defn difficulty [block]
  (get block :difficulty 1))

(defn height [block]
  (get block :height 0))

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
  (let [difficulty (difficulty block)
        average-blocktime (calculate-average-blocktime timestamps)
        next        (cond (> average-blocktime target-blocktime) dec
                          (< average-blocktime target-blocktime) inc
                          :else                                  identity) ]
    (next difficulty)))

(defn- header-from [chain transactions]
  (let [previous-block (last chain)
        block-timestamps (map :time chain)]
    {:previous-hash    (:hash previous-block)
     :height           (inc (height previous-block))
     :difficulty       (calculate-difficulty previous-block block-timestamps)
     :transaction-root (-> transactions
                           hash/tree-of
                           hash/root-of)
     :time             (time/now)
     :nonce            0}))

(defn header [block]
  (select-keys block [:previous-hash
                      :difficulty
                      :transaction-root
                      :time
                      :nonce]))

(defn hash [block]
  (-> block
      header
      hash/of))

(defn next-template [chain transactions]
  "generates a block with `transactions` that satisfies the constraints to be appended to the chain modulo a valid proof-of-work, i.e. a nonce that satisfies the difficulty in the block, also implies a missing block hash in the returned data. note: timestamp in the block header is currently included in the hash pre-image; given that a valid block must be within some time interval, client code MUST refresh this timestamp as needed; if you are having issues, run the proof-of-work routine for a smaller number of rounds"
  (merge {:transactions transactions}
         (header-from chain transactions)))

(defn chain-from [{:keys [initial-state]}]
  [initial-state])

(defn add-to-chain [blockchain block]
  ;; TODO handle reorgs, etc.
  (conj blockchain block))

(defn serialize [block]
  "work around limitations of clj-time object"
  (assoc block :time (coerce/to-date (:time block))))

(defn deserialize [block]
  "work around limitations of clj-time object"
  (assoc block :time (coerce/from-date (:time block))))

(defn best-chain [blockchain]
  ;; TODO find workiest chain
  blockchain)
