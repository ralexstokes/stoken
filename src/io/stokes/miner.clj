(ns io.stokes.miner
  (:require [com.stuartsierra.component :as component]
            [io.stokes.hash :as hash]
            [io.stokes.block :as block]
            [io.stokes.transaction :as transaction]
            [io.stokes.transaction-pool :as transaction-pool]
            [io.stokes.address :as address]
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
          block
          (recur (dec count)
                 (inc nonce)))))))

(defn- select-transactions [pool]
  (transaction-pool/take-by-fee pool 20))

(defn- build-coinbase-transaction [address subsidy]
  (transaction/from address/zero address subsidy 0))

(defn- derive-next-block [chain transactions]
  (block/next-template chain transactions))

(def ^:private halving-frequency
  "how many blocks occur since the last time the block reward halved"
  5000)

(def ^:private base-block-reward
  "the largest block reward that will ever be claimed"
  128)

(defn- calculate-subsidy [chain]
  (let [height (count chain)
        halvings (quot height halving-frequency)]
    (int (quot base-block-reward
               (Math/pow 2 halvings)))))

(defn mine [{:keys [number-of-rounds coinbase max-threshold max-seed] :or {number-of-rounds 250}} chain transaction-pool]
  (let [seed (rand-int max-seed)
        subsidy (calculate-subsidy chain)
        transactions (select-transactions transaction-pool)
        coinbase-transaction (build-coinbase-transaction coinbase subsidy)
        next-block (derive-next-block chain (concat [coinbase-transaction]
                                                    transactions))]
    (mine-range next-block seed number-of-rounds max-threshold)))

(defn new [config]
  (merge config {:channel (atom nil)}))
