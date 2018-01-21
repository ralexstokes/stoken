(ns io.stokes.key
  (:require [secp256k1.core :as ecc]
            [io.stokes.hash :as hash]
            [base58.core :as base58]))

(defn- derive-address
  "performs a computation similar to the Bitcoin address derivation algorithm given a key-pair"
  [keys]
  (-> keys
      :public-key
      .getEncoded
      hash/of-byte-array
      base58/encode))

(defn new
  "returns a new key pair with derived address"
  []
  (let [keys (ecc/generate-address-pair)]
    (merge keys {:address (derive-address keys)})))

(defn ->address [keys]
  (:address keys))
