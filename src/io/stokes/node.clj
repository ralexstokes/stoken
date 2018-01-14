(ns io.stokes.node
  (:require
   [io.stokes.rpc :as rpc]
   [io.stokes.p2p :as p2p]
   [io.stokes.miner :as miner]
   [io.stokes.scheduler :as scheduler]
   [io.stokes.state :as state]
   [io.stokes.queue :as queue]
   [com.stuartsierra.component :as component]
   [clojure.core.async :as async]))

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
