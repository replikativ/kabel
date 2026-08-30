(ns kabel.metrics
  "Composable transport metrics for Kabel.

   `messages` belongs inside the serialization middleware, where it sees
   logical messages and their `:type`. `wire` belongs outside serialization,
   where it sees the encoded envelope and can count the bytes sent on the
   WebSocket. Neither is installed globally: a peer that does not compose
   these middlewares pays no channel, allocation, or registry-update cost.

   Labels deliberately exclude peer ids, URLs and pub/sub topics. Those are
   commonly UUIDs or user input and would create an unbounded Prometheus
   series set."
  (:require [clojure.core.async :refer [chan close!]]
            #?(:cljs [hasch.platform :refer [utf8]])
            [replikativ.metrics :as metrics]
            #?(:clj [superv.async :refer [<? >? go-loop-super]]))
  #?(:cljs (:require-macros [superv.async :refer [<? >? go-loop-super]])))

(def descriptions
  {:kabel_messages_total
   {:type :counter
    :help "Logical Kabel messages, by direction and message type."}
   :kabel_wire_bytes_total
   {:type :counter
    :help "WebSocket application bytes, including Kabel's binary frame header, by direction and serialization."}
   :kabel_peer_connection_events_total
   {:type :counter
    :help "Kabel peer connection lifecycle events, by client/server side and event."}
   :kabel_pubsub_subscription_events_total
   {:type :counter
    :help "Successful Kabel pub/sub subscription lifecycle events, by client/server side and event."}})

(defn describe!
  "Install Kabel's metric descriptions after a registry reset."
  []
  (doseq [[metric description] descriptions]
    (metrics/describe! metric description))
  nil)

(describe!)

(defn- label [x]
  (cond
    (keyword? x) (if-let [n (namespace x)] (str n "/" (name x)) (name x))
    (nil? x) "untyped"
    :else (str x)))

(defn- side [peer]
  (if (seq (:addresses @peer)) "server" "client"))

(defn- direction-label [direction]
  (name direction))

(defn message!
  "Record one logical message. Public for non-channel transports."
  [direction message]
  (metrics/inc! :kabel_messages_total
                {:direction (direction-label direction)
                 :type (label (when (map? message) (:type message)))})
  nil)

(defn connection-event!
  "Record `:connect`, `:reconnect`, or `:disconnect` for one peer side."
  [side event]
  (metrics/inc! :kabel_peer_connection_events_total
                {:side (label side) :event (label event)})
  nil)

(defn subscription-event!
  "Record a successful subscription lifecycle event without a topic label."
  [side event n]
  (when (pos? n)
    (metrics/inc! :kabel_pubsub_subscription_events_total
                  {:side (label side) :event (label event)} n))
  nil)

(defn- payload-bytes [payload]
  #?(:clj
     (if (string? payload)
       (alength (.getBytes ^String payload java.nio.charset.StandardCharsets/UTF_8))
       (alength ^bytes payload))
     :cljs
     (if (string? payload)
       (count (utf8 payload))
       (or (.-byteLength payload) (.-size payload) (.-length payload) 0))))

(defn wire-size
  "Application bytes in the WebSocket message represented by `message`.

   Text messages are their UTF-8 length. Binary messages include Kabel's
   four-byte serialization id followed by the codec payload. An unwrapped
   value takes the same `pr-str` fallback path as `kabel.binary/to-binary`."
  [message]
  (if (= :string (:kabel/serialization message))
    (payload-bytes (:kabel/payload message))
    (+ 4 (if-let [payload (:kabel/payload message)]
           (payload-bytes payload)
           (payload-bytes (pr-str message))))))

(defn wire!
  "Record the encoded size of one WebSocket application message."
  [direction message]
  (metrics/inc! :kabel_wire_bytes_total
                {:direction (direction-label direction)
                 :serialization (label (or (:kabel/serialization message) :pr-str))}
                (wire-size message))
  nil)

(defn messages
  "Middleware for logical message and connection lifecycle counters.

   Compose this with application middleware, inside the codec. A second
   connection through the same client peer is a reconnect; accepted server
   sockets are always connects."
  [[S peer [in out]]]
  (let [new-in (chan)
        new-out (chan)
        peer-side (side peer)
        connects (get-in (swap! peer update-in [::state :connects] (fnil inc 0))
                         [::state :connects])]
    (connection-event! peer-side (if (and (= "client" peer-side) (> connects 1))
                                   :reconnect :connect))
    (go-loop-super S [message (<? S in)]
                   (if message
                     (do (message! :in message)
                         (>? S new-in message)
                         (recur (<? S in)))
                     (do (connection-event! peer-side :disconnect)
                         (close! new-in))))
    (go-loop-super S [message (<? S new-out)]
                   (if message
                     (do (message! :out message)
                         (>? S out message)
                         (recur (<? S new-out)))
                     (close! out)))
    [S peer [new-in new-out]]))

(defn wire
  "Middleware for encoded byte counters. Compose it outside the codec."
  [[S peer [in out]]]
  (let [new-in (chan)
        new-out (chan)]
    (go-loop-super S [message (<? S in)]
                   (if message
                     (do (wire! :in message)
                         (>? S new-in message)
                         (recur (<? S in)))
                     (close! new-in)))
    (go-loop-super S [message (<? S new-out)]
                   (if message
                     (do (wire! :out message)
                         (>? S out message)
                         (recur (<? S new-out)))
                     (close! out)))
    [S peer [new-in new-out]]))
