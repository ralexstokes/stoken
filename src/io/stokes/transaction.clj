(ns io.stokes.transaction)

(defn from [from to amount fee]
  {:from   from
   :to     to
   :amount amount
   :fee    fee})

(defn fee [transaction]
  (:fee transaction 0))
