(ns kabel.pubsub
  "Topic-based publish/subscribe layer for kabel.

   Provides:
   - Topic registration and subscription management
   - Filtered publishing to subscribers
   - Pluggable sync strategies for initial state transfer
   - Batched handshake with backpressure

   Usage:
   ```clojure
   ;; Server: Register a topic
   (register-topic! peer :my-topic {:strategy my-strategy})

   ;; Client: Subscribe to topics
   (subscribe! peer #{:my-topic}
     {:strategies {:my-topic my-strategy}
      :on-publish (fn [topic payload] ...)})

   ;; Either side: Publish
   (publish! peer :my-topic {:data 123})
   ```"
  (:require [kabel.pubsub.protocol :as proto]
            #?(:clj [kabel.platform-log :refer [debug info warn error]])
            #?(:clj [superv.async :refer [<? >? go-try go-loop-try go-loop-super]])
            #?(:clj [clojure.core.async :as async
                     :refer [>! <! chan put! close! timeout pub sub unsub alts!]]
               :cljs [clojure.core.async :as async
                      :refer [>! <! chan put! close! timeout alts!] :include-macros true]))
  #?(:cljs (:require-macros [kabel.platform-log :refer [debug info warn error]]
                            [superv.async :refer [<? >? go-try go-loop-try go-loop-super]])))

;; =============================================================================
;; Configuration
;; =============================================================================

(def default-opts
  {:batch-size 20
   :batch-timeout-ms 30000})

;; =============================================================================
;; State Management
;; =============================================================================

(defn- get-pubsub-state
  "Get pubsub state from peer, initializing if needed."
  [peer]
  (or (:pubsub @peer)
      {:topics {}
       :subscriptions {}}))

