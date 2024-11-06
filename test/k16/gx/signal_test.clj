(ns k16.gx.signal-test
  (:require
   [clojure.datafy :as d]
   [clojure.test :refer [deftest is]]
   [clojure.walk :as walk]
   [k16.gx :as gx]
   [k16.gx.component :as gx.component]
   [k16.gx.ref :as gx.ref]
   [matcher-combinators.test]))

(deftest signal-test
  (let [component (gx.component/component
                   {:c-ref (gx.ref/ref :c)}
                   {:signals {:start (fn [_ props]
                                       {:props props})
                              :stop (fn [current props]
                                      {:prev-value current
                                       :props props
                                       :stopped? true})}})

        graph {:a {:k 1}
               :b (gx.ref/ref :a)
               :c (gx.ref/ref [:a :k])
               :component component}

        graph (gx/signal! graph :start)
        values (gx/values graph)]
    (is (match? {:a {:k 1}
                 :b {:k 1}
                 :c 1
                 :component {:props {:c-ref 1}}}
                values))

    (is (= values (gx/values graph)))

    (is (match? {:a {:k 1}
                 :b {:ref-paths #{[:a]}
                     :value {:k 1}}
                 :c {:ref-paths #{[:a :k]}
                     :value 1}
                 :component {:props {:ref-paths #{[:c]}
                                     :value {:c-ref {:ref-paths #{[:c]}
                                                     :value {:ref-paths #{[:a :k]}
                                                             :value 1}}}}
                             :ref-paths #{[:c]}
                             :definition {:signals {:start fn?
                                                    :stop fn?}}
                             :state :start
                             :error nil
                             :value {:props {:c-ref 1}}}}
                (walk/prewalk d/datafy graph)))

    (is (match? {:component {:prev-value {:props {:c-ref 1}}
                             :props {:c-ref 1}
                             :stopped? true}}
                (gx/values
                 (gx/signal! graph :stop))))))

(deftest reverse-signal-test
  (let [call-order (atom [])
        record-order (fn [component]
                       (fn [_ _]
                         (swap! call-order conj component)))

        graph {:component-a (gx.component/component
                             {:signals {:start (record-order :a)
                                        :stop (record-order :a)}})
               :component-b (gx.component/component
                             (gx.ref/ref :component-a)
                             {:signals {:start (record-order :b)
                                        :stop (record-order :b)}})}]

    (gx/signal! graph :start)
    (is (= [:a :b] @call-order))

    (reset! call-order [])

    (gx/signal! graph :stop {:order :reverse})
    (is (= [:b :a] @call-order))))

(deftest idempotency-test
  (let [calls (atom 0)
        graph {:component (gx.component/component
                           {:signals {:start (fn [_ _]
                                               {:calls (swap! calls inc)})}})}
        graph (gx/signal! graph :start)
        values (gx/values graph)
        graph (gx/signal! graph :start)]

    (is (match? {:component {:calls 1}}
                values))
    (is (= values (gx/values graph)))
    (is (= @calls 1))))

(deftest missing-signal-test
  (let [graph {:component (gx.component/component
                           {:signals {:start (fn [_ _]
                                               :start)}})}
        graph (gx/signal! graph :start)]

    (is (match? {:component :start} (gx/values graph)))

    (let [graph (gx/signal! graph :missing)]
      (is (= {:component :start} (gx/values graph))))))

(deftest error-test
  (let [graph {:component (gx.component/component
                           {:signals {:start (fn [_ _]
                                               (throw (ex-info "Start failed" {})))}})}
        graph (gx/signal! graph :start)]

    (is (match? {:error (partial instance? Exception)}
                (d/datafy (:component graph))))

    (is (thrown-match? Exception {}
                       (gx/values graph)))

    (is (match? {:component (partial instance? Exception)}
                (gx/errors graph)))))

(deftest error-halts-execution-test
  (let [graph {:component-a (gx.component/component
                             {:signals {:start (fn [_ _]
                                                 (throw (ex-info "Start failed" {}))
                                                 {:data 1})}})
               :component-b (gx.component/component
                             (gx.ref/ref :component-a)
                             {:signals {:start (fn [_ _]
                                                 :started)}})}
        graph (gx/signal! graph :start)]

    (is (match? {:component-a {:state nil
                               :error (partial instance? Exception)
                               :value nil}
                 :component-b {:state nil
                               :value nil
                               :error nil}}
                (walk/postwalk d/datafy graph)))

    (is (thrown-match? Exception {}
                       (gx/values graph)))

    (is (match? {:component-a (partial instance? Exception)}
                (gx/errors graph)))))

(deftest partial-error-stop-test
  (let [graph {:component-a (gx.component/component
                             {:signals {:start (fn [_ _]
                                                 1)
                                        :stop (fn [_ _]
                                                :stopped)}})
               :component-b (gx.component/component
                             {:signals {:start (fn [_ _]
                                                 (throw (ex-info "Start failed" {})))
                                        :stop (fn [_ _]
                                                (throw (ex-info "Cannot call stop on failed component" {})))}})}

        graph (gx/signal! graph :start)]
    (is (thrown-match? Exception {}
                       (gx/values graph)))

    (is (match? {:component-a {:state :stop
                               :value :stopped}
                 :component-b {:state nil
                               :value nil
                               :error nil}}
                (walk/postwalk d/datafy
                               (gx/signal! graph :stop {:order :reverse
                                                        :filter-states #{:start}}))))))
(deftest timeout-test
  (let [graph {:component (gx.component/component
                           {:signals {:start (fn [_ _]
                                               (Thread/sleep 500))}
                            :timeout-ms 100})}
        graph (gx/signal! graph :start)]

    (is (match? {:error (partial instance? Exception)}
                (d/datafy (:component graph))))

    (is (thrown-match? Exception {}
                       (gx/values graph)))

    (is (match? {:component (partial instance? Exception)}
                (gx/errors graph)))))
