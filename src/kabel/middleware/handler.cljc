(ns kabel.middleware.handler
  "Generic callback handler middleware."
  (:require [kabel.platform-log :refer [debug info warn error]]
            [clojure.set :as set]
            #?(:clj [clojure.core.async :as async
                      :refer [<! >! chan go put! go-loop close!]]
               :cljs [cljs.core.async :as async :refer [<! >! chan put! close!]]))
  #?(:cljs (:require-macros [cljs.core.async.macros :refer (go go-loop alt!)])))


(defn handler
  "Applies given callback functions to messages on [in out] channels and passes through the return value of the callback."
  [cb-in cb-out [peer [in out]]]
  (let [new-in (chan)
        new-out (chan)]
    (go-loop [i (<! in)]
      (if i
        (do
          (when-let [i (cb-in i)])
          (>! new-in i)
          (recur (<! in)))
        (close! new-in)))
    (go-loop [o (<! new-out)]
      (if o
        (do
          (when-let [o (cb-out o)]
            (>! out o))
          (recur (<! new-out)))
        (close! new-out)))
    [peer [new-in new-out]]))
