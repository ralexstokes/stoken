(defproject io.stokes/stoken "0.1.0-SNAPSHOT"
  :description "a simple proof-of-work blockchain"
  :url "github.com/ralexstokes/stoken"
  :license {:name "TODO: Choose a license"
            :url "http://choosealicense.com/"}
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [org.clojure/core.async "0.3.465"]
                 [crypto-random "1.2.0"]
                 [digest "1.4.6"]
                 [clj-time "0.14.0"]
                 [http-kit "2.2.0"]
                 [compojure "1.6.0"]
                 [org.clojure/data.json "0.2.6"]
                 [com.stuartsierra/component "0.3.2"]]
  :profiles {:dev {:dependencies [[org.clojure/tools.namespace "0.2.11"]
                                  [com.stuartsierra/component.repl "0.2.0"]]
                   :source-paths ["dev"]}})
