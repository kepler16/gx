---
id: example
title: Practical Example App
sidebar_label: Example App
slug: /example-app
---

# More practical example

You can't do much with static nodes. GX supports components, a **component** is a node with custom signal handlers.

## Lets create an app config

In app config need:
- HTTP server options
- routes (example only, better to define routes inside code and add them as a component)
- router component
- handler component
- server component

```clojure title="resources/config.edn"
{:http/options {:port 8080}

 :http/routes ["/users"
               {:get
                ;; existing functions should be referenced with fully
                ;; qualified name
                {:handler app/get-users-handler}}]

 :http/ring-router {;; component is a fully qualified name of defined component
                    :gx/component components/ring-router
                    ;; props of a component
                    :gx/props {:routes (gx/ref :http/routes)}}

 :http/ring-handler {:gx/component components/ring-handler
                     :gx/props {:router (gx/ref :http/router)}}

 :http/server {:gx/component components/http-server
               :gx/props {:handler (gx/ref :http/ring-handler)
                          :options (gx/ref :http/options)}}

 ;; pseudo logger component, logs port by taking it from :http/options
 :app (println "Application is starting on port " (get (gx/ref :http/options)
                                                       :port))
 ;; independend component
 :another {:gx/start (println "another starting")
           :gx/stop (println "another stopping")}}
```
Our app is a simple web server with one route `/users`. You may noticed a new keyword `:gx/component` which is used for including predefined components. There are two different types of gx references available in config:

| type          | description        | example                                                  |
| ------------- | ------------------ | -------------------------------------------------------- |
| `gx/ref`      | gets node's value by key | `(gx/ref :a)` => `{:foo 1}`                              |
| `gx/ref-keys` | runs select-keys on graph | `(gx/ref-keys [:a :b])` => `{:a {:foo 1} :b {:bar 1}}` |

Alright, let's write app code:

```clojure title="src/app.clj"
(ns app)

;; /users route handler
(defn get-users-handler
  [_args]
  {:status 200
   :content-type "text/plain"
   :body (pr-str [{:user "John"}
                  {:user "Peter"}
                  {:user "Donald"}])})
```

```clojure title="src/components.clj"
(ns components
  (:require [reitit.ring :as reitit-ring]
            [org.httpkit.server :as http-kit]))

(def ring-router
  {:gx/start
   {;; :gx/processor contains signal function
    ;; processor function must accept map with two keys
    ;; :props - resolved props
    ;; :value - current node's value
    ;; data returned by handler is a node's new value
    :gx/processor (fn start-router [{{:keys [routes]} :props}]
                    (reitit-ring/router routes))}})

(def ring-handler
  {:gx/start
   {:gx/processor (fn start-router [{{:keys [router]} :props}]
                    (reitit-ring/ring-handler router))}})

(def http-server
  {:gx/start
   {;; incoming props will be validated against malli schema
    ;; during signal application
    :gx/props-schema [:map
                      [:handler fn?]
                      [:options :map]]
    :gx/processor (fn [{{:keys [handler options]} :props}]
                    (http-kit/run-server handler options))}

   :gx/stop {:gx/processor (fn [{server :value}]
                             (server))}})

```

We defined handler function, routes, ring router and server components and they are all glued by our `config.edn` file.

Sweet, our app is ready. Next we add some boring system routines:

```clojure title="src/main.clj"
(ns main
  (:require [app]
            [clojure.edn :as edn]
            [k16.gx.beta.core :as gx]
            ;; this ns contains helpers for managing systems
            [k16.gx.beta.system :as gx.system]))

(defn load-system! []
  ;; register our system, so later we can get it by name
  (gx.system/register!
   ::system
   ;; :context key is optional, fallbacks to gx/default-context
   {:context gx/default-context
    :graph (edn/read-string (slurp "resources/config.edn"))}))

(defn start! []
  (gx.system/signal! ::system :gx/start))

(defn stop! []
  (gx.system/signal! ::system :gx/stop))

(defn reset! []
  (stop!)
  (load-system!)
  (start!))

(defn failures []
  (gx.system/failures-humanized ::system))

(defn -main [& _args]
  (load-system!)

  (doto (Runtime/getRuntime)
    (.addShutdownHook
     (Thread. #(stop!))))

  (start!)

  ;; we don't want semi-started system on production
  ;; check for failures, print them and exit if any
  (when-let [failures (seq (failures))]
    (doseq [failure failures]
      (println failure))
    (System/exit 1))

  @(promise))
```

Cool, run the app from terminal
```
clj -M:main
```
Open your browser: http://localhost:8080/users. Viola! In the next step, we will add a database component.

Full source code is available on [github](https://github.com/kepler16/gx.cljc/tree/master/examples/simple).