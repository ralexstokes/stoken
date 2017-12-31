(ns io.stokes.miner
  (:require [com.stuartsierra.component :as component]
            [io.stokes.hash :as hash]
            [io.stokes.block :as block]
            [io.stokes.transaction :as transaction]
            [io.stokes.transaction-pool :as transaction-pool]
            [io.stokes.internal.async :as async]
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
    (<= result 0)))

(defn- increment-nonce [block]
  ;; TODO fill in ALL fields of block, w/ nonce at 0
  ;; TODO only take header to hash over
  (let [next (update block :nonce inc)]
    (assoc next :hash (hash/of next))))

(defn mine-range [number-of-nonces block]
  (loop [count number-of-nonces
         block block]
    (when (pos? count)
      (if (sealed? block)
        (assoc block :time (time/now))
        (recur (dec count)
               (increment-nonce block))))))

(defn- build-next-block [blockchain transaction-pool]
  (let [timestamps (map :time blockchain)
        transactions (transaction-pool/take-by-fee transaction-pool 5)]
    (block/from transactions (peek blockchain) timestamps)))

(defn- get-seed []
  ;; TODO pick a better ceiling?
  (rand-int 1000000000))

(defn mine-for-nonces [{:keys [number-of-nonces blockchain transaction-pool]}]
  (let [seed (get-seed)
        next-block (build-next-block blockchain transaction-pool)]
    (mine-range number-of-nonces (assoc next-block :nonce seed))))

(defn start-mining []
  (Thread/sleep 500)
  (when (zero? (mod (rand-int 100) 3))
    {:tag :new-block :nonce (rand-int 1000)}))

(defrecord ProofOfWork [state queue]
  component/Lifecycle
  (start [miner]
    (println "starting miner...")
    (assoc miner :stop (async/deliver-until-stopped
                        queue
                        start-mining)))
  (stop [miner]
    (println "stopping miner...")
    (when-let [stop (:stop miner)]
      (stop))
    (dissoc miner :stop)))

(defn new [config]
  (component/using
   (map->ProofOfWork (assoc config :queue (async/chan)))
   [:state]))
