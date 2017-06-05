(ns kabel.platform-log
  "Logging for Clojure."
  (:require [taoensso.timbre :refer [-log! *config*] :as timbre])
  #_(:import [org.slf4j LoggerFactory]))





;; support logging also in clj macroexpansion for cljs
(defn- cljs-env?
  "Take the &env from a macro, and tell whether we are expanding into cljs."
  [env]
  (boolean (:ns env)))

(defmacro if-cljs
  "Return then if we are generating cljs code and else for Clojure code.
     https://groups.google.com/d/msg/clojurescript/iBY5HaQda4A/w1lAQi9_AwsJ"
  [then else]
  (if (cljs-env? &env) then else))


#_(defmacro trace [& args]
  `(if-cljs
   (.trace js/console ~(str *ns*) (pr-str ~@args))
   (.trace (LoggerFactory/getLogger ~(str *ns*)) (pr-str ~@args))))

(defmacro trace [& args]
  `(timbre/trace ~@args))

#_(defmacro debug [& args]
  `(if-cljs
   (when (.-debug js/console)
      (.debug js/console ~(str *ns*) (pr-str ~@args))
      (.log js/console ~(str *ns*) (pr-str ~@args)))
   ;; do not pr-str values eagerly (at least on the JVM)
   (when (.isDebugEnabled (LoggerFactory/getLogger ~(str *ns*)))
      (.debug (LoggerFactory/getLogger ~(str *ns*)) (pr-str ~@args)))))

(defmacro debug [& args]
  `(timbre/debug ~@args))

#_(defmacro info [& args]
  `(if-cljs
   (.info js/console ~(str *ns*) (pr-str ~@args))
   (when (.isInfoEnabled (LoggerFactory/getLogger ~(str *ns*)))
      (.info (LoggerFactory/getLogger ~(str *ns*)) (pr-str ~@args)))))

(defmacro info [& args]
  `(timbre/info ~@args))

#_(defmacro warn [& args]
  `(if-cljs
   (.warn js/console  ~(str *ns*) (pr-str ~@args))
   (.warn (LoggerFactory/getLogger ~(str *ns*)) (pr-str ~@args))))

(defmacro warn [& args]
  `(timbre/warn ~@args))

#_(defmacro error [& args]
  `(if-cljs
   (.error js/console  ~(str *ns*) (pr-str ~@args))
   (.error (LoggerFactory/getLogger ~(str *ns*)) (pr-str ~@args))))

(defmacro error [& args]
  `(timbre/error ~@args))
