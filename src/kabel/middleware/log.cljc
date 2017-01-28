(ns kabel.middleware.log
  "DEPRECATED Logging middleware."
  (:require [clojure.set :as set]
            #?(:clj [clojure.core.async :as async
                      :refer [<! >! chan go put! go-loop close!]]
               :cljs [cljs.core.async :as async :refer [<! >! chan put! close!]]))
  #?(:cljs (:require-macros [cljs.core.async.macros :refer (go go-loop alt!)])))


(defn logger
  "Appends messages of in and out to log-atom under [type :in/:out] to a vector.

  DEPRECATED: Use the generic function handler instead."
  [log-atom type [S peer [in out]]]
  (let [new-in (chan)
        new-out (chan)]
    (go-loop [i (<! in)]
      (if i
        (do
          (swap! log-atom update-in [type :in] (fnil conj []) i)
          (>! new-in i)
          (recur (<! in)))
        (close! new-in)))
    (go-loop [o (<! new-out)]
      (if o
        (do
          (swap! log-atom update-in [type :out] (fnil conj []) o)
          (>! out o)
          (recur (<! new-out)))
        (close! new-out)))
    [S peer [new-in new-out]]))
