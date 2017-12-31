(ns io.stokes.ledger)

(defn- apply-transaction [ledger {:keys [from to amount]}]
  (-> ledger
      (update from #(- % amount))
      (update to #(+ % amount))))

(defn apply-transactions [ledger transactions]
  (reduce apply-transaction ledger transactions))

(defn from [{:keys [initial-state]}]
  ;; TODO add UTXO set here
  (atom (into {} initial-state)))

(defn balances [ledger]
  @ledger)
