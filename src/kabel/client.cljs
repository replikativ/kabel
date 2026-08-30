(ns kabel.client
  (:require [kabel.binary :refer [to-binary from-binary]]
            [kabel.util :refer [on-node?]]
            [goog.net.WebSocket]
            [goog.Uri]
            [goog.events :as events]
            [goog.crypt :as crypt]
            [clojure.core.async :as async :refer (take! put! close! chan buffer timeout go) :include-macros true]
            [superv.async :refer [-error]]
            [replikativ.logging :as log]))

(def default-max-frame-bytes (* 5 1024 1024))
(def default-out-buffer-items 16)

(defn- encoded-bytes [value]
  (cond
    (string? value) (count (crypt/stringToUtf8ByteArray value))
    (and (exists? js/Blob) (instance? js/Blob value)) (.-size value)
    (some? (.-byteLength value)) (.-byteLength value)
    :else (.-length value)))

(defn- check-frame-size! [url direction value]
  (let [size (encoded-bytes value)]
    (when (> size default-max-frame-bytes)
      (throw (ex-info "WebSocket application message exceeds Kabel's limit"
                      {:type :kabel/frame-too-large :url url
                       :direction direction :bytes size
                       :max-bytes default-max-frame-bytes})))
    value))

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
   (let [channel (if (on-node?)
                   ;; Node.js websocket polyfill only supports arraybuffer
                   (goog.net.WebSocket. #js {:binaryType goog.net.WebSocket.BinaryType.ARRAY_BUFFER})
                   (goog.net.WebSocket. false))
         in-buffer (buffer 1024) ;; standard size
         in (chan in-buffer)
         out (chan (buffer default-out-buffer-items))
         opener (chan)
         host (.getDomain (goog.Uri. (.replace url "ws" "http")))]
     (log/info :connecting-to {:url url})
     (doto channel
       (events/listen goog.net.WebSocket.EventType.MESSAGE
                      (fn [evt]
                        (let [v (.. evt -message)]
                          (try
                            (check-frame-size! url :in v)
                            (if (string? v)
                              (when-not
                               (async/offer!
                                in {:kabel/serialization :string
                                    :kabel/payload v})
                                (throw (ex-info "Incoming Kabel queue is full"
                                                {:type :kabel/inbound-overloaded
                                                 :url url})))
                              (from-binary v
                                           #(when-not
                                             (async/offer!
                                              in (if (map? %)
                                                   (assoc % :kabel/host host)
                                                   %))
                                              (let [e
                                                    (ex-info
                                                     "Incoming Kabel queue is full"
                                                     {:type :kabel/inbound-overloaded
                                                      :url url})]
                                                (log/error :cannot-read-message
                                                           {:error e})
                                                (put! (-error S) e)
                                                (.close channel)))))
                            (catch js/Error e
                              (log/error :cannot-read-message {:error e})
                              (.close channel)
                              (close! opener)
                              (put! (-error S) e))))))
       (events/listen goog.net.WebSocket.EventType.CLOSED
                      (fn [evt]
                        (let [e (ex-info "Connection closed!" {:event evt})]
                          (log/info :connection-closed {:url url})
                          (close! in)
                          (close! out)
                          (put! (-error S) e)
                          (try (put! opener e) (catch js/Object e))
                          (.close channel)
                          (close! opener))))
       (events/listen goog.net.WebSocket.EventType.OPENED
                      (fn [evt] (put! opener [in out]) (close! opener)))
       (events/listen goog.net.WebSocket.EventType.ERROR
                      (fn [evt]
                        (let [e (ex-info "Connection error!" {:event evt})]
                          (log/error :websocket-error {:url url})
                          (close! out)
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
                   (go
                     (while (and (.isOpen channel)
                                 (pos? (.getBufferedAmount channel)))
                       (log/debug :output-blocked {:buffered-amount (.getBufferedAmount channel)})
                       (<! (timeout 100)))
                     (try
                       (let [payload (if (= (:kabel/serialization m) :string)
                                       (:kabel/payload m)
                                       (to-binary m))]
                         (check-frame-size! url :out payload)
                         (.send channel payload))
                       (catch js/Error e
                         (log/error :cannot-send-message {:error e})
                         (put! (-error S) e)
                         (close! out)
                         (.close channel)))

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
