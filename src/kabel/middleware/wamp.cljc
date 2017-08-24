(ns kabel.middleware.wamp
  "This is an experimental wamp client middleware."
  (:require
   #?(:clj [clojure.core.async :as async
            :refer [>! timeout chan put! pub sub unsub close!]]
      :cljs [cljs.core.async :as async
             :refer [>! timeout chan put! pub sub unsub close!]])
   #?(:clj [kabel.platform-log :refer [debug]])
   [superv.async :refer [<?? go-try S go-loop-try <? >? put?]])
  #?(:cljs (:require-macros [superv.async :refer [go-try <? >? put? go-loop-try S]]
                            [kabel.platform-log :refer [debug]])))

;; https://tools.ietf.org/html/draft-oberstet-hybi-tavendo-wamp-02
;; curl examples https://github.com/crossbario/crossbar-examples/tree/master/longpoll_curl

;; TODO
;; To extend support for wamp we need to find the proper API abstractions
;; to interact with the low-level protocol. The client is currently open to
;; extension and we might also just provide raw channel interfaces to the
;; different message types.
;; client:
;; - handle connection aborts (and reconnects)
;; - allow authentication
;; - support publications
;; router:
;; - implement routing

(def type->int {:HELLO 1,
                :WELCOME 2,
                :ABORT 3,
                :CHALLENGE 4,
                :AUTHENTICATE 5,
                :GOODBYE 6,
                :HEARTBEAT 7,
                :ERROR 8,
                :PUBLISH 16,
                :PUBLISHED 17,
                :SUBSCRIBE 32,
                :SUBSCRIBED 33,
                :UNSUBSCRIBE 34,
                :UNSUBSCRIBED 35,
                :EVENT 36,
                :CALL 48,
                :CANCEL 49,
                :RESULT 50,
                :REGISTER 64,
                :REGISTERED 65,
                :UNREGISTER 66,
                :UNREGISTERED 67,
                :INVOCATION 68,
                :INTERRUPT 69,
                :YIELD 70})


(def int->type (into {} (map (fn [[k v]] [v k])) type->int))

(defn conv-msg [m]
  (update (vec m) 0 int->type))

(defn wamp-middleware [subs realm event-fn [S peer [in out]]]
  (let [new-in (chan)
        new-out (chan)
        p (async/pub in (comp int->type first))
        welcome-ch (chan)
        subed-ch (chan)
        subs-map (atom {})
        event-ch (chan 10 (map #(update (conv-msg %) 1 @subs-map)))
        _ (async/sub p :WELCOME welcome-ch)
        _ (async/sub p :SUBSCRIBED subed-ch)
        _ (async/sub p :EVENT event-ch)]
    (go-try S
            (>? S out [(type->int :HELLO)
                       realm
                       {:roles {:subscriber {}
                                :publisher {}}}])
            (let [w (<? S welcome-ch)]
              (debug {:event ::welcome
                      :message (conv-msg w)}))
            (doseq [s subs]
              (>? S out [(type->int :SUBSCRIBE) 1 {} s])
              (let [[_ _ id] (<? S subed-ch)]
                (swap! subs-map assoc id s)))
            (debug {:event ::subscribed
                    :subs subs}))
    (go-loop-try S [e (<? S event-ch)]
                 (when e
                   (event-fn e)
                   (recur (<? S event-ch))))
    [S peer [new-in new-out]]))

