(ns kabel.pubsub.protocol
  "Protocol definitions for kabel.pubsub sync strategies."
  (:require #?(:clj [clojure.core.async :as async :refer [chan put! close!]]
               :cljs [clojure.core.async :as async :refer [chan put! close!]])))

;; =============================================================================
;; Sync Strategy Protocol
;; =============================================================================

(defprotocol PSyncStrategy
  "Pluggable sync behavior for pubsub topics.

   Implementations control:
   - What client state to send during subscription
   - What items to send during handshake (server -> client)
   - How to apply received items (client-side)
   - How to handle incremental publishes

   Examples:
   - StoreSyncStrategy: timestamp-based diff, push stale keys
   - CRDTSyncStrategy: CRDT handshake, downstream ops
   - PubSubOnlyStrategy: no sync, just pub/sub"

  (-init-client-state [this]
    "Return client's initial state to include in subscribe message.
     Called on client before subscribing.

     Returns: core.async channel yielding any EDN value representing
              client's current state for this topic. nil if no client state needed.")

  (-handshake-items [this client-state]
    "Server-side: Generate items to send during initial sync.

     Parameters:
     - client-state: The state client sent in subscribe message

     Returns: core.async channel that yields handshake items.
              Close channel when done. Items can be any EDN value.
              Core pubsub handles batching and flow control.")

  (-apply-handshake-item [this item]
    "Client-side: Apply a received handshake item.

     Parameters:
     - item: The handshake item received from server

     Returns: core.async channel yielding {:ok true} or {:error <ex>}")

  (-apply-publish [this payload]
    "Apply an incoming publish payload (both client and server).

     Parameters:
     - payload: The payload from :pubsub/publish message

     Returns: core.async channel yielding {:ok <result>} or {:error <ex>}
              Result can include strategy-specific data."))

;; =============================================================================
;; Message Helpers
;; =============================================================================

(defn subscribe-msg
  "Create a subscribe message."
  [id topics client-states]
  {:type :pubsub/subscribe
   :id id
   :topics topics
   :client-states client-states})

(defn subscribe-ack-msg
  "Create a subscribe acknowledgment message."
  [id topics]
  {:type :pubsub/subscribe-ack
   :id id
   :topics topics})

(defn unsubscribe-msg
  "Create an unsubscribe message."
  [topics]
  {:type :pubsub/unsubscribe
   :topics topics})

(defn handshake-data-msg
  "Create a handshake data message."
  [topic data]
  {:type :pubsub/handshake-data
   :topic topic
   :data data})

(defn handshake-batch-complete-msg
  "Create a batch complete message."
  [topic batch-idx]
  {:type :pubsub/handshake-batch-complete
   :topic topic
   :batch-idx batch-idx})

(defn handshake-ack-msg
  "Create a handshake acknowledgment message."
  [topic batch-idx]
  {:type :pubsub/handshake-ack
   :topic topic
   :batch-idx batch-idx})

(defn handshake-complete-msg
  "Create a handshake complete message."
  [topic]
  {:type :pubsub/handshake-complete
   :topic topic})

(defn publish-msg
  "Create a publish message."
  [topic payload]
  {:type :pubsub/publish
   :topic topic
   :payload payload})

(defn error-msg
  "Create an error message."
  [topic error]
  {:type :pubsub/error
   :topic topic
   :error error})

;; =============================================================================
;; Message Type Predicates
;; =============================================================================

(defn pubsub-msg?
  "Check if message is a pubsub message."
  [msg]
  (and (map? msg)
       (keyword? (:type msg))
       (= "pubsub" (namespace (:type msg)))))

(defn subscribe-msg?
  "Check if message is a subscribe request."
  [msg]
  (= :pubsub/subscribe (:type msg)))

(defn subscribe-ack-msg?
  "Check if message is a subscribe acknowledgment."
  [msg]
  (= :pubsub/subscribe-ack (:type msg)))

(defn unsubscribe-msg?
  "Check if message is an unsubscribe request."
  [msg]
  (= :pubsub/unsubscribe (:type msg)))

(defn handshake-data-msg?
  "Check if message is handshake data."
  [msg]
  (= :pubsub/handshake-data (:type msg)))

(defn handshake-batch-complete-msg?
  "Check if message is batch complete."
  [msg]
  (= :pubsub/handshake-batch-complete (:type msg)))

(defn handshake-ack-msg?
  "Check if message is a handshake ack."
  [msg]
  (= :pubsub/handshake-ack (:type msg)))

(defn handshake-complete-msg?
  "Check if message is handshake complete."
  [msg]
  (= :pubsub/handshake-complete (:type msg)))

(defn publish-msg?
  "Check if message is a publish."
  [msg]
  (= :pubsub/publish (:type msg)))

;; =============================================================================
;; Built-in Strategy: PubSubOnly
;; =============================================================================

(defrecord PubSubOnlyStrategy [on-publish-fn]
  PSyncStrategy

  (-init-client-state [_]
    ;; No client state - return closed channel (nil value indicates no state)
    (let [ch (chan 1)]
      (close! ch)
      ch))

  (-handshake-items [_ _]
    ;; No handshake - return closed channel
    (let [ch (chan)]
      (close! ch)
      ch))

  (-apply-handshake-item [_ _]
    (let [ch (chan 1)]
      (put! ch {:ok true})
      (close! ch)
      ch))

  (-apply-publish [_ payload]
    (let [ch (chan 1)]
      (try
        (when on-publish-fn
          (on-publish-fn payload))
        (put! ch {:ok true})
        (catch #?(:clj Exception :cljs js/Error) e
          (put! ch {:error e})))
      (close! ch)
      ch)))

(defn pub-sub-only-strategy
  "Create a strategy for pure pub/sub with no initial sync.

   Parameters:
   - on-publish-fn: (fn [payload]) called when publish received"
  [on-publish-fn]
  (->PubSubOnlyStrategy on-publish-fn))
