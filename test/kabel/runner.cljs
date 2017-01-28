(ns kabel.runner
  (:require [cljs.test :refer-macros [run-all-tests]]))

(enable-console-print!)

(defn ^:export run []
  (run-all-tests #_"kabel.transit-test"))
