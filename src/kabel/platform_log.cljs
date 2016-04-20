(ns kabel.platform-log
  "Logging, might move to own project.")


(defn trace [& args]
  (.trace js/console (apply pr-str args)))

;; aliases for console for now
(defn debug [& args]
  (when (.-debug js/console)
    (.debug js/console (apply pr-str args))
    (.log js/console (apply pr-str args))))

(defn info [& args]
  (.info js/console (apply pr-str args)))

(defn warn [& args]
  (.warn js/console (apply pr-str args)))

(defn error [& args]
  (.error js/console (apply pr-str args)))
