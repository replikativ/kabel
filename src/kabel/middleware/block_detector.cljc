(ns kabel.middleware.block-detector
  "Block detection middleware for replikativ."
  (:require [kabel.platform-log :refer [debug info warn error]]
            [clojure.set :as set]
            #?(:clj [clojure.core.async :as async
                     :refer [<! >! >!! <!! timeout chan alt! go put!
                             go-loop pub sub unsub close!]]
               :cljs [cljs.core.async :as async
                     :refer [<! >! timeout chan put! pub sub unsub close!]]))
  #?(:cljs (:require-macros [cljs.core.async.macros :refer (go go-loop alt!)])))



(defn block-detector [type [peer [in out]]]
  "Warns when either in or out is blocked for longer than 5 seconds and retries."
  (let [new-in (chan)
        new-out (chan)]
    (go-loop [i (<! in)]
      (if i
        (alt! [[new-in i]]
              (recur (<! in))

              (timeout 5000)
              (do (warn {:event :input-channel-blocked :message i})
                  (recur i)))
        (close! new-in)))
    (go-loop [o (<! new-out)]
      (if o
        (alt! [[out o]]
              (recur (<! new-out))

              (timeout 5000)
              (do (warn {:event :output-channel-blocked :message o})
                  (recur o)))
        (close! new-out)))
    [peer [new-in new-out]]))
