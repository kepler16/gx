(ns k16.gx.parse-test
  (:require
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is]]
   [k16.gx :as gx]
   [k16.gx.component :as gx.component]
   [k16.gx.parse :as gx.parse]
   [k16.gx.ref :as gx.ref]
   [matcher-combinators.test]))

(deftest read-string-test
  (let [graph (gx.parse/read-string (slurp (io/resource "fixtures/test-graph.edn")))]
    (is (match? {:raw-value {:a 1}
                 :ref gx.ref/is-ref?
                 :ref-in gx.ref/is-ref?
                 :with-refs gx.ref/is-ref?
                 :component gx.component/is-instance?}
                graph))

    (is (match? {:raw-value {:a 1}
                 :ref {:a 1}
                 :ref-in 1
                 :with-refs {:b 2}
                 :component {:input-props {:b 2}}}
                (gx/values (gx/signal! graph :start))))))
