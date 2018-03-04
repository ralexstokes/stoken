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

  (def blockchain (state/->best-chain state))

  (def blocktree (state/->blockchain state))


  ;; the blockchain!
  blockchain

  ;; the full blocktree
  blocktree

  ;; e.g. find blocks not on the main chain
  (let [all-blocks (block/tree->blocks blocktree)]
    (remove (into #{} blockchain) all-blocks))

  ;; go look at blocktree structure and fork choice rule

  (reset)
  )
