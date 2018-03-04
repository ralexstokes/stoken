(ns io.stokes.miner
  (:require [io.stokes.hash :as hash]
            [io.stokes.block :as block]
            [io.stokes.transaction :as transaction]
            [io.stokes.transaction-pool :as transaction-pool]
            [io.stokes.key :as key]
            [clojure.core.async :as async]
            [clj-time.core :as time]
            [clj-time.coerce :as coerce]))

(defn- prepare-block [block nonce]
  (block/with-nonce block nonce))

(defn- mine-range [block seed number-of-rounds max-threshold]
  (loop [count number-of-rounds
         nonce seed]
    (when (pos? count)
      (let [block (prepare-block block nonce)]
        (if (block/sealed? block max-threshold)
          (assoc block :hash (block/hash block))
          (recur (dec count)
                 (inc nonce)))))))

(defn- select-transactions [pool]
  (transaction-pool/take-by-fee pool 20))

(defn- ->coinbase-transaction [address subsidy block-height block-fees]
  (transaction/for-coinbase address (+ subsidy block-fees) block-height))

(def ^:private default-number-of-rounds
  "how many nonces to search for a solution"
  250)

(defn- derive-next-block [chain coinbase transaction-pool halving-frequency base-block-reward]
  (let [transactions (select-transactions transaction-pool)
        subsidy (block/calculate-subsidy (count chain) halving-frequency base-block-reward)
        block-fees (reduce + (map transaction/fee transactions))
        coinbase-transaction (->coinbase-transaction coinbase subsidy (count chain) block-fees)]
    (block/next-template chain (conj transactions
                                     coinbase-transaction))))

(defn mine [{:keys [number-of-rounds
                    halving-frequency
                    base-block-reward
                    coinbase
                    max-threshold
                    max-seed]
             :or {number-of-rounds default-number-of-rounds
                  halving-frequency block/default-halving-frequency
                  base-block-reward block/default-base-block-reward}}
            chain transaction-pool]
  (let [seed (rand-int max-seed)
        next-block (derive-next-block chain coinbase transaction-pool halving-frequency base-block-reward)]
    (mine-range next-block seed number-of-rounds max-threshold)))

(defn new [config]
  (merge config {:channel (atom nil)}))
