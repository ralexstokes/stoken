(ns io.stokes.transaction-pool
  (:require [clojure.set :as set]
            [io.stokes.transaction :as transaction]))

(defn new [{:keys [initial-state]}]
  (into #{} initial-state))

(defn add [pool transaction]
  (conj pool transaction))

(defn remove-transactions [pool transactions]
  (->> transactions
       (into #{})
       (set/difference pool)))

(defn take-by-fee
  "returns `n` transactions from the `pool` preferring those with higher fees"
  [pool n]
  (->> pool
       (sort-by transaction/fee >)
       (take n)))
