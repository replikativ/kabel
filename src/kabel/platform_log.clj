(ns kabel.platform-log
  "Logging for Clojure."
  (:import [org.slf4j LoggerFactory]))

(defmacro trace [& args]
  `(.trace (LoggerFactory/getLogger ~(str *ns*)) (pr-str ~@args)))

(defmacro debug [& args]
  `(.debug (LoggerFactory/getLogger ~(str *ns*)) (pr-str ~@args)))

(defmacro info [& args]
  `(.info (LoggerFactory/getLogger ~(str *ns*)) (pr-str ~@args)))

(defmacro warn [& args]
  `(.warn (LoggerFactory/getLogger ~(str *ns*)) (pr-str ~@args)))

(defmacro error [& args]
  `(.error (LoggerFactory/getLogger ~(str *ns*)) (pr-str ~@args)))
