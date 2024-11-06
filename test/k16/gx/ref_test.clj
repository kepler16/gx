(ns k16.gx.ref-test
  (:require
   [clojure.datafy :as d]
   [clojure.test :refer [deftest is]]
   [k16.gx.ref :as gx.ref]))

(deftest ref-test
  (let [ref (gx.ref/ref :some-key :fallback-value)]
    (is (= {:ref-paths #{[:some-key]}
            :value nil}
           (d/datafy ref)))
    (let [resolved-ref (gx.ref/lookup-in ref {:some-key :some-value})]
      (is (match? {:value :some-value} (d/datafy resolved-ref)))
      (is :some-value @resolved-ref))
    (is (= :fallback-value @(gx.ref/lookup-in ref {})))))

(deftest ref-in-test
  (let [ref (gx.ref/ref [:some-key :sub-key] :fallback-value)]
    (is (= {:ref-paths #{[:some-key :sub-key]}
            :value nil}
           (d/datafy ref)))
    (is (= :some-value @(gx.ref/lookup-in ref {:some-key {:sub-key :some-value}})))
    (is (= :fallback-value @(gx.ref/lookup-in ref {:some-key {}})))))

(deftest with-refs-test
  (let [ref (gx.ref/with-refs [a (gx.ref/ref :a)
                               b (gx.ref/ref :b)]
              (merge a b))]
    (is (= {:ref-paths #{[:a] [:b]}
            :value nil}
           (d/datafy ref)))
    (is (= {:left :value
            :right :value}
           @(gx.ref/lookup-in ref {:a {:left :value}
                                   :b {:right :value}})))))

(deftest with-refs-memoization-test
  (let [calls (atom 0)
        ref (gx.ref/with-refs [a (gx.ref/ref :a)
                               b (gx.ref/ref :b)]
              (swap! calls inc)
              {:a a :b b})]
    (is (= {:a 1
            :b 2}
           @(gx.ref/lookup-in ref {:a 1
                                   :b 2})))
    (is (= 1 @calls))

    (is (= {:a 1
            :b 2}
           @(gx.ref/lookup-in ref {:a 1
                                   :b 2})))
    (is (= 1 @calls))

    (is (= {:a 2
            :b 2}
           @(gx.ref/lookup-in ref {:a 2
                                   :b 2})))
    (is (= 2 @calls))))
