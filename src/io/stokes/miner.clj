(ns io.stokes.miner
  (:require [com.stuartsierra.component :as component]
            [io.stokes.hash :as hash]
            [io.stokes.block :as block]
            [io.stokes.transaction :as transaction]
            [io.stokes.transaction-pool :as transaction-pool]
            [io.stokes.key :as key]
            [clojure.core.async :as async]
            [clj-time.core :as time]
            [clj-time.coerce :as coerce]))

(defn valid-block? [block]
  (and (block :hash)
       block))

(defn- calculate-threshold [max-threshold difficulty]
  (.shiftRight max-threshold difficulty))

(defn- hex->bignum [str]
  (BigInteger. str 16))

(defn- sealed?
  "a proof-of-work block is sealed when the block hash is less than a threshold determined by the difficulty"
  [block max-threshold]
  (let [threshold (calculate-threshold max-threshold (block/difficulty block))
        hash (-> block
                 block/hash
                 hex->bignum)]
    (< hash threshold)))

(defn- prepare-block [block nonce]
  (block/with-nonce block nonce))

(defn- mine-range [block seed number-of-rounds max-threshold]
  (loop [count number-of-rounds
         nonce seed]
    (when (pos? count)
      (let [block (prepare-block block nonce)]
        (if (sealed? block max-threshold)
          (assoc block :hash (block/hash block))
          (recur (dec count)
                 (inc nonce)))))))

(defn- select-transactions [pool]
  (transaction-pool/take-by-fee pool 20))

(defn- ->coinbase-transaction [address subsidy block-height]
  (transaction/for-coinbase address subsidy block-height))

(def ^:private default-number-of-rounds
  "how many nonces to search for a solution"
  250)

(def ^:private default-halving-frequency
  "how many blocks occur since the last time the block reward halved"
  5000)

(def ^:private default-base-block-reward
  "the largest block reward that will ever be claimed"
  128)

(defn- calculate-subsidy [chain halving-frequency base-block-reward]
  (let [height (count chain)
        halvings (quot height halving-frequency)]
    (int (quot base-block-reward
               (Math/pow 2 halvings)))))

(defn- derive-next-block [chain coinbase transaction-pool halving-frequency base-block-reward]
  (let [transactions (select-transactions transaction-pool)
        subsidy (calculate-subsidy chain halving-frequency base-block-reward)
        coinbase-transaction (->coinbase-transaction coinbase subsidy (count chain))]
    (block/next-template chain (conj transactions
                                     coinbase-transaction))))

(defn run-miner [{:keys [number-of-rounds
                         halving-frequency
                         base-block-reward
                         coinbase
                         max-threshold
                         max-seed]
                  :or {number-of-rounds default-number-of-rounds
                       halving-frequency default-halving-frequency
                       base-block-reward default-base-block-reward}}
                 chain transaction-pool]
  (let [seed (rand-int max-seed)
        next-block (derive-next-block chain coinbase transaction-pool halving-frequency base-block-reward)]
    (mine-range next-block seed number-of-rounds max-threshold)))

(defn mine [{:keys [total-blocks] :as config} chain transaction-pool]
  (if total-blocks
    (when (pos? (- total-blocks (count chain)))
      (run-miner config chain transaction-pool))
    (run-miner config chain transaction-pool)))

(defn new [config]
  (merge config {:channel (atom nil)}))
