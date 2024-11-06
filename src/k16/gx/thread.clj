(ns k16.gx.thread
  (:import
   java.util.concurrent.ExecutorService
   java.util.concurrent.Executors))

(def ^:dynamic *executor*
  (Executors/newVirtualThreadPerTaskExecutor))

(defmacro vthread [& body]
  `(.submit ^ExecutorService *executor*
            ^Callable (fn [] ~@body)))

(defn deref-maybe-fut [maybe-fut]
  (if (future? maybe-fut)
    @maybe-fut
    maybe-fut))
