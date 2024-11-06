(ns k16.gx.parse
  (:refer-clojure :exclude [read-string])
  (:require
   [clojure.edn :as edn]
   [clojure.walk :as walk]
   [k16.gx :as gx]
   [k16.gx.component :as gx.component]
   [k16.gx.ref :as gx.ref]))

(def ^:private gx-macro-mapping
  {'gx/with-refs #'gx.ref/with-refs*})

(def ^:private gx-fn-mapping
  {'gx/ref gx.ref/ref
   'gx/component gx.component/component})

(defn- rewrite-macro [node]
  (apply (get gx-macro-mapping (first node))
         (rest node)))

(defn eval-gx-graph [graph]
  (let [var->binding
        (into {}
              (map (fn [[k]]
                     [k (gensym (name k))]))
              gx-fn-mapping)
        gx-syms (set (keys gx-fn-mapping))
        gx-macros (set (keys gx-macro-mapping))
        bindings
        (into []
              (mapcat (fn [[k v]]
                        [(get var->binding k) v]))
              gx-fn-mapping)

        sexpr
        `(let ~bindings
           ~(walk/postwalk
             (fn [node]
               (cond
                 (and (list? node)
                      (some #{(first node)} gx-macros))
                 (rewrite-macro node)

                 (and (symbol? node)
                      (some #{node} gx-syms))
                 (get var->binding node)

                 :else node))
             graph))]
    (eval sexpr)))

(defn read-string [string-data]
  (->> (edn/read-string string-data)
       eval-gx-graph))

(defn read-string! [string-data]
  (->> (read-string string-data)
       gx/validate!))
