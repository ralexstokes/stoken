(ns demo-keys
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
  ;; make a new key pair
  (key/new-pair)

  ;; inspect public key, private key
  ;; and the address for some key pair
  (let [some-keys (key/new-pair)]
    [(key/->public some-keys)
     (key/->private some-keys)
     (key/->address some-keys)])

  ;; let's look at code to derive address -- just some hashing

  (reset)
  )
