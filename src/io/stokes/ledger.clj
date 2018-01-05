(ns io.stokes.ledger)

(defn- apply-transaction [ledger {:keys [from to amount]}]
  (-> ledger
      (update from #(- (or % 0) amount))
      (update to #(+ (or % 0) amount))))

(defn apply-transactions [ledger transactions]
  (reduce apply-transaction ledger transactions))

(defn from [{:keys [initial-state]}]
  ;; TODO adapt into UTXO set w/ "account" cache
  (into {} initial-state))

(defn balances [ledger]
  ;; TODO filter out the zero address
  ledger)
