(ns kabel.client
  (:require [kabel.binary :refer [to-binary from-binary]]
            [kabel.util :refer [on-node?]]
            [goog.net.WebSocket]
            [goog.Uri]
            [goog.events :as events]
            [cljs.core.async :as async :refer (take! put! close! chan buffer)]
            [superv.async :refer [-error]])
  (:require-macros [cljs.core.async.macros :refer [go go-loop]]
                   [kabel.platform-log :refer [debug info warn error]]))


(when (on-node?)
  (.log js/console "Patching global env for: W3C WebSocket API.")
  (set! js/WebSocket (.-w3cwebsocket (js/require "websocket"))))

(defn client-connect!
  "Connects to url. Puts [in out] channels on return channel when ready.
Only supports websocket at the moment, but is supposed to dispatch on
  protocol of url. read-opts is ignored on cljs for now, use the
  platform-wide reader setup."
  ([S url peer-id]
   (client-connect! S url peer-id (atom {}) (atom {})))
  ([S url peer-id read-handlers write-handlers]
   (let [channel (goog.net.WebSocket. false)
         in-buffer (buffer 1024) ;; standard size
         in (chan in-buffer)
         out (chan)
         opener (chan)
         host (.getDomain (goog.Uri. (.replace url "ws" "http")))]
     (info {:event :connecting-to :url url})
     (doto channel
       (events/listen goog.net.WebSocket.EventType.MESSAGE
                      (fn [evt]
                        (let [v (.. evt -message)]
                          (try
                            (when (> (count in-buffer) 100)
                              (.close channel)
                              (throw (ex-info
                                      (str "incoming buffer for " url
                                           " too full:" (count in-buffer))
                                      {:url url
                                       :count (count in-buffer)})))
                            (if (string? v)
                              (put! in {:kabel/serialization :string
                                        :kabel/payload v})
                              (from-binary v
                                           #(put! in (if (map? %)
                                                       (assoc % :kabel/host host)
                                                       %))))
                            (catch js/Error e
                              (error {:event :cannot-read-message :error e})
                              (.close channel)
                              (close! opener)
                              (put! (-error S) e))))))
       (events/listen goog.net.WebSocket.EventType.CLOSED
                      (fn [evt]
                        (let [e (ex-info "Connection closed!" {:event evt})]
                          (info {:event :connection-closed :url url})
                          (close! in)
                          (put! (-error S) e)
                          (try (put! opener e) (catch js/Object e))
                          (.close channel)
                          (close! opener))))
       (events/listen goog.net.WebSocket.EventType.OPENED
                      (fn [evt] (put! opener [in out]) (close! opener)))
       (events/listen goog.net.WebSocket.EventType.ERROR
                      (fn [evt]
                        (let [e (ex-info "Connection error!" {:event evt})]
                          (error {:event :websocket-error :url url})
                          (put! (-error S) e) ;; TODO needs happen first for replikativ.connect
                          (try (put! opener e) (catch js/Object e))
                          (close! opener))))
       (try
         (.open channel url) ;; throws on connection failure? doesn't catch?
         (catch js/Object e
           (let [e (ex-info  "Connection failed!" {:event :connection-failed
                                                   :error e})]
             (put! (-error S) e)
             (put! opener e)
             (close! opener)))))
     ((fn sender []
        (take! out
               (fn [m]
                 (if m
                   (do
                     (try
                       (if (= (:kabel/serialization m) :string)
                         (.send channel (:kabel/payload m))
                         (.send channel (to-binary m)))
                       (catch js/Error e
                         (error {:event :cannot-send-transit-message :error e})
                         (put! (-error S) e)))

                     (sender))
                   (.close channel))))))
     opener)))


(comment
  (client-connect! "ws://127.0.0.1:9090"))


;; fire up repl
#_(do
    (ns dev)
    (def repl-env (reset! cemerick.austin.repls/browser-repl-env
                         (cemerick.austin/repl-env)))
    (cemerick.austin.repls/cljs-repl repl-env))
