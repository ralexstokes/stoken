(ns io.stokes.node
  (:require
   [com.stuartsierra.component :as component]
   [io.stokes.state :as state]
   [io.stokes.queue :as queue]
   [io.stokes.rpc :as rpc]
   [io.stokes.p2p :as p2p]
   [io.stokes.miner :as miner]
   [io.stokes.scheduler :as scheduler]))

(defn from
  "constructs an instance of the system ready to run given the config"
  [{:keys [rpc p2p miner scheduler] :as config}]
  (component/system-map
   :config config
   :state (state/new config)
   :queue (queue/new)
   :rpc (rpc/new rpc)
   :p2p (p2p/new p2p)
   :miner (miner/new miner)
   :scheduler (scheduler/new scheduler)))
