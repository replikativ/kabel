(ns kabel.platform-log
  "Logging for Clojure."
  (:import [org.slf4j LoggerFactory]))


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


;; TODO do not pr-str values eagerly? It seems the logger expects already a String castable Object
(defmacro trace [& args]
  (if-cljs
   `(.trace js/console ~(str *ns*) (apply pr-str ~args))
   `(.trace (LoggerFactory/getLogger ~(str *ns*)) (pr-str ~@args))))

(defmacro debug [& args]
  (if-cljs
   `(when (.-debug js/console)
      (.debug js/console ~(str *ns*) (apply pr-str ~args))
      (.log js/console ~(str *ns*) (apply pr-str ~args)))
   `(.debug (LoggerFactory/getLogger ~(str *ns*)) (pr-str ~@args))))

(defmacro info [& args]
  (if-cljs
   `(.info js/console ~(str *ns*) (apply pr-str ~args))
   `(.info (LoggerFactory/getLogger ~(str *ns*)) (pr-str ~@args))))

(defmacro warn [& args]
  (if-cljs
   `(.warn js/console  ~(str *ns*) (apply pr-str ~args))
   `(.warn (LoggerFactory/getLogger ~(str *ns*)) (pr-str ~@args))))

(defmacro error [& args]
  (if-cljs
   `(.error js/console  ~(str *ns*) (apply pr-str ~args))
   `(.error (LoggerFactory/getLogger ~(str *ns*)) (pr-str ~@args))))
