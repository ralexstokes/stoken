(ns demo-ledger
  (:require
   [dev]
   [io.stokes.node :as node]
   [io.stokes.rpc :as rpc]
   [io.stokes.p2p :as p2p]
   [io.stokes.block :as block]
   [io.stokes.transaction :as transaction]
   [io.stokes.miner :as miner]
   [io.stokes.state :as state]
   [io.stokes.queue :as queue]
   [io.stokes.hash :as hash]
   [io.stokes.key :as key]
   [io.stokes.transaction-pool :as transaction-pool]
   [com.stuartsierra.component.repl :refer [reset set-init start stop system]]))

(defn- transaction-for
  "makes a transaction that spends `value` from the `out-points` and then returns the remainder to the sender"
  [ledger from-keys to-keys value out-points]
  (let [previous-outputs (map (partial transaction/output-from-out-point ledger) out-points)
        proofs (map (partial transaction/generate-output-proof from-keys) previous-outputs)
        input (transaction/new-input previous-outputs proofs)
        inputs [input]
        outputs [(transaction/new-output value (key/->address to-keys))
                 (transaction/new-output (- (transaction/input->value input)
                                            value) (key/->address from-keys))]]
    (transaction/new inputs outputs)))

(comment
  (def from-keys      (dev/coinbase-key-for 0))
  from-keys
  (def to-keys        (dev/coinbase-key-for 1))
  (def value 10)

  (def ledger (-> system
                  (dev/from-node [:state])
                  first
                  state/->ledger))
  ledger

  (def t
    (let [transactions (:transactions dev/genesis-block)
          genesis-transaction (first transactions)
          out-points [(-> genesis-transaction
                          :outputs
                          first
                          (select-keys [:hash :index]))]
          transaction (transaction-for ledger from-keys to-keys value out-points)]
      transaction))

  ;; how to build a transaction
  ledger

  t

  ;; generate proof to spend an output
  (let [outputs (->> t
                     transaction/inputs
                     (mapcat transaction/input->previous-outputs))
        proofs (map (partial transaction/generate-output-proof from-keys) outputs)
        satisfies? (map transaction/valid-pay-to-pubkey-hash? (map merge outputs proofs))]
    proofs
    ;; satisfies?
    )

  ledger

  ;; how do transactions affect the ledger?
  (#'transaction/apply-transaction-to-ledger ledger t)

  (reset)
  )
