(ns k16.gx.system
  (:require
   [k16.gx :as gx]))

(defprotocol GxSystem
  (signal! [this signal])
  (start! [this])
  (stop! [this]))

(defn system [graph]
  (let [graph (atom (gx/validate! graph))]
    (reify GxSystem
      (start! [_]
        (let [next (swap! graph (fn [graph]
                                  (gx/signal!
                                   graph
                                   :start)))]
          (gx/values next)))

      (stop! [_]
        (let [next (swap! graph (fn [graph]
                                  (gx/signal!
                                   graph
                                   :stop
                                   {:filter-states #{:start}
                                    :order :reverse})))]
          (gx/values next)))

      clojure.lang.IDeref
      (deref [_]
        (gx/values @graph)))))
