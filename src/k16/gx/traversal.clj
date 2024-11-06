(ns k16.gx.traversal
  (:require
   [k16.gx.node :as gx.node]))

(defn get-in*
  ([m ks] (get-in* m ks nil))
  ([m ks not-found]
   (loop [sentinel (Object.)
          current m
          ks (seq ks)]
     (if ks
       (let [value (if (gx.node/is-node? current)
                     (gx.node/traverse current (first ks) sentinel)
                     (get current (first ks) sentinel))]

         (if (identical? sentinel value)
           not-found
           (recur sentinel value (next ks))))
       current))))

(defn find-outer-node [graph path]
  (loop [path path
         ref-id []
         subgraph graph]
    (let [next (first path)
          subgraph (get subgraph next)
          ref-id (conj ref-id next)]
      (cond
        (nil? subgraph)
        nil

        (not (seq (rest path)))
        ref-id

        (gx.node/is-node? subgraph)
        ref-id

        :else
        (recur (rest path)
               ref-id
               subgraph)))))

(defn- path-seq* [node path]
  (cond
    (sequential? node)
    (mapcat
     (fn [item idx]
       (path-seq* item (conj (or path []) idx)))
     node
     (iterate inc 0))

    (map? node)
    (mapcat
     (fn [[k v]]
       (path-seq* v (conj (or path []) k)))
     node)

    (nil? node)
    [[nil path]]

    :else [[node path]]))

(defn path-seq
  [graph]
  (map
   (fn [[form path]]
     [path form])
   (path-seq* graph nil)))
