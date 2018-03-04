(ns io.stokes.key
  (:require [secp256k1.core :as ecc]
            [io.stokes.hash :as hash]
            [base58.core :as base58]))

(defn ->public [keys]
  (ecc/x962-encode (:public-key keys)))
(defn ->private [keys]
  (:private-key keys))
(defn ->address [keys]
  (:address keys))

(def sign-hash-with-keys ecc/sign-hash)

(defn sign
  [keys msg]
  (let [hash (hash/of msg)]
    {:hash hash
     :signature (sign-hash-with-keys (->private keys) hash)}))

(defn verify
  ([keys {:keys [hash signature]}]
   (verify (->public keys) hash signature))
  ([public-key hash signature]
   (ecc/verify-signature-from-hash public-key hash signature)))

(defn- derive-address-from-encoded-public-key [enc-pub-key]
  (->> enc-pub-key
       .getBytes
       hash/of-byte-array
       (take 20)
       base58/encode))

(defn- derive-address
  "performs a computation similar to the Bitcoin address derivation algorithm given a key-pair"
  [keys]
  (->> keys
       ->public
       derive-address-from-encoded-public-key))

(defn new-pair
  "returns a new key pair with derived address"
  []
  (let [keys (ecc/generate-address-pair)]
    (merge keys {:address (derive-address keys)})))

(defn yields-address? [encoded-public-key address]
  (= address
     (derive-address-from-encoded-public-key encoded-public-key)))

(defn validates-signature? [public-key signature hash]
  (verify public-key hash signature))
