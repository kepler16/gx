(ns k16.gx.malli-validation-test
  (:require
   [clojure.datafy :as d]
   [clojure.test :refer [deftest is]]
   [clojure.walk :as walk]
   [k16.gx :as gx]
   [k16.gx.component :as gx.component]
   [k16.gx.malli :as gx.malli]
   [k16.gx.ref :as gx.ref]
   [matcher-combinators.test]))

(deftest signal-test
  (let [component-def {:signals {:start (fn [_ props]
                                          {:props props})}
                       :props-schema [:map
                                      [:value :int]]}

        graph {:a 1
               :b "2"

               :c-a (gx.component/component
                     {:value (gx.ref/ref :a)}
                     component-def)

               :c-b (gx.component/component
                     {:value (gx.ref/ref :b)}
                     component-def)}

        graph (gx/signal! (gx.malli/compile graph) :start)]

    (is (thrown-match? Exception {:errors {:value ["should be an integer"]}}
                       (gx/values graph)))

    (is (match? {:a 1
                 :b "2"
                 :c-a {:definition {:signals {:start fn?}
                                    :validate-props fn?}
                       :error nil
                       :props {:ref-paths #{[:a]}
                               :value {:value {:ref-paths #{[:a]}
                                               :value 1}}}
                       :ref-paths #{[:a]}
                       :state :start
                       :value {:props {:value 1}}}
                 :c-b {:definition {:signals {:start fn?}
                                    :validate-props fn?}
                       :error {:cause "Component props failed schema validation"
                               :data {:errors {:value ["should be an integer"]}}}

                       :props {:ref-paths #{[:b]}
                               :value nil}
                       :ref-paths #{[:b]}
                       :state nil
                       :value nil}}
                (walk/prewalk d/datafy graph)))))
