(ns k16.gx.malli
  (:require
   [clojure.datafy :as d]
   [clojure.walk :as walk]
   [k16.gx.component :as gx.component]
   [malli.core :as m]
   [malli.error :as me]))

(defn- make-validate-fn [?schema message]
  (fn validate-component [data]
    (when-not (m/validate ?schema data)
      (throw (ex-info message {:errors (-> (m/explain ?schema data)
                                           me/humanize)})))))

(defn- generate-validation-fns [{:keys [props-schema result-schema]}]
  (cond-> {}
    props-schema (assoc :validate-props
                        (make-validate-fn props-schema
                                          "Component props failed schema validation"))

    result-schema (assoc :validate-result
                         (make-validate-fn result-schema
                                           "Signal result failed schema validation"))))

(defn- compile-node [node]
  (let [{:keys [definition props]} (d/datafy node)

        signals (into {}
                      (map (fn [[key signal]]
                             (if (fn? signal)
                               [key signal]
                               [key (merge (generate-validation-fns signal)
                                           signal)])))
                      (:signals definition))

        definition (merge (generate-validation-fns definition)
                          definition
                          {:signals signals})]

    (gx.component/component props definition)))

(defn compile [graph]
  (walk/postwalk
   (fn [node]
     (if (gx.component/is-instance? node)
       (compile-node node)
       node))
   graph))