(defn- update-pubsub-state!
  "Update pubsub state in peer."
  [peer f & args]
  (swap! peer update :pubsub #(apply f (or % {:topics {} :subscriptions {}}) args)))

;; =============================================================================
;; Server-Side API
;; =============================================================================

(defn register-topic!
  "Register a topic on the peer for subscription.

   Parameters:
   - peer: The kabel peer atom
   - topic: Any EDN value identifying the topic
   - opts: Map with:
     - :strategy - PSyncStrategy implementation (required)
     - :batch-size - Items per handshake batch (default 20)
     - :batch-timeout-ms - Batch ack timeout (default 30000)

   Returns: topic"
  [peer topic {:keys [strategy] :as opts}]
  {:pre [(some? strategy)]}
  (info {:event :pubsub/register-topic :topic topic})
  (update-pubsub-state! peer assoc-in [:topics topic]
                        {:strategy strategy
                         :subscribers #{}
                         :opts (merge default-opts opts)})
  topic)

(defn unregister-topic!
  "Unregister a topic. Removes all subscriptions.

   Returns: topic"
  [peer topic]
  (info {:event :pubsub/unregister-topic :topic topic})
  (update-pubsub-state! peer update :topics dissoc topic)
  topic)

(defn get-topic-config
  "Get configuration for a topic."
  [peer topic]
  (get-in (get-pubsub-state peer) [:topics topic]))

(defn topic-registered?
  "Check if a topic is registered."
  [peer topic]
  (some? (get-topic-config peer topic)))

(defn get-subscribers
  "Get set of transports subscribed to a topic."
  [peer topic]
  (get-in (get-pubsub-state peer) [:topics topic :subscribers] #{}))

(defn- add-subscriber!
  "Add a subscriber transport to a topic."
  [peer topic transport]
  (update-pubsub-state! peer update-in [:topics topic :subscribers]
                        (fnil conj #{}) transport))

(defn- remove-subscriber!
  "Remove a subscriber transport from a topic."
  [peer topic transport]
  (update-pubsub-state! peer update-in [:topics topic :subscribers]
                        disj transport))

;; =============================================================================
;; Publishing
;; =============================================================================

(defn publish!
  "Publish a message to all subscribers of a topic.

   For servers: Sends to all local subscribers.
   For clients: Sends to server (which will forward to other subscribers).

   Parameters:
   - peer: The kabel peer atom
   - topic: The topic to publish to
   - payload: Any EDN value

   Returns: channel yielding {:ok true :sent-count N} or {:error ...}"
  [peer topic payload]
  (let [{{S :supervisor} :volatile} @peer
        pubsub-state (get-pubsub-state peer)
        out (:out pubsub-state)
        subscribers (get-subscribers peer topic)
        is-client? (and out (empty? subscribers))]
    (go-try S
      (let [msg (proto/publish-msg topic payload)
            sent (atom 0)]
        (if is-client?
          ;; Client: send to server
          (do
            (debug {:event :pubsub/publish-to-server :topic topic})
            (>? S out msg)
            (swap! sent inc))
          ;; Server: send to local subscribers
          (doseq [transport subscribers]
            (try
              (>? S transport msg)
              (swap! sent inc)
              (catch #?(:clj Exception :cljs js/Error) e
                (warn {:event :pubsub/publish-failed
                       :topic topic
                       :error (str e)})))))
        {:ok true :sent-count @sent}))))

;; =============================================================================
;; Handshake Logic
;; =============================================================================

(defn- send-handshake!
  "Send handshake items with batching and flow control.

   Returns channel yielding {:ok true} or {:error ...}"
  [S out topic handshake-ch opts pending-acks]
  (let [{:keys [batch-size batch-timeout-ms]} opts]
    (go-try S
      (loop [batch-idx 0]
        ;; Collect up to batch-size items
        (let [items (loop [items []
                          remaining batch-size]
                     (if (zero? remaining)
                       items
                       (let [[item ch] (alts! [handshake-ch (timeout 100)])]
                         (cond
                           ;; Got an item
                           (and (= ch handshake-ch) (some? item))
                           (recur (conj items item) (dec remaining))

                           ;; Channel closed
                           (and (= ch handshake-ch) (nil? item))
                           items

                           ;; Timeout - return what we have
                           :else
                           items))))]
          (if (empty? items)
            ;; Done - send complete
            (do
              (debug {:event :pubsub/handshake-complete :topic topic})
              (>? S out (proto/handshake-complete-msg topic))
              {:ok true})

            ;; Send batch
            (do
              (debug {:event :pubsub/sending-batch
                      :topic topic
                      :batch-idx batch-idx
                      :item-count (count items)})
              ;; Send each item
              (doseq [item items]
                (>? S out (proto/handshake-data-msg topic item)))

              ;; Send batch-complete
              (>? S out (proto/handshake-batch-complete-msg topic batch-idx))

              ;; Create promise for ack
              (let [ack-ch (chan 1)]
                (swap! pending-acks assoc-in [topic batch-idx] ack-ch)

                ;; Wait for ack or timeout
                (let [[result ch] (alts! [ack-ch (timeout batch-timeout-ms)])]
                  (swap! pending-acks update topic dissoc batch-idx)
                  (if (= ch ack-ch)
                    (recur (inc batch-idx))
                    {:error (ex-info "Handshake batch ack timeout"
                                     {:topic topic :batch-idx batch-idx})}))))))))))

(defn- handle-subscription!
  "Handle a subscription request from a client.

   Returns channel yielding {:ok topics} or {:error ...}"
  [S peer out msg pending-acks]
  (go-try S
    (let [{:keys [id topics client-states]} msg
          successful-topics (atom #{})]
      (debug {:event :pubsub/handle-subscription
              :topics topics
              :msg-id id})

      ;; Process each topic
      (doseq [topic topics]
        (if-let [topic-config (get-topic-config peer topic)]
          (let [{:keys [strategy opts]} topic-config
                client-state (get client-states topic)
                handshake-ch (proto/-handshake-items strategy client-state)]

            ;; Add as subscriber before handshake
            (add-subscriber! peer topic out)

            ;; Send handshake
            (let [result (<? S (send-handshake! S out topic handshake-ch opts pending-acks))]
              (if (:ok result)
                (do
                  (info {:event :pubsub/subscription-complete :topic topic})
                  (swap! successful-topics conj topic))
                (do
                  (warn {:event :pubsub/subscription-failed
                         :topic topic
                         :error (:error result)})
                  (remove-subscriber! peer topic out)))))

          ;; Topic not registered
          (do
            (warn {:event :pubsub/topic-not-found :topic topic})
            (>? S out (proto/error-msg topic "Topic not registered")))))

      ;; Send subscribe-ack with successful topics
      (>? S out (proto/subscribe-ack-msg id @successful-topics))
      {:ok @successful-topics})))

(defn- handle-unsubscription!
  "Handle an unsubscribe request."
  [S peer out msg]
  (go-try S
    (let [{:keys [topics]} msg]
      (debug {:event :pubsub/handle-unsubscription :topics topics})
      (doseq [topic topics]
        (remove-subscriber! peer topic out))
      {:ok true})))

;; =============================================================================
;; Client-Side API
;; =============================================================================

(defn- init-subscription-state!
  "Initialize client-side subscription state."
  [peer topic strategy]
  (update-pubsub-state! peer assoc-in [:subscriptions topic]
                        {:strategy strategy
                         :handshake-complete? false}))

(defn- mark-handshake-complete!
  "Mark handshake as complete for a topic."
  [peer topic]
  (update-pubsub-state! peer assoc-in [:subscriptions topic :handshake-complete?] true))

(defn subscribe!
  "Subscribe to topics on a remote peer.

   Parameters:
   - peer: The kabel client peer atom
   - topics: Set of topics to subscribe to
   - opts: Map with:
     - :strategies - {topic -> PSyncStrategy} for handling updates (required)
     - :on-publish - (fn [topic payload]) callback for publishes (optional)
     - :on-handshake-complete - (fn [topic]) called per topic (optional)

   Returns channel yielding {:ok topics} when done, or {:error ...}"
  [peer topics {:keys [strategies on-publish on-handshake-complete] :as opts}]
  {:pre [(every? #(contains? strategies %) topics)]}
  (let [{{S :supervisor} :volatile} @peer
        out (get-in (get-pubsub-state peer) [:out])
        id #?(:clj (java.util.UUID/randomUUID)
              :cljs (random-uuid))]
    (go-try S
      ;; Initialize subscription state
      (doseq [topic topics]
        (init-subscription-state! peer topic (get strategies topic)))

      ;; Build client-states (await async init)
      (let [client-states (loop [topics-seq (seq topics)
                                 states {}]
                            (if-let [topic (first topics-seq)]
                              (let [state (<? S (proto/-init-client-state (get strategies topic)))]
                                (recur (rest topics-seq) (assoc states topic state)))
                              states))]
        ;; Send subscribe request
        (debug {:event :pubsub/sending-subscribe :topics topics :id id})
        (>? S out (proto/subscribe-msg id topics client-states))

        ;; Return - actual handling happens in middleware
        {:ok topics :id id}))))

(defn unsubscribe!
  "Unsubscribe from topics.

   Returns channel yielding {:ok true}"
  [peer topics]
  (let [{{S :supervisor} :volatile} @peer
        out (get-in (get-pubsub-state peer) [:out])]
    (go-try S
      (doseq [topic topics]
        (update-pubsub-state! peer update :subscriptions dissoc topic))
      (>? S out (proto/unsubscribe-msg topics))
      {:ok true})))

;; =============================================================================
;; Middleware
;; =============================================================================

(defn- msg-type-dispatch
  "Dispatch function for routing messages."
  [{:keys [type]}]
  (cond
    (proto/pubsub-msg? {:type type}) type
    :else :unrelated))

(defn pubsub-middleware
  "Kabel middleware that handles pubsub protocol messages.

   Server-side:
   - Handles :pubsub/subscribe, :pubsub/unsubscribe
   - Handles :pubsub/handshake-ack
   - Dispatches :pubsub/publish to strategy

   Client-side:
   - Handles :pubsub/subscribe-ack
   - Handles :pubsub/handshake-data, :pubsub/handshake-batch-complete, :pubsub/handshake-complete
   - Dispatches :pubsub/publish to strategy

   Non-pubsub messages pass through unchanged.

   Stores :pubsub/out channel in peer state for client-side subscribe/unsubscribe."
  [opts]
  (fn [[S peer [in out]]]
    (let [pass-in (chan)
          pass-out (chan)
          p (pub in msg-type-dispatch)

          ;; Per-connection state
          pending-acks (atom {})  ;; {topic -> {batch-idx -> chan}}
          handshake-items (atom {})  ;; {topic -> [items...]} for client

          ;; Subscribe to pubsub message types
          ;; Use buffers to prevent race conditions between related messages
          subscribe-ch (chan 10)
          subscribe-ack-ch (chan 10)
          unsubscribe-ch (chan 10)
          handshake-data-ch (chan 100)  ;; Larger buffer for handshake data
          batch-complete-ch (chan 10)
          handshake-ack-ch (chan 10)
          handshake-complete-ch (chan 10)
          publish-ch (chan 100)  ;; Larger buffer for publishes
          unrelated-ch (chan 100)]

      ;; Store output channel in peer state for client-side operations
      (update-pubsub-state! peer assoc :out out)

      ;; Subscribe to message types
      (sub p :pubsub/subscribe subscribe-ch)
      (sub p :pubsub/subscribe-ack subscribe-ack-ch)
      (sub p :pubsub/unsubscribe unsubscribe-ch)
      (sub p :pubsub/handshake-data handshake-data-ch)
      (sub p :pubsub/handshake-batch-complete batch-complete-ch)
      (sub p :pubsub/handshake-ack handshake-ack-ch)
      (sub p :pubsub/handshake-complete handshake-complete-ch)
      (sub p :pubsub/publish publish-ch)
      (sub p :unrelated unrelated-ch)

      ;; Handle subscribe requests (server-side)
      (go-loop-super S [msg (<? S subscribe-ch)]
        (when msg
          (handle-subscription! S peer out msg pending-acks)
          (recur (<? S subscribe-ch))))

      ;; Handle subscribe-ack (client-side)
      (go-loop-super S [msg (<? S subscribe-ack-ch)]
        (when msg
          (debug {:event :pubsub/subscribe-ack-received
                  :topics (:topics msg)
                  :id (:id msg)})
          (recur (<? S subscribe-ack-ch))))

      ;; Handle unsubscribe (server-side)
      (go-loop-super S [msg (<? S unsubscribe-ch)]
        (when msg
          (handle-unsubscription! S peer out msg)
          (recur (<? S unsubscribe-ch))))

      ;; Handle handshake data (client-side)
      (go-loop-super S [msg (<? S handshake-data-ch)]
        (when msg
          (let [{:keys [topic data]} msg]
            (debug {:event :pubsub/handshake-data-received :topic topic})
            ;; Accumulate items for this batch
            (swap! handshake-items update topic (fnil conj []) data))
          (recur (<? S handshake-data-ch))))

      ;; Handle batch-complete (client-side)
      (go-loop-super S [msg (<? S batch-complete-ch)]
        (when msg
          ;; Small yield to let handshake-data processing catch up
          ;; (messages arrive in order but are processed by separate go-loops)
          (<? S (timeout 10))
          (let [{:keys [topic batch-idx]} msg
                items (get @handshake-items topic [])
                sub-state (get-in (get-pubsub-state peer) [:subscriptions topic])
                strategy (:strategy sub-state)]
            (debug {:event :pubsub/batch-complete-received
                    :topic topic
                    :batch-idx batch-idx
                    :item-count (count items)})
            ;; Apply all items
            (when strategy
              (doseq [item items]
                (<? S (proto/-apply-handshake-item strategy item))))
            ;; Clear accumulated items
            (swap! handshake-items assoc topic [])
            ;; Send ack
            (>? S out (proto/handshake-ack-msg topic batch-idx)))
          (recur (<? S batch-complete-ch))))

      ;; Handle handshake-ack (server-side)
      (go-loop-super S [msg (<? S handshake-ack-ch)]
        (when msg
          (let [{:keys [topic batch-idx]} msg]
            (debug {:event :pubsub/handshake-ack-received
                    :topic topic
                    :batch-idx batch-idx})
            ;; Signal waiting send-handshake!
            (when-let [ack-ch (get-in @pending-acks [topic batch-idx])]
              (put! ack-ch :ack)
              (close! ack-ch)))
          (recur (<? S handshake-ack-ch))))

      ;; Handle handshake-complete (client-side)
      (go-loop-super S [msg (<? S handshake-complete-ch)]
        (when msg
          (let [{:keys [topic]} msg
                on-complete (get opts :on-handshake-complete)]
            (info {:event :pubsub/handshake-complete-received :topic topic})
            (mark-handshake-complete! peer topic)
            (when on-complete
              (on-complete topic)))
          (recur (<? S handshake-complete-ch))))

      ;; Handle publish (both sides)
      (go-loop-super S [msg (<? S publish-ch)]
        (when msg
          (let [{:keys [topic payload]} msg
                ;; Check server-side (topic registered)
                topic-config (get-topic-config peer topic)
                ;; Check client-side (subscribed)
                sub-state (get-in (get-pubsub-state peer) [:subscriptions topic])
                strategy (or (:strategy topic-config) (:strategy sub-state))
                on-publish (get opts :on-publish)
                ;; Get subscribers for forwarding (server-side only)
                subscribers (get-subscribers peer topic)]
            (debug {:event :pubsub/publish-received :topic topic :subscriber-count (count subscribers)})
            ;; Apply locally
            (when strategy
              (<? S (proto/-apply-publish strategy payload)))
            (when on-publish
              (on-publish topic payload))
            ;; Server-side: forward to other subscribers (except the sender)
            ;; Note: We forward to all subscribers since the sender is the out channel
            ;; which is not in the subscribers set (subscribers are the transport channels
            ;; from connected clients, not the peer's own out channel)
            (when (and topic-config (seq subscribers))
              (debug {:event :pubsub/forwarding-publish :topic topic :count (count subscribers)})
              (let [fwd-msg (proto/publish-msg topic payload)]
                (doseq [transport subscribers]
                  ;; Don't forward back to sender (sender is 'out' which is the channel
                  ;; that received this message, so we compare with subscriber transports)
                  (when (not= transport out)
                    (try
                      (>? S transport fwd-msg)
                      (catch #?(:clj Exception :cljs js/Error) e
                        (warn {:event :pubsub/forward-failed :topic topic :error (str e)}))))))))
          (recur (<? S publish-ch))))

      ;; Pass through unrelated messages
      (go-loop-super S [msg (<? S unrelated-ch)]
        (when msg
          (>? S pass-in msg)
          (recur (<? S unrelated-ch))))

      ;; Pass through outgoing messages
      (go-loop-super S [msg (<? S pass-out)]
        (when msg
          (>? S out msg)
          (recur (<? S pass-out))))

      [S peer [pass-in pass-out]])))

;; =============================================================================
;; Convenience
;; =============================================================================

(defn make-pubsub-peer-middleware
  "Create pubsub middleware with given options.

   Options:
   - :on-publish - (fn [topic payload]) callback
   - :on-handshake-complete - (fn [topic]) callback

   Usage with kabel:
   ```clojure
   (peer/server-peer S handler id
     (comp (make-pubsub-peer-middleware opts)
           other-middleware)
     serialization-middleware)
   ```"
  [opts]
  (pubsub-middleware opts))
