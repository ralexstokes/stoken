(ns io.stokes.key
  (:require [secp256k1.core :as ecc]))

(defn new
  "returns a new key pair"
  []
  (ecc/generate-address-pair))
