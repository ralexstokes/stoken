(ns io.stokes.rpc
  (:require [com.stuartsierra.component :as component]
            [org.httpkit.server :as http]
            [compojure.core :as compojure]
            [crypto.random :as rand]
            [clojure.data.json :as json]
            [io.stokes.block :as block]
            [io.stokes.transaction :as transaction]
            [io.stokes.state :as state]
            [io.stokes.queue :as queue]))

(defn- ->transaction [req]
  (let [{:strs [from to amount fee]} (-> req
                                         slurp
                                         json/read-str)]
    (transaction/from from to amount fee)))

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
                   (->> body
                        ->transaction
                        (queue/submit-transaction queue))
                   {:status 200})))

(defn- start [port state queue]
  (http/run-server (make-handler state queue) {:port port}))

(defrecord Server [port shutdown shutdown-timeout-ms state queue]
  component/Lifecycle
  (start [server]
    (println "starting rpc server...")
    (assoc server :shutdown (start port state queue)))
  (stop [server]
    (println "stopping rpc server...")
    (let [{:keys [shutdown shutdown-timeout-ms]} server]
      (shutdown :timeout shutdown-timeout-ms))
    server))

(defn new [config]
  (component/using (map->Server config)
                   [:state :queue]))
