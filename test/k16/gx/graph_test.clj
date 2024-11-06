(ns k16.gx.graph-test
  (:require
   [clojure.test :refer [deftest is]]
   [k16.gx.graph :as gx.graph]
   [k16.gx.ref :as gx.ref]
   [matcher-combinators.test]))

(deftest ref-raw-value-test
  (is (match? {[:b] #{}
               [:c] #{}}
              (gx.graph/ref-graph
               {:a {:k 1}
                :b (gx.ref/ref :a)
                :c (gx.ref/ref [:a :k])}))))

(deftest direct-ref-test
  (is (match? {[:a] #{}
               [:b] #{[:a]}}
              (gx.graph/ref-graph
               {:data {}
                :a (gx.ref/ref :data)
                :b (gx.ref/ref :a)}))))

(deftest ref-traversal-test
  (is (match? {[:a] #{}
               [:b] #{[:a]}}
              (gx.graph/ref-graph
               {:data {:k 1}
                :a (gx.ref/ref :data)
                :b (gx.ref/ref [:a :data])}))))

(deftest indirect-ref-test
  (is (match? {[:container :k1] #{}
               [:container :k2] #{}
               [:b] #{[:container :k1] [:container :k2]}}
              (gx.graph/ref-graph
               {:k1 1
                :k2 1
                :container {:k1 (gx.ref/ref :k1)
                            :k2 (gx.ref/ref :k2)}
                :b (gx.ref/ref :container)}))))

(deftest missing-ref-test
  (is (match? {[:a] #{}}
              (gx.graph/ref-graph
               {:a (gx.ref/ref :missing)}))))
