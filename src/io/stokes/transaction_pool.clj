(ns io.stokes.transaction-pool
  (:require [clojure.set :as set]))

(defn new [{:keys [initial-state]}]
  (into #{} initial-state))

(defn add [pool transaction]
  (conj pool transaction))

(defn remove-transactions [pool transactions]
  (->> transactions
       (into #{})
       (set/difference pool)))

(defn take-by-fee [pool n]
  "returns a set of transactions with the highest fee"
  (->> pool
       (sort-by :fee >)
       (take n)
       (into #{})))
