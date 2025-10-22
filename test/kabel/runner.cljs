(ns kabel.runner
  (:require [shadow.test.browser :as browser]))

(defn start []
  (browser/init))

(defn stop []
  ;; called before re-running tests
  )

(defn ^:export init []
  (start))
