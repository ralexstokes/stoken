(ns demo-blockchain
  (:require
   [dev]
   [io.stokes.node :as node]
   [io.stokes.rpc :as rpc]
   [io.stokes.p2p :as p2p]
   [io.stokes.block :as block]
   [io.stokes.transaction :as transaction]
   [io.stokes.miner :as miner]
   [io.stokes.state :as state]
   [io.stokes.queue :as queue]
   [io.stokes.hash :as hash]
   [io.stokes.key :as key]
   [io.stokes.transaction-pool :as transaction-pool]
   [com.stuartsierra.component.repl :refer [reset set-init start stop system]]))


(comment

  (def state (-> system
                 (dev/from-node [:state])
                 first))
  state

  (def blockchain (state/->best-chain state))

  (def genesis-block (first blockchain))
  (def some-block (rand-nth blockchain))

  ;; the genesis block, where we start
  genesis-block

  (:transactions genesis-block)

  ;; some block in the chain
  some-block

  (:transactions some-block)

  ;; the blockchain!
  blockchain

  ;; let's check the hash chain...
  (map (juxt :previous-hash :hash) blockchain)

  (reset)
  )

