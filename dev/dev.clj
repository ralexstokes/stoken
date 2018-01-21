(ns dev
  "Tools for interactive development with the REPL. This file should
  not be included in a production build of the application.

  Call `(reset)` to reload modified code and (re)start the system.

  The system under development is `system`, referred from
  `com.stuartsierra.component.repl/system`.

  See also https://github.com/stuartsierra/component.repl"
  (:require
   [clojure.java.io :as io]
   [clojure.java.javadoc :refer [javadoc]]
   [clojure.pprint :refer [pprint]]
   [clojure.reflect :refer [reflect]]
   [clojure.repl :refer [apropos dir doc find-doc pst source]]
   [clojure.set :as set]
   [clojure.string :as string]
   [clojure.test :as test]
   [clojure.edn :as edn]
   [clojure.tools.namespace.repl :refer [refresh refresh-all clear]]
   [com.stuartsierra.component :as component]
   [com.stuartsierra.component.repl :refer [reset set-init start stop system]]
   [crypto.random :as rand]
   [io.stokes.node :as node]
   [io.stokes.rpc :as rpc]
   [io.stokes.p2p :as p2p]
   [io.stokes.block :as block]
   [io.stokes.transaction :as transaction]
   [io.stokes.ledger :as ledger]
   [io.stokes.miner :as miner]
   [io.stokes.state :as state]
   [io.stokes.queue :as queue]
   [io.stokes.hash :as hash]
   [io.stokes.key :as key]
   [io.stokes.transaction-pool :as transaction-pool]))

(def pp pprint)

;; Do not try to load source code from 'resources' directory
(clojure.tools.namespace.repl/set-refresh-dirs "dev" "src" "test")

(defn- hex->bignum [str]
  (BigInteger. str 16))

(def max-threshold-str-hard
  "00000000FFFF0000000000000000000000000000000000000000000000000000")

(def max-threshold-str-easy
  "0FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF")

(defn- max-threshold
  "maximum threshold used in Bitcoin; https://en.bitcoin.it/wiki/Target"
  [str]
  (hex->bignum str))

(defn mine-until-sealed [chain transaction-pool]
  (let [miner {:coinbase "0xdeadbeefcafe"
               :max-threshold (max-threshold max-threshold-str-easy)
               :max-seed 1000000}]
    (loop [block (miner/mine miner chain transaction-pool)]
      (if block
        block
        (recur (miner/mine miner chain transaction-pool))))))

(def genesis-block
  (mine-until-sealed [] (transaction-pool/new {})))

(def genesis-string (pr-str (block/readable genesis-block)))

(defn mock-transaction []
  (transaction/from (rand/hex 8) (rand/hex 8) 50 (-> (rand)
                                                     (* 100)
                                                     int)))

(def mock-transactions (take 100 (repeatedly mock-transaction)))

(defn- ledger-state [transactions]
  (reduce (fn [ledger {:keys [from to]}]
            (-> ledger
                (assoc to   10000)
                (assoc from 10000))) {} transactions))

(def mock-address "0xdeadbeefcafe")

(defn- config [transactions mine? easy-mining?]
  {:rpc              {:port 3000
                      :shutdown-timeout-ms 1000}
   :p2p              {:port 8888}
   :scheduler        {:number-of-workers 1
                      :node-should-mine? mine?}
   :miner            {:number-of-rounds 1000
                      :coinbase mock-address
                      :max-threshold (max-threshold (if easy-mining?
                                                      max-threshold-str-easy
                                                      max-threshold-str-hard))
                      :max-seed 1000000}
   :blockchain       {:initial-state genesis-block}
   :transaction-pool {:initial-state transactions}
   :ledger           {:initial-state (ledger-state transactions)}})

;; convenience flags for development
(def transactions [])
(def mine? false)
(def easy-mining? true)

(def dev-config (config transactions mine? easy-mining?))

(defn dev-system
  "Constructs a system map suitable for interactive development."
  []
  (node/from dev-config))

(set-init (fn [_] (dev-system)))

;; some convenience functions for the REPL

(defn chain [system] (state/->best-chain (:state system)))

(defn read-n-messages [p2p n]
  (future (loop [count 0]
            (while (< count n)
              (println "got a mesg:"
                       (p2p/receive p2p))))))

;; (defmacro expose-system [system]
;;   (for [component (keys system)]
;;     `(def ~(symbol (name component))
;;        (~component system))))
