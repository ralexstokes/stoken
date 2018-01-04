(ns io.stokes.miner
  (:require [com.stuartsierra.component :as component]
            [io.stokes.hash :as hash]
            [io.stokes.block :as block]
            [io.stokes.transaction :as transaction]
            [io.stokes.transaction-pool :as transaction-pool]
            [clojure.core.async :as async]
            [clj-time.core :as time]
            [clj-time.coerce :as coerce]))

(defn valid-block? [block]
  (and (block :hash)
       block))

(defn- hex->bignum [str]
  (BigInteger. str 16))

;; (def max-threshold-str
;;   "00000000FFFF0000000000000000000000000000000000000000000000000000")

(def max-threshold-str
  "0FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF")

(def max-threshold
  "maximum threshold used in Bitcoin; https://en.bitcoin.it/wiki/Target"
  (hex->bignum max-threshold-str))

(defn- calculate-threshold [difficulty]
  (.shiftRight max-threshold difficulty))

(defn- sealed? [{:keys [hash difficulty]}]
  "a proof-of-work block is sealed when the block hash is less than a threshold determined by the difficulty"
  (let [threshold (calculate-threshold difficulty)
        hash (hex->bignum hash)
        result (.compareTo hash threshold)]
    (not (pos? result))))

(defn- prepare-block [block nonce]
  (let [block (assoc block :nonce nonce)]
    (assoc block :hash (block/hash block))))

(defn- mine-range [block seed number-of-rounds]
  (loop [count number-of-rounds
         nonce seed]
    (when (pos? count)
      (let [block (prepare-block block nonce)]
        (if (sealed? block)
          block
          (recur (dec count)
                 (inc nonce)))))))

(defn- select-transactions [pool]
  (transaction-pool/take-by-fee pool 20))

(defn- derive-next-block [chain transaction-pool]
  (let [transactions (select-transactions transaction-pool)]
    (block/next-template chain transactions)))

(defn mine [chain transaction-pool & {:keys [number-of-rounds] :or {number-of-rounds 250}}]
  (let [seed (rand-int 10000000) ;; TODO pick a better ceiling?
        next-block (derive-next-block chain transaction-pool)]
    (mine-range next-block seed number-of-rounds)))

(defn mine-until-sealed [chain transaction-pool]
  (loop [block (mine chain transaction-pool)]
    (if block
      block
      (recur (mine chain transaction-pool)))))

(defn new [config]
  ;; TODO add coinbase address to config
  ;; TODO number-of-rounds config
  (atom nil))
