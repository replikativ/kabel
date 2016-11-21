(ns kabel.platform-log
  "Logging, might move to own project.")


(defn trace [& args]
  (.trace js/console (apply pr-str args) args))

;; aliases for console for now
(defn debug [& args]
  (when (.-debug js/console)
    (.debug js/console (apply pr-str args) args)
    (.log js/console (apply pr-str args) args)))

(defn info [& args]
  (.info js/console (apply pr-str args) args))

(defn warn [& args]
  (.warn js/console (apply pr-str args) args))

(defn error [& args]
  (.error js/console (apply pr-str args) args))
