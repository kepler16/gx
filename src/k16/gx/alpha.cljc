(ns k16.gx.alpha
  (:refer-clojure :exclude [compile extend])
  (:require [juxt.clip.promesa])
  (:require [juxt.clip.core :as clip]
            [promesa.core :as p]
            [clojure.walk :as walk]))

(defn- interpret-entry [[k v]]
  [k
   (cond
     (and (map? v) (:start v))
     v

     (keyword? v)
     {:start `(keyword ~v)}

     :else
     {:start v})])
     ;; (map? v)
     ;; {:start v})])

     ;; :else (throw (ex-info "Encountered unsupported graph entry while compiling" {:key k
     ;;                                                                              :value v})))])

(defn compile [graph-map]
  (if (:gx/compiled graph-map)
    graph-map
    (let [compiled-map
          (->> graph-map
               (map interpret-entry)
               (into {}))]
      {:gx/compiled compiled-map})))

(defn- executed->clip [executed]
  (->> executed
       (map (fn [[k v]] [k {:start `(identity ~v)}]))
       (into {})))

(defn- to-execute->clip [to-execute]
  (walk/postwalk
   (fn [n]
     (if (= n 'gx/ref)
       'clip/ref
       n))
   (:gx/compiled to-execute)))

(defn- normalise-resolver [resolver]
  (cond
      (fn? resolver) resolver
      :else (constantly resolver)))

(defn- resolve-definition [resolver]
  (try
    (-> (normalise-resolver resolver)
        (apply [])
        (p/then
         (fn [definition]
           (assert (map? definition))
           definition)))
    (catch #?(:clj Exception :cljs js/Error) e
      (p/rejected e))))

(defn- with-resolved [smap config]
  (walk/postwalk-replace smap config))

(defn resolve-gx-definition [definition-resolvers {:keys [instance-map]}]
  (-> (map resolve-definition definition-resolvers)
      (p/all)
      (p/then
       #(with-resolved
          (or instance-map {})
          (apply merge %)))))

(defn defuse-resolver [resolver]
  (fn []
    (try
      (let [normalised (normalise-resolver resolver)]
        (normalised))
      (catch #?(:clj Exception :cljs js/Error) e
        (p/resolved {})))))

(defn exec [[to-execute & rest] {:keys [system system-config]}]
  (let [to-execute (compile to-execute)
        static (some-> system executed->clip)
        dynamic (some-> to-execute to-execute->clip)
        system-map (merge static dynamic)
        system-config {:executor juxt.clip.promesa/exec
                       :components (merge (:components system-config) system-map)}]
    (-> (clip/start system-config)
        (p/then #(if rest
                   (exec rest {:system % :system-config system-config})
                   {:system %
                    :system-config system-config
                    :active? true}))
        (p/catch (fn [err]
                   (println err)
                   {:error err
                    :system-config system-config})))))

(defn stop [g]
  (if (:active? g)
    (-> (clip/stop (:system-config g) (:system g))
        (p/then (fn [stopped-system]
                  (merge g {:system stopped-system
                            :active? false})))
        (p/catch (fn [err]
                   (merge g {:error err}))))
    (do
      (println "No active system")
      (p/resolved g))))

(def system* (atom nil))
(def system-config-fn* (atom nil))

(defn set-config-fn! [f]
  (reset! system-config-fn* f))

(defn start! []
  (cond
    (not @system-config-fn*) (println "NO system-config-fn set!!!")
    (some-> @system* :active?) (println "System already started")
    :else (let [config (@system-config-fn*)
                resolved-system @(exec [config] {})]
            (when (and (:error resolved-system)
                       (not (:development? config)))
              #?(:clj (System/exit 1)
                 :cljs nil ;; TODO: here should be some error message in CLJS UI
                 ))
            (reset! system* resolved-system))))

(defn stop! []
  (when @system*
    (reset! system* @(stop @system*))))

(defn reboot! []
  (stop!)
  (start!))

(comment
  (def graph-1
    (compile
     {:a {:a 1}
      :z '(get (gx/ref :a) :a)
      :y '(println "starting")
      :b {:start '(+ (gx/ref :z) 2)
          :stop '(println "stopping")}}))

  (def graph-2
    {:c {:start '(gx/ref :b)}
     :d {:w 3}})

  (def exec-1 @(exec [graph-1] {}))

  (meta exec-1)
  (def stopped @(stop exec-1))

  (def exec-2 @(exec [graph-1 graph-2] {:system {:yyyyy 3}}))

  (def exec-3 @(exec [graph-2] {:system exec-1}))

  (def exec-4 @(exec [graph-2] {}))

  @(resolve-gx-definition
    [{}
     (fn []
       (-> (p/delay 300)
           (p/then (fn [_] {}))))]
    {})

  (set-config-fn!
   (fn []
     graph-1))

  (reboot!)

  nil)
