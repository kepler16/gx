(ns k16.gx.node)

(defprotocol Node
  (traverse [_ k not-found])
  (refs [_]))

(defn is-node? [v]
  (satisfies? Node v))
