(ns io.stokes.key
  (:require [secp256k1.core :as ecc]
            [io.stokes.hash :as hash]
            [base58.core :as base58]))

(defn ->public [key]
  (->> key
       :public-key
       .getEncoded
       base58/encode))

(defn- sign-with-key [key msg]
  ;; TODO implement
  (str "SIGNED:" msg))

(defn sign
  [key msg]
  (let [hash (hash/of msg)]
    {:hash hash
     :signature (sign-with-key (:private-key key) hash)}))

(defn- derive-address
  "performs a computation similar to the Bitcoin address derivation algorithm given a key-pair"
  [keys]
  (->> keys
       :public-key
       .getEncoded
       hash/of-byte-array
       (take 20)
       base58/encode))

(defn new
  "returns a new key pair with derived address"
  []
  (let [keys (ecc/generate-address-pair)]
    (merge keys {:address (derive-address keys)})))

(defn ->address [keys]
  (:address keys))
