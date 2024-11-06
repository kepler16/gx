(ns k16.gx.system-test
  (:require
   [clojure.test :refer [deftest is]]
   [k16.gx :as gx]
   [k16.gx.system :as gx.system]
   [matcher-combinators.test]))

(deftest system-test
  (let [system (gx.system/system
                {:a (gx/ref :b)
                 :b 1
                 :c (gx/component {:a (gx/ref :a)}
                                  {:signals {:start (fn [_ props]
                                                      {:props props})
                                             :stop (fn [_ _]
                                                     :stopped)}})})]

    (is (= {:a 1 :b 1 :c {:props {:a 1}}}
           (gx.system/start! system)))

    (is (= {:a 1 :b 1 :c {:props {:a 1}}}
           @system))

    (is (= {:a 1 :b 1 :c :stopped}
           (gx.system/stop! system)))

    (is (= {:a 1 :b 1 :c :stopped}
           @system))))

(deftest system-error-test
  (let [system (gx.system/system
                {:a (gx/component {:signals {:start (fn [_ _] :started)
                                             :stop (fn [_ _] :stopped)}})
                 :b (gx/component {:signals {:start (fn [_ _]
                                                      (throw (ex-info "Failed to start" {})))
                                             :stop (fn [_ _] :stopped)}})})]

    (is (thrown-match? Exception {}
                       (gx.system/start! system)))

    (is (= {:a :stopped :b nil}
           (gx.system/stop! system)))

    (is (= {:a :stopped :b nil}
           @system))))
