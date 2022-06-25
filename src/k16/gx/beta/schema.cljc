(ns k16.gx.beta.schema
  (:require
   [malli.core :as m]
   [malli.error :as me]
   [malli.util :as mu]))

(def ?SignalConfig
  [:map {:closed true}
   [:deps {:optional true} [:sequential keyword?]]
   [:from-states [:set keyword?]]
   [:to-state keyword?]
   [:deps-from {:optional true} keyword?]])

(def ?Context
  [:map {:closed true}
   [:initial-state keyword?]
   [:signal-mapping {:optional true} [:map-of keyword? keyword?]]
   [:normalize
    [:map
     [:form-evaluator fn?]
     [:auto-signal keyword?]
     [:props-signals [:set keyword?]]]]
   [:signals
    [:map-of keyword? ?SignalConfig]]])

(def explain-context (m/explainer ?Context))

(defn validate-context
  "Check graph config for errors,
   returns humanized explanation (if any errors)"
  [context]
  (-> context (explain-context) (me/humanize)))

(def gx-props [:gx/props {:optional true} coll?])

(def ?SignalDefinition
  [:map
   gx-props
   [:gx/processor ifn?]
   [:gx/props-schema {:optional true} some?]
   [:gx/props-fn {:optional true} some?]
   [:gx/resolved-props-fn {:optional true} [:maybe fn?]]
   [:gx/deps {:optional true} coll?]
   [:gx/resolved-props {:optional true} [:maybe some?]]])

(def ?NormalizedNodeDefinition
  [:map
   ;; top level props
   gx-props
   [:gx/component {:optional true }any?]
   [:gx/signal-mapping {:optional true} [:map-of keyword? keyword?]]
   [:gx/state {:optional true} keyword?]
   [:gx/failure {:optional true} any?]
   [:gx/type {:optional true} keyword?]
   [:gx/normalized? {:optional true} boolean?]
   [:gx/value {:optional true} any?]])

(defn normalized-node-schema
  [context]
  (let [signals (->> context
                     :signals
                     (keys)
                     (mapv (fn [sig-key]
                             [sig-key {:optional true} ?SignalDefinition]))
                     (into [:map {:open true}]))]
    (mu/closed-schema
     (mu/merge ?NormalizedNodeDefinition signals))))

(defn normalized?
  [context component]
  (m/validate (normalized-node-schema context) component))

(defn validate-component
  [context component]
  (let [schema (normalized-node-schema context)]
    [(->> component
          (m/explain schema)
          (me/humanize))
     (m/-form schema)
     component]))

(defn validate-graph
  [{:keys [graph context]}]
  (let [graph-schema [:map-of keyword? (normalized-node-schema context)]]
    (me/humanize
     (m/explain graph-schema graph))))
