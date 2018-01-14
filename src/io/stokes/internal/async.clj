(ns io.stokes.internal.async
  (:require [clojure.core.async :as async]))

(defn apply-until-stopped
  "calls `f` on each value obtained from the channel `q`. To cancel this routine, call the returned function."
  [q f]
  (let [stop (async/chan)]
    (async/go-loop [[val chan] (async/alts! [q stop])]
      (when-not (= chan stop)
        (when val
          (f val))
        (recur (async/alts! [q stop]))))
    (fn [] (async/close! stop))))

(defn deliver-until-stopped
  "repeatedly calls `f`, putting the result of evaluation onto `q` until some value is sent on the returned channel"
  [q f]
  (let [stop (async/chan)]
    (async/go-loop [[_ chan] (async/alts! [stop] :default :continue)]
      (when-not (= chan stop)
        (when-let [val (f)]
          (async/>! q val))
        (recur (async/alts! [stop] :default :continue))))
    (fn [] (async/close! stop))))
