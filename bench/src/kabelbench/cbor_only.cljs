(ns kabelbench.cbor-only
  (:require [kabel.middleware.cbor :as c]))
(defn ^:export main [] (js/console.log (pr-str (c/cbor identity))))
