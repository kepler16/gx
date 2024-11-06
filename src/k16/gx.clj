(ns k16.gx
  (:refer-clojure :exclude [ref])
  (:require
   [clojure.datafy :as d]
   [k16.gx.component :as gx.component]
   [k16.gx.graph :as gx.graph]
   [k16.gx.node :as gx.node]
   [k16.gx.ref :as gx.ref]
   [k16.gx.thread :as gx.thread]
   [k16.gx.traversal :as gx.traversal]))

(defmacro ref
  ([key]
   `(gx.ref/ref ~key))
  ([key fallback]
   `(gx.ref/ref ~key ~fallback)))

(defmacro with-refs [refs & body]
  `(gx.ref/with-refs ~refs ~@body))

(defmacro component
  ([definition]
   `(gx.component/component ~definition))
  ([props signals]
   `(gx.component/component ~props ~signals)))

(defn validate! [graph]
  (let [cycles (gx.graph/find-cyclic-refs (gx.graph/ref-graph graph))]
    (when (seq cycles)
      (throw (ex-info "Graph contains cyclic references"
                      {:errors (gx.graph/cyclic-refs->errors cycles)}))))
  graph)

(defn values [graph]
  (gx.graph/reduce-ordered
   (fn [result-graph grouped-ref-paths]
     (reduce
      (fn [result ref-path]
        (let [node (gx.traversal/get-in* graph ref-path)
              value (cond
                      (gx.ref/is-ref? node)
                      @(gx.ref/lookup-in node result)

                      (gx.node/is-node? node)
                      @node)]
          (assoc-in result ref-path value)))
      result-graph
      grouped-ref-paths))
   graph))

(defn errors [graph]
  (reduce
   (fn [errors [node-path _]]
     (let [node (gx.traversal/get-in* graph node-path)]
       (if (and (gx.component/is-instance? node)
                (gx.component/has-error? node))
         (assoc-in errors node-path (:error (d/datafy node)))
         errors)))
   {}
   (gx.graph/ref-graph graph)))

(defn- signal-node!
  [node signal-key partial-graph opts]
  (cond
    (gx.component/is-instance? node)
    (gx.thread/vthread
     (gx.component/signal! node signal-key partial-graph opts))

    (gx.ref/is-ref? node)
    (gx.ref/lookup-in node partial-graph)

    :else node))

(defn signal!
  ([graph signal-key] (signal! graph signal-key {}))
  ([graph signal-key opts]
   (gx.graph/reduce-ordered
    (fn [result-graph grouped-ref-paths]
      (let [signaled-nodes
            (->> grouped-ref-paths
                 (mapv
                  (fn [ref-path]
                    (let [node (gx.traversal/get-in* graph ref-path)
                          maybe-fut (signal-node! node
                                                  signal-key
                                                  result-graph
                                                  opts)]
                      [ref-path maybe-fut])))
                 (mapv
                  (fn [[node-key maybe-fut]]
                    [node-key (gx.thread/deref-maybe-fut maybe-fut)])))

            contains-errors?
            (some (fn [[_ node]]
                    (and (gx.component/is-instance? node)
                         (gx.component/has-error? node)))
                  signaled-nodes)

            result
            (reduce
             (fn [graph [node-key value]]
               (assoc-in graph node-key value))
             result-graph
             signaled-nodes)]
        (if contains-errors?
          (reduced result)
          result)))
    graph
    opts)))
