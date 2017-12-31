(ns io.stokes.rpc
  (:require [com.stuartsierra.component :as component]
            [org.httpkit.server :as http]
            [io.stokes.ledger :as ledger]
            [io.stokes.block :as block]
            [io.stokes.transaction :as transaction]
            [io.stokes.transaction-pool :as transaction-pool]
            [io.stokes.state :as state ]
            [crypto.random :as rand]
            [clojure.core.async :as async]
            [clojure.data.json :as json]
            [compojure.core :as compojure]))

(defn- ->transaction [req]
  (let [{:strs [from to amount]} (-> req
                                     slurp
                                     json/read-str)]
    (transaction/from from to amount (-> (rand)
                                         (* 100)
                                         int))))

(defn- make-handler [state queue]
  (compojure/routes
   (compojure/GET "/balances" [req]
                  (let [balances (state/->balances state)]
                    {:body (prn-str balances)}))
   (compojure/GET "/blockchain" [req]
                  (let [chain (state/->best-chain state)]
                    {:body (prn-str (map block/serialize chain))}))
   (compojure/GET "/transactions" [req]
                  (let [transaction-pool (state/->transactions state)]
                    {:body (prn-str transaction-pool)}))
   (compojure/POST "/transaction" {:keys [body]}
                   (let [transaction (->transaction body)]
                     (async/go (async/>! queue {:tag :new-transaction
                                                :transaction transaction}))
                     {:status 200}))))

(defrecord Server [port shutdown shutdown-timeout-ms state queue]
  component/Lifecycle
  (start [server]
    (println "starting rpc server...")
    (assoc server :shutdown (http/run-server (make-handler state queue) {:port (:port server)})))
  (stop [server]
    (println "stopping rpc server...")
    (let [{:keys [shutdown shutdown-timeout-ms]} server]
      (shutdown :timeout shutdown-timeout-ms))
    server))

(defn new [config]
  (component/using (map->Server (assoc config :queue (async/chan)))
                   [:state]))
