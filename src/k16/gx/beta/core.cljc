(ns k16.gx.beta.core
  (:refer-clojure :exclude [ref])
  #?(:cljs (:require-macros [k16.gx.beta.context]))
  (:require [malli.core :as m]
            [malli.error :as me]
            [promesa.core :as p]
            [k16.gx.beta.normalize :as gx.norm]
            [k16.gx.beta.impl :as impl]
            [k16.gx.beta.schema :as gx.schema]
            [k16.gx.beta.errors :as gx.err]
            [k16.gx.beta.context :refer [merge-err-ctx] :as gx.ctx])
  (:import #?(:clj [clojure.lang ExceptionInfo])))

(def default-context gx.norm/default-context)

(defn ref
  [key]
  (list 'gx/ref key))

(defn ref-keys
  [& keys]
  (apply list (conj keys 'gx/ref-keys)))

(defn signal-dependencies
  [{:keys [signals]}]
  (->> signals
       (map (fn [[k v]]
              [k (if-let [f (:deps-from v)]
                   #{f}
                   #{})]))
       (into {})))

(defn validate-context
  "Validates context against schema and checks signal dependency errors"
  [context]
  (or (gx.schema/validate-context context)
      (let [deps (signal-dependencies context)]
        (->> deps
             (impl/sccs)
             (impl/dependency-errors deps)
             (map impl/human-render-dependency-error)
             (seq)))))

(defn normalize
  "Given a graph definition and config, return a normalised form. Idempotent.
   This acts as the static analysis step of the graph.
   Returns tuple of error explanation (if any) and normamized graph."
  [{:keys [context graph]
    :or {context default-context}
    :as gx-map}]
  (let [config-issues (validate-context context)
        gx-map (assoc gx-map :context context)
        ;; remove previous normalization errors
        gx-map' (cond-> gx-map
                  (not (:initial-graph gx-map)) (assoc :initial-graph graph)
                  :always (dissoc :failures))]
    (try
      (if config-issues
        (throw (ex-info "GX Context error" config-issues))
        (gx.norm/normalize-graph gx-map'))
      (catch ExceptionInfo e
        (update gx-map' :failures conj (gx.err/ex->gx-err-data e))))))

(defn graph-dependencies [graph signal-key]
  (->> graph
       (map (fn [[k node]]
              (let [deps (-> node
                             signal-key
                             :gx/deps)]
                [k (into #{} deps)])))
       (into {})))

(defn topo-sort
  "Sorts graph nodes according to signal topology, returns vector of
   [error, sorted nodes]"
  ([gx-map signal-key]
   (topo-sort gx-map signal-key #{}))
  ([{:keys [context graph]} signal-key priority-selector]
   (merge-err-ctx {:error-type :deps-sort :signal-key signal-key}
     (try
       (if-let [signal-config (get-in context [:signals signal-key])]
         (let [deps-from (or (:deps-from signal-config) signal-key)
               selector-deps (reduce (fn [acc k]
                                       (->> [k deps-from :gx/deps]
                                            (get-in graph)
                                            (set)
                                            (assoc acc k)))
                                     {} priority-selector)
               graph-deps
               (->> deps-from
                    (graph-dependencies graph)
                    (map (fn [[k deps :as signal-deps]]
                           (let [node-selector
                                 (->> selector-deps
                                      (filter (fn [[_ d]] (not (contains? d k))))
                                      (map first)
                                      (set))]
                             (if (contains? node-selector k)
                               signal-deps
                               [k (into deps node-selector)]))))
                    (into {}))
               sorted-raw (impl/sccs graph-deps)]
           (when-let [errors (->> sorted-raw
                                  (impl/dependency-errors graph-deps)
                                  (map impl/human-render-dependency-error)
                                  (seq))]
             (gx.err/throw-gx-err "Dependency errors" {:errors errors}))
           [nil
            (let [topo-sorted (map first sorted-raw)]
              ;; if signal takes deps from another signal then it is anti-signal
              (if (:deps-from signal-config)
                (reverse topo-sorted)
                topo-sorted))])
         (gx.err/throw-gx-err (str "Unknown signal key '" signal-key "'")))
       (catch ExceptionInfo e
         [(assoc (ex-data e) :message (ex-message e))])))))

(defn get-component-props
  [graph property-key]
  (->> graph
       (map (fn [[k node]]
              [k (get node property-key)]))
       (into {})))

(defn system-failure [gx-map]
  (get-component-props (:graph gx-map) :gx/failure))

(defn system-value [gx-map]
  (get-component-props (:graph gx-map) :gx/value))

(defn system-state [gx-map]
  (get-component-props (:graph gx-map) :gx/state))

(defn props-validate-error
  [schema props]
  (when-let [error (and schema (m/explain schema props))]
    (merge-err-ctx {:error-type :props-validation}
      (gx.err/gx-err-data "Props validation error"
                          {:props-value props
                           :props-schema schema
                           :schema-error (me/humanize error)}))))

(defn- run-props-fn
  [props-fn arg-map]
  (try
    (props-fn arg-map)
    (catch #?(:clj Throwable :cljs :default) e
      (gx.err/throw-gx-err "Props function error"
                           {:ex-message (impl/error-message e)
                            :args arg-map}))))

(defn- wrap-error
  [e arg-map]
  (gx.err/gx-err-data "Signal processor error"
                      {:ex-message (impl/error-message e)
                       :ex (or (ex-data e) e)
                       :args arg-map}))

#?(:cljs
   (defn- wrap-error-cljs
     [e arg-map err-ctx]
     (merge-err-ctx err-ctx
       (wrap-error e arg-map))))

#?(:clj
   (defn- run-processor
     [processor arg-map]
     (try
       [nil @(p/do (processor arg-map))]
       (catch Throwable e
         [(wrap-error e arg-map) nil])))

   :cljs
   (defn- run-processor
     "CLJS version with error context propagation"
     [processor arg-map err-ctx]
     (try
       (-> (processor arg-map)
           (p/then (fn [v] [nil v]))
           (p/catch (fn [e] [(wrap-error-cljs e arg-map err-ctx) nil])))
       (catch :default e
         [(wrap-error-cljs e arg-map err-ctx) nil]))))

(defn node-signal
  "Trigger a signal through a node, assumes dependencies have been run.
   Subsequent signal calls is supported, but it should be handled in it's
   implementation. For example, http server component checks that it
   already started and does nothing to prevent port taken error or it
   can restart itself by taking recalculated properties from deps.
   Static nodes just recalculates its values.
   If node does not support signal then do nothing."
  [{:keys [context graph initial-graph]} node-key signal-key]
  (let [evaluate-fn (-> context :normalize :form-evaluator)
        signal-config (-> context :signals signal-key)
        {:keys [deps-from from-states to-state]} signal-config
        node-def (get graph node-key)
        node (gx.norm/normalize-node context node-def)
        node-state (:gx/state node)
        signal-def (get node signal-key)
        {:gx/keys [processor props-schema resolved-props]} signal-def
        ;; take deps from another signal of node if current signal has deps-from
        ;; and does not have resolved props
        {:gx/keys [resolved-props resolved-props-fn deps]}
        (if (and deps-from (not resolved-props))
          (get node deps-from)
          signal-def)
        dep-nodes (select-keys graph deps)
        dep-nodes-vals (system-value {:graph dep-nodes})
        failed-dep-node-keys (->> {:graph dep-nodes}
                                  (system-failure)
                                  (filter second)
                                  (map first))]
    (merge-err-ctx {:node-contents (node-key initial-graph)}
      (cond
        (or ;; signal isn't defined for this state transition
         (not (contains? from-states node-state))
            ;; node is already in to-state
         (= node-state to-state))
        node

        (seq failed-dep-node-keys)
        (assoc node :gx/failure (gx.err/gx-err-data
                                 "Failure in dependencies"
                                 {:dep-node-keys failed-dep-node-keys}))
        (ifn? processor)
        ;; Binding vars is not passed to nested async code
        ;; Workaround for CLJS: propagating error context manually
        (let [err-ctx (gx.ctx/err)]
          (p/let [props-result (if (fn? resolved-props-fn)
                                 (run-props-fn resolved-props-fn dep-nodes-vals)
                                 (evaluate-fn dep-nodes-vals resolved-props))
                  validate-error (merge-err-ctx err-ctx
                                   (props-validate-error props-schema props-result))
                  [error result] (when-not validate-error
                                   (run-processor
                                    processor
                                    {:props props-result
                                     :value (:gx/value node)}
                                    #?(:cljs err-ctx)))]
            (if-let [e (or validate-error error)]
              (assoc node :gx/failure e)
              (-> node
                  (assoc :gx/value result)
                  (assoc :gx/state to-state)))))

        :else (assoc node :gx/state to-state)))))

(defn merge-node-failure
  [gx-map node]
  (if-let [failure (:gx/failure node)]
    (update gx-map :failures conj failure)
    gx-map))

(defn signal
  ([gx-map signal-key]
   (signal gx-map signal-key #{}))
  ([gx-map signal-key priority-selector]
   (let [gx-map (normalize (dissoc gx-map :failures))
         [error sorted] (topo-sort gx-map signal-key priority-selector)
         gx-map (if error
                  (update gx-map :failures conj error)
                  gx-map)]
     (if (seq (:failures gx-map))
       (p/resolved gx-map)
       (p/loop [gxm gx-map
                sorted sorted]
         (cond
           (seq sorted)
           (p/let [node-key (first sorted)
                   node (merge-err-ctx {:error-type :node-signal
                                        :signal-key signal-key
                                        :node-key node-key}
                          (node-signal gxm node-key signal-key))
                   next-gxm (assoc-in gxm [:graph node-key] node)]
             (p/recur (merge-node-failure next-gxm node) (rest sorted)))

           :else gxm))))))

(comment
  (def graph {:a {:nested-a 1},
              :z '(get (gx/ref :a) :nested-a),
              :y '(println "starting"),
              :b #:gx{:start '(+ (gx/ref :z) 2), :stop '(println "stopping")},
              :c #:gx{:component 'k16.gx.beta.core-test/test-component},
              :x #:gx{:component 'k16.gx.beta.core-test/test-component-2}})

  (normalize {:context default-context
              :graph graph}))
