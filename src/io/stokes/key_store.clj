(ns io.stokes.key-store
  (:require [io.stokes.key :as key]
            [me.raynes.fs :as fs]
            [lock-key.core :as lock]
            [clojure.edn :as edn])
  (:refer-clojure :exclude [load]))

(defn load
  "loads keys from disk at `filename`"
  [filename password]
  (let [filename (fs/expand-home filename)
        keys (-> (slurp filename)
                 (lock/decrypt-from-base64 password)
                 edn/read-string)]
    (map key/from-readable keys)))

(defn store
  "writes the `keys`, a vector of key pairs as produced by `key/new-pair`, to disk at `filename`"
  [keys filename password]
  (let [filename (fs/expand-home filename)
        parent (fs/parent filename)
        keys (map key/readable keys)
        content (pr-str keys)]
    (fs/mkdirs parent)
    (spit filename (lock/encrypt-as-base64 content password) :append true)))
