(ns kabel.client
  (:require [kabel.platform-log :refer [debug info warn error]]
            [cognitect.transit :as transit]
            [incognito.transit :refer [incognito-read-handler incognito-write-handler]]
            [goog.net.WebSocket]
            [goog.Uri]
            [goog.events :as events]
            [cljs.core.async :as async :refer (take! put! close! chan)]
            [superv.async :refer [-error]])
  (:require-macros [cljs.core.async.macros :refer [go go-loop]]))


(defn on-node? []
  (and (exists? js/process)
       (exists? js/process.versions)
       (exists? js/process.versions.node)
       true))



(defn client-connect!
  "Connects to url. Puts [in out] channels on return channel when ready.
Only supports websocket at the moment, but is supposed to dispatch on
  protocol of url. read-opts is ignored on cljs for now, use the
  platform-wide reader setup."
  ([S url peer-id]
   (client-connect! S url peer-id (atom {}) (atom {})))
  ([S url peer-id read-handlers write-handlers]
   (when (on-node?)
     (.log js/console "Setting global W3C WebSocket API to 'websocket' package.")
     (set! js/WebSocket (.-w3cwebsocket (js/require "websocket"))))
   (let [channel (goog.net.WebSocket. false)
         in (chan)
         out (chan)
         opener (chan)
         host (.getDomain (goog.Uri. (.replace url "ws" "http")))]
     (info {:event :connecting-to :url url})
     (doto channel
       (events/listen goog.net.WebSocket.EventType.MESSAGE
                      (fn [evt]
                        (try
                          (let [reader (transit/reader :json {:handlers ;; remove if uuid problem is gone
                                                              {"u" (fn [v] (cljs.core/uuid v))
                                                               "incognito" (incognito-read-handler read-handlers)}})]
                            (if-not (on-node?)
                              ;; Browser
                              (let [fr (js/FileReader.)]
                                (set! (.-onload fr) #(let [res (js/String. (.. % -target -result))]
                                                       #_(debug "Received message: " res)
                                                       (put! in (assoc (transit/read reader res) :host host))))

                                (.readAsText fr (.-message evt)))
                              ;; nodejs
                              (let [s  (js/String.fromCharCode.apply
                                        nil
                                        (js/Uint8Array. (.. evt -message)))]
                                (put! in (assoc (transit/read reader s) :host host)))))
                          (catch js/Error e
                            (error {:event :cannot-read-transit-message :error e})
                            (put! (-error S) e)))))
       (events/listen goog.net.WebSocket.EventType.CLOSED
                      (fn [evt]
                        (let [e (ex-info "Connection closed!" {:event evt})]
                          (info {:event :connection-closed :closed-event evt})
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
                          (error {:event :websocket-error :error evt})
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
                       (let [i-write-handler (incognito-write-handler write-handlers)
                             writer (transit/writer
                                     :json
                                     {:handlers {"default" i-write-handler}})
                             to-send (transit/write writer (assoc m :sender peer-id))]
                         (if-not (on-node?)
                           (.send channel (js/Blob. #js [to-send])) ;; Browser
                           (.send channel (js/Buffer. to-send)) ;; NodeJS
                           ))
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
