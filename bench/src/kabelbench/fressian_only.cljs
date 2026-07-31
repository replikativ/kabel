(ns kabelbench.fressian-only
  (:require [kabel.middleware.fressian :as f]))
(defn ^:export main [] (js/console.log (pr-str (f/fressian identity))))
