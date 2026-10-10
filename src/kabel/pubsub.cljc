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
  (:require [kabel.authorize :as authz]
            [kabel.metrics :as metrics]
            [kabel.pubsub.lifecycle :as lifecycle]
            [kabel.pubsub.live-sender :as ls]
            [kabel.pubsub.protocol :as proto]
            [replikativ.logging :as log]
            #?(:clj [superv.async :refer [<? >? put? go-try go-loop-try go-loop-super]]
               :cljs [superv.async :refer [put?]])
            #?(:clj [clojure.core.async :as async
                     :refer [>! <! chan put! close! timeout alts!]]
               :cljs [clojure.core.async :as async
                      :refer [>! <! chan put! close! timeout alts!] :include-macros true]))
  #?(:cljs (:require-macros [superv.async :refer [<? >? go-try go-loop-try go-loop-super]])))

;; =============================================================================
;; Configuration
;; =============================================================================

(def default-opts
  {:batch-size 20
   :batch-timeout-ms 30000
   ;; How long to wait for the NEXT item before flushing the batch we have.
   ;; This is a batching hint ONLY — it decides when to send a partial batch,
   ;; never whether the producer is finished. Finished is signalled by the
   ;; producer CLOSING the item channel, and nothing else. (It used to end the
   ;; handshake, which turned any slow producer into silent truncation.)
   :item-timeout-ms 100
   ;; Liveness bound: how long the handshake may go WITHOUT PROGRESS before it
   ;; is declared stalled. A producer that never closes is a FAILURE and is
   ;; reported as one — never as a completed handshake.
   ;;
   ;; This is a gap between batches, not a bound on total duration. A large
   ;; store over a slow link takes as long as it takes, and capping the total
   ;; would kill healthy transfers for being big rather than for being stuck.
   ;; Generous on purpose either way: the cost of being wrong is a broken
   ;; replica.
   :handshake-timeout-ms 300000})

(defn- now-ms []
  #?(:clj (System/currentTimeMillis)
     :cljs (.getTime (js/Date.))))

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

;; =============================================================================
;; Transport
;; =============================================================================
;; Where a publish GOES, and what a subscription MEANS, are the only two things
;; that differ between running pub/sub over one connection and running it over
;; a peer-to-peer overlay. Everything else — topics, strategies, the batched
;; ack-driven handshake, backpressure — is identical, so it lives here once and
;; the difference is a pair of injected functions.
;;
;;   :direct   (default, and today's behaviour) a server fans out to the
;;             subscriber channels it holds; a client sends to its server.
;;   :overlay  a publish is disseminated multi-hop, signed at origin and
;;             verified at every hop; a subscription is topic interest.
;;
;; `PSyncStrategy` does not change, and neither does any consumer. That is the
;; point: konserve-sync, datahike's tx-broadcast and spindel's signal-sync
;; already express both paths — `-apply-publish` is the live path and
;; `-init-client-state`/`-handshake-items` is the differential state sync — so
;; federation is a transport choice rather than an API.

(defn set-transport!
  "Install a transport: `{:publish! (fn [peer topic payload] ch)
                          :subscribe! (fn [peer topics opts] ch)
                          :unsubscribe! (fn [peer topics] ch)
                          :receive-publish!
                          (fn [peer topic payload context] ch)}`.

  Either key may be omitted to keep the direct behaviour for that operation.
  `:receive-publish!` owns authenticated inbound live frames when present; it
  is how a transport makes direct and overlay live delivery mutually exclusive.
  Passing nil restores both."
  [peer transport]
  (update-pubsub-state! peer assoc :transport transport)
  peer)

(defn- transport-fn
  [peer k]
  (get-in (get-pubsub-state peer) [:transport k]))

(declare direct-publish! direct-subscribe! topic-apply-lock safe-result!
         explicit-success? estimated-frame-bytes drop-subscriber!)

(defn register-topic!
  "Register a topic on the peer for subscription.

   Parameters:
   - peer: The kabel peer atom
   - topic: Any EDN value identifying the topic
   - opts: Map with:
     - :strategy - PSyncStrategy implementation (required)
     - :batch-size - Items per handshake batch (default 20)
     - :batch-timeout-ms - Batch ack timeout (default 30000)
     - :item-timeout-ms - Timeout waiting for next item (default 100)

   Returns: topic"
  [peer topic {:keys [strategy] :as opts}]
  {:pre [(some? strategy)]}
  (log/info :pubsub/register-topic {:topic topic})
  (update-pubsub-state! peer assoc-in [:topics topic]
                        {:strategy strategy
                         :subscribers #{}
                         :opts (merge default-opts opts)})
  topic)

(defn unregister-topic!
  "Unregister a topic. Removes all subscriptions.

   Returns: topic"
  [peer topic]
  (log/info :pubsub/unregister-topic {:topic topic})
  (doseq [out (get-in (get-pubsub-state peer) [:topics topic :subscribers])]
    (drop-subscriber! peer topic out))
  (update-pubsub-state! peer update :topics dissoc topic)
  topic)

(defn subscription
  "This peer's subscription to `topic`, or nil.

  `{:strategy _ :on-handshake-complete _ :handshake-complete? _}`. A transport
  needs `:handshake-complete?` to know whether a live publish may be applied
  yet, or whether it has to wait for the state the publish builds on.

  An accessor rather than letting callers read the peer atom: the shape under
  `:pubsub` is an implementation detail, and it has already changed once."
  [peer topic]
  (get-in (get-pubsub-state peer) [:subscriptions topic]))

(defn subscriptions
  "Every topic this peer subscribes to, as `{topic subscription}`."
  [peer]
  (get-in (get-pubsub-state peer) [:subscriptions] {}))

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
  (let [added? (volatile! false)]
    (update-pubsub-state! peer update-in [:topics topic :subscribers]
                          (fn [subscribers]
                            (let [subscribers (or subscribers #{})]
                              (if (contains? subscribers transport)
                                subscribers
                                (do (vreset! added? true)
                                    (conj subscribers transport))))))
    (when @added?
      (metrics/subscription-event! :server :subscribe 1))))

(defn- remove-subscriber!
  "Remove a subscriber transport from a topic."
  [peer topic transport]
  (let [removed? (volatile! false)]
    (update-pubsub-state! peer update-in [:topics topic :subscribers]
                          (fn [subscribers]
                            (if (contains? subscribers transport)
                              (do (vreset! removed? true)
                                  (disj subscribers transport))
                              subscribers)))
    (when @removed?
      (metrics/subscription-event! :server :unsubscribe 1))))

;; =============================================================================
;; Publishing
;; =============================================================================

;; =============================================================================
;; Live windows
;; =============================================================================
;; A live publish is queued per (connection, topic) in a LiveSender and written
;; by its send loop while the receiver's grant allows (kabel.pubsub.live-sender).
;; The receiver acknowledges after applying, so a burst waits here, bounded and
;; under an overflow policy, instead of overflowing the receiver's lane.
;;
;; Peer state under `:live`:
;;   :conns   {out {:queued-bytes atom :cap n :limits m :offer m :grant m
;;                  :retire! f}}  registered by the middleware per connection
;;   :senders {[out topic] sender}
;;   :modes   {out {:mode :windowed :grant g} | {:mode :legacy}}  how the
;;            other side of `out` takes our live publishes
;;   :resyncs {[out topic] n}  consecutive resyncs, for escalation

(def live-defaults
  {:live-window 128
   :max-connection-queued-bytes (* 64 1024 1024)
   :max-resyncs 3})

;; The sequence fields a windowed frame adds, at the 4x-printed-size estimate.
(def ^:private live-frame-overhead (* 4 96))

(defn- live-conn [peer out]
  (get-in (get-pubsub-state peer) [:live :conns out]))

(defn- sender-for [peer out topic]
  (get-in (get-pubsub-state peer) [:live :senders [out topic]]))

(defn- live-mode [peer out]
  (get-in (get-pubsub-state peer) [:live :modes out]))

(defn- peer-lock
  "A lock object kept in peer state under `path`, created on first use."
  [peer path]
  (let [candidate #?(:clj (Object.) :cljs #js {})
        chosen (volatile! nil)]
    (update-pubsub-state!
     peer
     (fn [state]
       (if-let [existing (get-in state path)]
         (do (vreset! chosen existing) state)
         (do (vreset! chosen candidate)
             (assoc-in state path candidate)))))
    @chosen))

(defn- with-send-section
  "Run `f` in `topic`'s send section. Subscriber selection, sender creation and
  every enqueue into the topic's senders happen inside it, so all subscribers
  receive the topic's publications in one order (konserve-sync applies last
  write wins per key; two orders would diverge replicas). `f` must not park,
  write to a connection, or enter another topic's section; side effects such
  as retiring a connection run after it returns."
  [peer topic f]
  (let [lock (peer-lock peer [:send-locks topic])]
    #?(:clj (locking lock (f))
       :cljs (do lock (f)))))

(defn- remove-sender!
  [peer out topic sender]
  (update-pubsub-state!
   peer
   (fn [state]
     (if (identical? sender (get-in state [:live :senders [out topic]]))
       (update-in state [:live :senders] dissoc [out topic])
       state))))

(defn- new-sender!
  "Create, register and start the sender for (out, topic). Inside the topic's
  send section."
  [peer out topic opts]
  (let [conn (live-conn peer out)
        sender (assoc (ls/create out
                                 (or (:queued-bytes conn) (atom 0))
                                 (assoc opts
                                        :topic topic
                                        :epoch (random-uuid)
                                 ;; Connection defaults, overridden per topic.
                                        :limits (merge (:limits conn)
                                                       (select-keys
                                                        (:opts (get-topic-config peer topic))
                                                        [:max-queued-publishes
                                                         :max-queued-bytes]))))
                      ;; :client senders carry this side's own publishes to
                      ;; its server; :subscriber senders serve a subscriber.
                      :role (:role opts :subscriber))]
    (update-pubsub-state! peer assoc-in [:live :senders [out topic]] sender)
    (ls/start! sender
               {:on-exit #(remove-sender! peer out topic sender)
                ;; A closed connection, or core.async refusing a put past
                ;; 1024 pending ones. The frame is lost for this subscriber,
                ;; so its connection is closed and it resubscribes.
                :on-write-failed
                (fn [error]
                  (remove-subscriber! peer topic out)
                  (when error
                    (log/error :pubsub/subscriber-overflow
                               {:topic topic :error (str error)})
                    (close! out)))})))

(defn- set-live-mode!
  "Record how the other side of `out` takes our live publishes and tell the
  client senders already queueing toward it. A subscriber's sender on the same
  connection keeps the grant it negotiated for its topic."
  [peer out mode]
  (update-pubsub-state! peer assoc-in [:live :modes out] mode)
  (doseq [[[o _] sender] (get-in (get-pubsub-state peer) [:live :senders])
          :when (and (= o out) (= :client (:role sender)))]
    (if (= :windowed (:mode mode))
      (ls/swap-sender! sender ls/on-grant (:grant mode))
      (ls/swap-sender! sender ls/on-legacy))))

(defn- client-sender!
  "Inside the send section: the sender toward the server on `out`. Until the
  server's grant decision is known it queues if a subscription offering a
  window is in flight on `out`, and sends unpaced otherwise."
  [peer out topic]
  (or (sender-for peer out topic)
      (let [mode (live-mode peer out)
            sender (new-sender!
                    peer out topic
                    (assoc (cond
                             (= :windowed (:mode mode))
                             {:mode :windowed :grant (:grant mode)}

                             (and (nil? mode)
                                  (:offer (live-conn peer out))
                                  (some #(= out (:out %)) (vals (subscriptions peer))))
                             {:mode :pending-grant}

                             :else
                             {:mode :legacy})
                           :role :client))]
        ;; A decision recorded while this sender was created reached the
        ;; senders registered before it; apply it here too.
        (when-let [mode (and (nil? mode) (live-mode peer out))]
          (if (= :windowed (:mode mode))
            (ls/swap-sender! sender ls/on-grant (:grant mode))
            (ls/swap-sender! sender ls/on-legacy)))
        sender)))

(defn- enqueue-admitted!
  "Enqueue `entry` into `sender` under `policy`, with the connection-wide byte
  cap checked atomically: senders of other topics admit concurrently under
  their own sections, so the shared budget is reserved by compare-and-set
  before the enqueue and released after it (the enqueue itself accounts the
  bytes it keeps). Returns the enqueue decision."
  [peer out sender entry policy]
  (let [total (:connection-bytes sender)
        cap (or (:cap (live-conn peer out))
                (:max-connection-queued-bytes live-defaults))
        b (:bytes entry)
        reserved? (loop []
                    (let [current @total]
                      (cond
                        (> (+ current b) cap) false
                        (compare-and-set! total current (+ current b)) true
                        :else (recur))))]
    (try
      (ls/swap-sender! sender ls/enqueue entry policy (if reserved? b -1))
      (finally
        (when reserved?
          (swap! total - b))))))

(defn- live-policy
  "The overflow policy for subscriber `out` of `topic`: the topic's
  `:live-overflow`, by default `:resync`, or `:fail` for the no-sync strategy
  (its subscribers have no handshake to catch up through) and after
  `:max-resyncs` consecutive resyncs."
  [peer out topic]
  (let [{:keys [strategy opts]} (get-topic-config peer topic)
        policy (or (:live-overflow opts)
                   (if (proto/pub-sub-only? strategy) :fail :resync))
        resyncs (get-in (get-pubsub-state peer) [:live :resyncs [out topic]] 0)]
    (if (and (= :resync policy)
             (>= resyncs (get opts :max-resyncs (:max-resyncs live-defaults))))
      :fail
      policy)))

(defn- count-resync!
  [peer out topic]
  (update-pubsub-state! peer update-in [:live :resyncs [out topic]] (fnil inc 0)))

(defn- enqueue-live!
  "Inside `topic`'s send section: queue `entry` for subscriber `out`. A
  subscriber added without a sender (embedders and tests calling the handlers
  directly) gets a legacy one. An overflow that ends the session removes the
  subscriber here, in the same step, so no later publication reaches it."
  [peer out topic entry]
  (let [sender (or (sender-for peer out topic)
                   (new-sender! peer out topic {:mode :legacy}))
        decision (enqueue-admitted! peer out sender entry
                                    (live-policy peer out topic))]
    (case decision
      :resync (do (remove-subscriber! peer topic out)
                  (count-resync! peer out topic))
      :fail (remove-subscriber! peer topic out)
      nil)
    decision))

(defn- overflow-live!
  "Inside `topic`'s send section: apply subscriber `out`'s overflow policy as
  if its queue were full."
  [peer out topic entry]
  (let [sender (or (sender-for peer out topic)
                   (new-sender! peer out topic {:mode :legacy}))
        decision (ls/swap-sender! sender ls/enqueue entry
                                  (live-policy peer out topic) -1)]
    (case decision
      :resync (do (remove-subscriber! peer topic out)
                  (count-resync! peer out topic))
      :fail (remove-subscriber! peer topic out)
      nil)
    decision))

(defn- retire-out!
  [peer out reason]
  (if-let [retire! (:retire! (live-conn peer out))]
    (retire! reason)
    (close! out)))

(defn- after-live-decisions!
  "Outside the send section: report overflows, retire the connections a
  `:fail` ended."
  [peer topic decisions]
  (doseq [[out decision] decisions]
    (case decision
      (:resync :resync-deferred)
      (do (log/warn :pubsub/live-overflow-resync {:topic topic :decision decision})
          (metrics/subscription-event! :server :resync 1))

      :fail
      (do (log/warn :pubsub/live-overflow {:topic topic})
          (retire-out! peer out {:reason :live-overflow :topic topic}))

      nil)))

(defn- drop-subscriber!
  "Remove subscriber `out` of `topic` and close its sender now, dropping what
  it has queued."
  [peer topic out]
  (with-send-section
    peer topic
    (fn []
      (remove-subscriber! peer topic out)
      (when-let [sender (sender-for peer out topic)]
        (ls/swap-sender! sender ls/close)))))

(defn- forget-connection!
  "Close every sender of `out` and drop its live state."
  [peer out]
  (doseq [[[o topic] sender] (get-in (get-pubsub-state peer) [:live :senders])
          :when (= o out)]
    (with-send-section peer topic #(ls/swap-sender! sender ls/close)))
  (update-pubsub-state!
   peer update :live
   (fn [live]
     (-> live
         (update :conns dissoc out)
         (update :modes dissoc out)
         (update :resyncs #(into {} (remove (fn [[[o _] _]] (= o out))) %))))))

;; While an inbound publish is applied and forwarded, a local publish of the
;; same topic must not overtake it: the inbound write may already be committed
;; when the local one happens, and last-write-wins subscribers would end on the
;; older value. A local publish therefore waits for the apply, as it did when it
;; took the apply lock, but without parking: the send section defers it and the
;; end of the apply queues it after the forwarded frame.

(defn- begin-inbound-apply!
  [peer topic]
  (with-send-section peer topic
    #(update-pubsub-state! peer assoc-in [:live :inbound topic] {:entries [] :bytes 0})))

(defn- end-inbound-apply!
  "Queue the local publishes deferred during the apply, after its forward."
  [peer topic]
  (let [decisions
        (with-send-section
          peer topic
          (fn []
            (let [deferred (get-in (get-pubsub-state peer) [:live :inbound topic :entries])]
              (update-pubsub-state! peer update-in [:live :inbound] dissoc topic)
              (reduce (fn [decisions entry]
                        (into decisions
                              (map (fn [o] [o (enqueue-live! peer o topic entry)]))
                              (get-subscribers peer topic)))
                      []
                      deferred))))]
    (after-live-decisions! peer topic decisions)))

(defn- defer-or-enqueue!
  "Inside `topic`'s send section: queue a local publish for every subscriber,
  or defer it behind an inbound apply in progress. Deferred publishes are
  bounded like the topic's queues, in frames and bytes; past it every
  subscriber's overflow policy applies."
  [peer topic entry]
  (if-let [{:keys [entries bytes]} (get-in (get-pubsub-state peer) [:live :inbound topic])]
    (if (let [{:keys [max-queued-publishes max-queued-bytes]}
              ;; As strict as the strictest subscriber connection, with the
              ;; topic's own bounds taking precedence as they do for senders.
              (merge (apply merge-with min ls/default-limits
                            (keep #(:limits (live-conn peer %))
                                  (get-subscribers peer topic)))
                     (select-keys (:opts (get-topic-config peer topic))
                                  [:max-queued-publishes :max-queued-bytes]))]
          (and (< (count entries) max-queued-publishes)
               (<= (+ bytes (:bytes entry)) max-queued-bytes)))
      (do (update-pubsub-state! peer update-in [:live :inbound topic]
                                #(-> % (update :entries conj entry)
                                     (update :bytes + (:bytes entry))))
          {})
      (do (update-pubsub-state! peer assoc-in [:live :inbound topic]
                                {:entries [] :bytes 0})
          (into {}
                (map (fn [o] [o (overflow-live! peer o topic entry)]))
                (get-subscribers peer topic))))
    (into {}
          (map (fn [o] [o (enqueue-live! peer o topic entry)]))
          (get-subscribers peer topic))))

(defn- live-entry
  [topic payload bytes]
  {:payload payload
   :bytes (+ (or bytes (estimated-frame-bytes (proto/publish-msg topic payload)))
             live-frame-overhead)})

(defn publish!
  "Publish a message to all subscribers of a topic.

   For servers: Sends to all local subscribers.
   For clients: Sends to server (which will forward to other subscribers).

   Parameters:
   - peer: The kabel peer atom
   - topic: The topic to publish to
   - payload: Any EDN value

   Returns: channel yielding {:ok true :queued N} or {:error ...}. The
   publication is queued per subscriber connection and sent as the receiver's
   live window allows (see `direct-publish!`)."
  [peer topic payload]
  (if-let [f (transport-fn peer :publish!)]
    (f peer topic payload)
    (direct-publish! peer topic payload)))

(defn direct-publish!
  "The one-hop publish: a server fans out to its subscribers, a client sends to
  its server. This was `publish!` before there was more than one transport.

  The publication is queued in each connection's live sender and written as
  the receiver's window allows; this never parks and never throws on transport
  pressure. Returns a channel yielding `{:ok true :queued n}`, or
  `{:error e :overflow true}` when a client's queue overflowed (its connection
  is retired: the loss is loud).

  Public because it is part of the transport CONTRACT, not an implementation
  detail: a transport that carries publishes some other way may still want the
  direct path for some of them, and reaching a private var across a library
  boundary is not an interface."
  [peer topic payload]
  (let [pubsub-state (get-pubsub-state peer)
        out (:out pubsub-state)
        owner? (topic-registered? peer topic)
        is-client? (and out (not owner?))
        ;; Estimated outside the send section: printing may load from storage.
        entry (live-entry topic payload nil)
        result
        (if is-client?
          (let [decision (with-send-section
                           peer topic
                           #(enqueue-admitted! peer out (client-sender! peer out topic)
                                               entry :fail))]
            (log/debug :pubsub/publish-to-server {:topic topic :decision decision})
            (case decision
              :queued {:ok true :queued 1}
              :fail (let [error (ex-info "Live publish queue overflow"
                                         {:topic topic})]
                      (log/error :pubsub/publish-overflow {:topic topic})
                      (retire-out! peer out {:reason :live-overflow :topic topic})
                      {:error error :overflow true})
              {:error (ex-info "Connection closed" {:topic topic})}))
          (let [decisions (with-send-section
                            peer topic
                            #(defer-or-enqueue! peer topic entry))]
            (after-live-decisions! peer topic decisions)
            {:ok true :queued (count (filter #{:queued} (vals decisions)))}))]
    (doto (chan 1)
      (async/offer! result)
      close!)))

(defn- apply-publish-under-lock!
  [S peer topic payload strategy on-publish after-success]
  (let [lock (topic-apply-lock peer topic)
        owned? (topic-registered? peer topic)]
    (go-try S
            (<! lock)
            (when owned?
              (begin-inbound-apply! peer topic))
            (try
              (let [apply-result (if strategy
                                   (<! (safe-result!
                                        S #(proto/-apply-publish strategy payload)
                                        {:operation :apply-publish
                                         :topic topic}))
                                   {:ok true})]
                (if-not (explicit-success? apply-result)
                  {:error (or (:error apply-result)
                              (ex-info "Publish strategy did not report success"
                                       {:topic topic :result apply-result}))}
                  (do
                    (when on-publish
                      (on-publish topic payload))
                    (when after-success
                      (after-success))
                    {:ok true})))
              (catch #?(:clj Throwable :cljs :default) e
                {:error e})
              (finally
                (when owned?
                  (end-inbound-apply! peer topic))
                ;; A one-slot semaphore must never park its releasing runner.
                (async/offer! lock :available))))))

(defn apply-publish!
  "Apply one live payload through the peer-wide serializer for `topic`.

  This is the transport-independent local application boundary. Direct Kabel
  frames, snapshot application, and an installed overlay must all use the same
  peer/topic serializer so two network paths cannot invoke one strategy at
  once. The function applies the registered or subscribed strategy first and
  then calls `:on-publish`, both while holding that boundary.

  It deliberately does not forward the payload. Routing belongs to the direct
  middleware or the installed transport; this operation owns only local
  application. Success is the literal strategy result `{:ok true}`."
  ([S peer topic payload]
   (apply-publish! S peer topic payload nil))
  ([S peer topic payload {:keys [strategy on-publish]}]
   (let [strategy (or strategy
                      (:strategy (get-topic-config peer topic))
                      (:strategy (subscription peer topic)))]
     (apply-publish-under-lock! S peer topic payload strategy on-publish nil))))

;; =============================================================================
;; Handshake Logic
;; =============================================================================

(declare explicit-success? safe-result! serialized-result!)

(defn- send-handshake!
  "Send handshake items with batching and flow control.

   With `finish!`, the end of the handshake is `(finish!)` instead of writing
   `handshake-complete` here: the subscriber's live sender writes it, so it is
   ordered with the sender's own frames. `overflowed?` is checked between
   batches; once the subscriber's live window overflowed, the producer is
   closed and the handshake ends early, `finish!` sending `resync`.

   Returns channel yielding {:ok true :outcome o} or {:error ...}, `o` being
   what `finish!` returned (`:live` without it)."
  ([S out topic handshake-source opts pending-acks]
   (send-handshake! S out topic handshake-source opts pending-acks nil))
  ([S out topic handshake-source opts pending-acks {:keys [finish! overflowed?]}]
   (let [{handshake-ch :items producer-completion :completion}
         (if (and (map? handshake-source) (contains? handshake-source :items))
           handshake-source
           {:items handshake-source})
         {:keys [batch-size batch-timeout-ms item-timeout-ms handshake-timeout-ms]} opts
         started-ms (now-ms)]
     (go-try S
            ;; `last-progress-ms` is when this handshake last MOVED, not when it
            ;; began. The liveness bound below measures a stall, and a transfer
            ;; that is slow but progressing is not a stall — see there.
             (loop [batch-idx 0
                    total-sent 0
                    last-progress-ms started-ms]
               (if (and overflowed? (overflowed?))
                ;; The subscriber's live window overflowed: it resubscribes,
                ;; so the rest of this snapshot is moot. Closing the item
                ;; channel stops a producer parked on it.
                 (do
                   (close! handshake-ch)
                   (log/warn :pubsub/handshake-ended-by-overflow
                             {:topic topic :total-sent total-sent})
                   {:ok true :outcome (finish!)})
        ;; Collect up to batch-size items
                 (let [{:keys [items closed?]}
                       (loop [items []
                              remaining batch-size]
                         (if (zero? remaining)
                           {:items items :closed? false}
                           (let [[item ch] (alts! [handshake-ch (timeout item-timeout-ms)])]
                             (cond
                           ;; Got an item
                               (and (= ch handshake-ch) (some? item))
                               (recur (conj items item) (dec remaining))

                           ;; Channel CLOSED — the producer is finished. This is
                           ;; the ONLY thing that ends a handshake.
                               (and (= ch handshake-ch) (nil? item))
                               {:items items :closed? true}

                           ;; Quiet — the producer is SLOW, not finished. Hand
                           ;; back what we have and let the caller decide; it
                           ;; must not be mistaken for the case above.
                               :else
                               {:items items :closed? false}))))]
                   (cond
            ;; Producer finished and nothing left - send complete
                     (and closed? (empty? items))
                     (let [producer-result
                           (if producer-completion
                             (if handshake-timeout-ms
                               (let [[result port]
                                     (alts! [producer-completion
                                             (timeout handshake-timeout-ms)])]
                                 (if (= port producer-completion)
                                   result
                                   {:error (ex-info "Snapshot producer completion timeout"
                                                    {:topic topic
                                                     :handshake-timeout-ms
                                                     handshake-timeout-ms})}))
                               (<! producer-completion))
                          ;; Compatibility only. A bare legacy channel cannot
                          ;; distinguish successful close from a producer that
                          ;; caught an exception and closed after a prefix.
                             {:ok true :legacy-close? true})]
                       (if (explicit-success? producer-result)
                         (do
                           (log/debug :pubsub/handshake-complete {:topic topic :total-sent total-sent})
                           {:ok true
                            :outcome (if finish!
                                       (finish!)
                                       (do (>? S out (proto/handshake-complete-msg topic))
                                           :live))
                            :legacy-close? (:legacy-close? producer-result)})
                         {:error (or (:error producer-result)
                                     (ex-info "Snapshot producer did not report success"
                                              {:topic topic :result producer-result}))}))

            ;; QUIET, not finished. Keep waiting.
            ;;
            ;; This used to send `handshake-complete` here, which is how a slow
            ;; producer became SILENT DATA LOSS: the subscriber was told the
            ;; handshake finished, so it treated the prefix it had received as
            ;; the whole store. A producer that computes before it streams —
            ;; konserve-sync's `-handshake-items` walks a store and reads
            ;; metadata per key before emitting anything — is quiet for entirely
            ;; ordinary reasons, and it CLOSES the channel when actually done.
            ;; That signal is exact; a quiet period is a guess, and no value of
            ;; `:item-timeout-ms` turns a guess into the answer.
            ;;
            ;; Worst affected is the tail: a walker that emits mutable branch
            ;; pointers LAST (datahike's does, so a head is applied only after
            ;; the nodes it references) loses exactly the head, and a replica
            ;; without its head is unusable.
                     (and (not closed?) (empty? items))
                     (if (and handshake-timeout-ms
                              (> (- (now-ms) last-progress-ms) handshake-timeout-ms))
              ;; Liveness bound: a peer that never finishes is a FAILURE, and
              ;; must be reported as one. Never completed silently.
              ;;
              ;; Measured from the last batch we sent, NOT from the start of the
              ;; handshake. A big store over a slow link legitimately takes
              ;; longer than any fixed total, and against `started-ms` such a
              ;; transfer would sail along healthily and then die on its first
              ;; ordinary quiet gap once the clock ran out. What we want to
              ;; catch is a producer that has STOPPED, and that is a gap since
              ;; progress — a quantity no amount of slow wifi inflates.
                       (do
                         (log/warn :pubsub/handshake-timeout
                                   {:topic topic :total-sent total-sent
                                    :stalled-ms (- (now-ms) last-progress-ms)
                                    :elapsed-ms (- (now-ms) started-ms)})
                         {:error (ex-info "Handshake producer stalled"
                                          {:topic topic :total-sent total-sent
                                           :stalled-ms (- (now-ms) last-progress-ms)
                                           :handshake-timeout-ms handshake-timeout-ms})})
                       (recur batch-idx total-sent last-progress-ms))

                     :else

            ;; Register the ACK waiter BEFORE anything can cause the peer to ACK
            ;; this batch. With an unbuffered transport, the receiver can consume
            ;; batch-complete and answer before the sender resumes from its put.
            ;; Registering afterward loses that perfectly valid fast ACK and then
            ;; times out a batch the receiver already applied.
                     (let [ack-ch (chan 1)]
                       (swap! pending-acks assoc-in [topic batch-idx] ack-ch)
                       (log/debug :pubsub/sending-batch {:topic topic :batch-idx batch-idx :item-count (count items)})
              ;; Send each item
                       (doseq [item items]
                         (>? S out (proto/handshake-data-msg topic item)))

              ;; Send batch-complete with item count
                       (>? S out (proto/handshake-batch-complete-msg topic batch-idx (count items)))

              ;; Wait for ACK or timeout
                       (let [[result ch] (alts! [ack-ch (timeout batch-timeout-ms)])]
                         (swap! pending-acks update topic dissoc batch-idx)
                         (if (and (= ch ack-ch)
                                  (some? result)
                                  (not= :closed result))
                           (recur (inc batch-idx) (+ total-sent (count items)) (now-ms))
                           {:error (ex-info "Handshake batch ack timeout"
                                            {:topic topic :batch-idx batch-idx
                                             :ack-result result})})))))))))))

(defn- handle-publish!
  "Apply an inbound `:pubsub/publish` and forward it to the topic's other
   subscribers.

   `authorize-publish-fn` is (fn [principal topic] -> truthy) — the WRITE gate.
   It was previously consulted on subscribe only, so a peer that could reach a
   registered topic could `-apply-publish` into its store without holding any
   grant on it: authorization decided who could READ a store while anyone could
   WRITE one. A denied publish is dropped and answered with `:pubsub/error`; it
   is neither applied locally nor forwarded, so a refused write cannot reach
   other subscribers either.

   It DEFAULTS to `:authorize-fn`, the join-time subscribe gate, which is what
   this argument used to be unconditionally. Reusing one predicate for both
   operations means a consumer cannot say \"subscribe yes, publish no\" — and
   for a one-directional deployment (a server that owns its stores and whose
   clients only ever subscribe) the correct publish policy is to refuse every
   inbound write, which the read gate cannot express. Pass
   `:authorize-publish-fn` to separate them."
  [S peer out msg on-publish authorize-publish-fn]
  (go-try S
          (try
            (let [{:keys [topic payload]} msg
                  principal (:kabel/principal msg)
                  ;; Server-side (topic registered) / client-side (subscribed).
                  topic-config (get-topic-config peer topic)
                  sub-state (get-in (get-pubsub-state peer) [:subscriptions topic])
                  strategy (or (:strategy topic-config) (:strategy sub-state))]
              (if-not (<! (authz/decision (authorize-publish-fn {:principal principal :topic topic
                                                                 :payload payload})))
                (do
                  (log/warn :pubsub/publish-denied {:topic topic})
                  (>? S out {:type :pubsub/error
                             :topic topic
                             :error :pubsub/unauthorized
                             :message "not authorized to publish to this topic"})
                  {:ok true :denied? true})
                (if-let [receive-publish! (transport-fn peer :receive-publish!)]
                  (<! (safe-result!
                       S #(receive-publish!
                           peer topic payload {:principal principal :out out})
                       {:operation :transport-receive-publish :topic topic}))
                  (let [entry (when topic-config
                                (live-entry topic payload
                                            (estimated-frame-bytes msg)))]
                    (<!
                     (apply-publish-under-lock!
                      S peer topic payload strategy on-publish
                      (fn []
                        (log/debug :pubsub/publish-received {:topic topic})
                        ;; Server-side: forward to the other subscribers (not
                        ;; the sender), selected after apply while still
                        ;; holding the topic boundary unsubscribe takes too.
                        ;; Queueing never parks, and an overflow at another
                        ;; subscriber never fails this client's apply.
                        (when entry
                          (let [decisions
                                (with-send-section
                                  peer topic
                                  #(into {}
                                         (keep (fn [transport]
                                                 (when (not= transport out)
                                                   [transport
                                                    (enqueue-live! peer transport
                                                                   topic entry)])))
                                         (get-subscribers peer topic)))]
                            (after-live-decisions! peer topic decisions))))))))))
            (catch #?(:clj Throwable :cljs :default) e
              {:error e}))))

(defn- live-grant-for
  "The window subscriber `msg` grants for `topic`'s live publishes, clamped to
  what its lane can take next to a handshake batch: nil for a client without
  windows (or when this connection has windows disabled, `:live-window
  false`), `::invalid-grant` when nothing remains."
  [peer out msg topic batch-size]
  (when-let [{:keys [frames bytes lane]} (and (:grant (live-conn peer out))
                                              (get-in msg [:live-grant topic]))]
    (let [w (when (and (pos-int? frames) (pos-int? lane))
              (min frames (- lane batch-size 4)))]
      (if (and w (pos? w) (pos-int? bytes))
        {:frames w :bytes bytes}
        ::invalid-grant))))

(defn- handle-subscription!
  "Handle a subscription request from a client.

   `authorize-fn` is (fn [principal topic] -> truthy) — the join-time gate. The
   `principal` is whatever an upstream middleware stamped on the message as
   `:kabel/principal` (e.g. kabel-auth's annotate-msg); pubsub itself stays
   auth-agnostic. A topic that fails the gate gets a `:pubsub/error` and no
   subscription — so authorizing the subscribe authorizes the whole stream,
   since publishes fan out to the subscriber set without re-checking.

   Returns channel yielding {:ok topics} or {:error ...}"
  ([S peer out msg pending-acks authorize-fn]
   (handle-subscription! S peer out msg pending-acks authorize-fn
                         (atom #{}) (atom #{})))
  ([S peer out msg pending-acks authorize-fn retired-topics]
   (handle-subscription! S peer out msg pending-acks authorize-fn
                         retired-topics (atom #{})))
  ([S peer out msg pending-acks authorize-fn retired-topics active-sessions]
   (go-try S
           (let [{:keys [id topics client-states]} msg
                 principal (:kabel/principal msg)]
             (log/debug :pubsub/handle-subscription {:topics topics :msg-id id})
             ;; The FIFO router performs this reservation before enqueueing the
             ;; request. Repeating the idempotent union keeps direct handler
             ;; tests and embedders safe as well.
             (swap! active-sessions into topics)

            ;; Version-zero handshake frames carry only a topic, not a session
            ;; id. A duplicate cannot be correlated safely with the active
            ;; session, so retire the connection instead of overwriting it.
             (when-let [duplicate (first (filter #(or (contains? @retired-topics %)
                                                      (contains? (get-subscribers peer %) out))
                                                 topics))]
               (log/warn :pubsub/duplicate-subscription {:topic duplicate :id id})
               (swap! active-sessions #(apply disj % topics))
               (close! out)
               (throw (ex-info "Duplicate version-zero pub/sub session"
                               {:topic duplicate :id id})))

            ;; Requests on one connection are consumed sequentially by the
            ;; control runner, making this add-before-snapshot reservation
            ;; atomic with respect to later v0 requests on that connection.
             (loop [remaining (seq topics)
                    successful #{}]
               (if-let [topic (first remaining)]
                 (cond
          ;; Topic not registered on this peer
                   (not (get-topic-config peer topic))
                   (let [error (ex-info "Topic not registered" {:topic topic})]
                     (log/warn :pubsub/topic-not-found {:topic topic})
                     (>? S out (proto/error-msg topic "Topic not registered"))
                     (>? S out (proto/subscribe-ack-msg id successful))
                     (doseq [successful-topic successful]
                       (drop-subscriber! peer successful-topic out))
                     (swap! active-sessions #(apply disj % topics))
                     (close! out)
                     {:error error})

          ;; The client offered a live window this topic cannot honour
                   (= ::invalid-grant (live-grant-for peer out msg topic
                                                      (get-in (get-topic-config peer topic)
                                                              [:opts :batch-size])))
                   (let [error (ex-info "Live window too small for this topic"
                                        {:topic topic
                                         :offer (get-in msg [:live-grant topic])})]
                     (log/warn :pubsub/invalid-live-grant (ex-data error))
                     (>? S out (proto/error-msg topic "Live window too small"))
                     (>? S out (proto/subscribe-ack-msg id successful))
                     (doseq [successful-topic successful]
                       (drop-subscriber! peer successful-topic out))
                     (swap! active-sessions #(apply disj % topics))
                     (close! out)
                     {:error error})

          ;; Registered, but the subject may not subscribe
                   (not (<! (authz/decision (authorize-fn {:principal principal :topic topic}))))
                   (let [error (ex-info "Not authorized" {:topic topic})]
                     (log/warn :pubsub/subscription-denied {:topic topic})
                     (>? S out (proto/error-msg topic "Not authorized"))
                     (>? S out (proto/subscribe-ack-msg id successful))
                     (doseq [successful-topic successful]
                       (drop-subscriber! peer successful-topic out))
                     (swap! active-sessions #(apply disj % topics))
                     (close! out)
                     {:error error})

                   :else
                   (let [{:keys [strategy opts]} (get-topic-config peer topic)
                         client-state (get client-states topic)
                         grant (live-grant-for peer out msg topic (:batch-size opts))
                         ;; Begin live capture before the strategy takes its
                         ;; snapshot. The receiver lane holds these
                         ;; publications behind the snapshot cut.
                         sender (with-send-section
                                  peer topic
                                  (fn []
                                    (let [sender (new-sender!
                                                  peer out topic
                                                  (if grant
                                                    {:mode :windowed :grant grant
                                                     :phase :handshaking}
                                                    {:mode :legacy
                                                     :phase :handshaking}))]
                                      (add-subscriber! peer topic out)
                                      sender)))
                         ;; The end of the handshake, in one step on the
                         ;; sender: `handshake-complete`, or `resync` if the
                         ;; live window overflowed meanwhile. The session is
                         ;; released first, so the client's resubscribe is
                         ;; not taken for a duplicate.
                         finish! (fn []
                                   (swap! active-sessions disj topic)
                                   (with-send-section
                                     peer topic
                                     (fn []
                                       (let [outcome (ls/swap-sender! sender ls/finish)]
                                         (when (= :resync outcome)
                                           (remove-subscriber! peer topic out)
                                           (count-resync! peer out topic))
                                         outcome))))
                         resync? (contains? (set (:resync msg)) topic)]

            ;; Send handshake
                     (let [result (try
                                    (let [source (if (and resync?
                                                          (satisfies? proto/PResyncHandshake
                                                                      strategy))
                                                   (proto/-resync-handshake-items
                                                    strategy client-state)
                                                   (proto/-handshake-items
                                                    strategy client-state))]
                                      (<! (send-handshake!
                                           S out topic source opts pending-acks
                                           {:finish! finish!
                                            :overflowed? #(:overflowed?
                                                           (ls/state sender))})))
                                    (catch #?(:clj Throwable :cljs :default) e
                                      {:error e}))]
                       (if (and (map? result) (true? (:ok result)))
                         (if (= :live (:outcome result))
                           (do
                             (log/info :pubsub/subscription-complete {:topic topic})
                             (recur (next remaining) (conj successful topic)))
                           ;; Resync requested or unsubscribed meanwhile: not
                           ;; a failure, and not a subscription either.
                           (do
                             (log/info :pubsub/subscription-ended-in-handshake
                                       {:topic topic :outcome (:outcome result)})
                             (recur (next remaining) successful)))
                         (do
                           (swap! active-sessions #(apply disj % topics))
                           (log/warn :pubsub/subscription-failed
                                     {:topic topic :error (:error result)})
                           (doseq [added-topic (conj successful topic)]
                             (drop-subscriber! peer added-topic out))
                           (close! out)
                           {:error (or (:error result)
                                       (ex-info "Snapshot handshake failed"
                                                {:topic topic :result result}))})))))
                 (do
                   (>? S out (proto/subscribe-ack-msg id successful))
                   {:ok successful})))))))

(defn- handle-unsubscription!
  "Handle an unsubscribe request."
  [S peer out msg retired-topics active-sessions]
  (go-try S
          (let [{:keys [id topics]} msg]
            (log/debug :pubsub/handle-unsubscription {:topics topics})
            (if-let [joining (first (filter @active-sessions topics))]
              {:error (ex-info "Cannot drain an active v0 snapshot"
                               {:topic joining})}
              (let [;; Under the apply lock, so a publication being applied and
                    ;; forwarded has been queued; in the send section, so no
                    ;; later one is.
                    dones (loop [topics (seq topics)
                                 dones []]
                            (if-let [topic (first topics)]
                              (let [lock (topic-apply-lock peer topic)]
                                (<! lock)
                                (let [done (try
                                             (with-send-section
                                               peer topic
                                               (fn []
                                                 (remove-subscriber! peer topic out)
                                                 (when-let [sender (sender-for peer out topic)]
                                                   (ls/swap-sender! sender ls/drain)
                                                   (:done sender))))
                                             (finally
                                               (async/offer! lock :available)))]
                                  (recur (next topics) (cond-> dones done (conj done)))))
                              dones))]
                ;; The drain marker follows every frame queued before it: wait
                ;; until the senders wrote their queues, outside every lock.
                (loop [dones (seq dones)]
                  (when-let [done (first dones)]
                    (<! done)
                    (recur (next dones))))
                (when id
                  (>? S out (proto/unsubscribe-ack-msg id topics)))
                {:ok true})))))

;; =============================================================================
;; Client-Side API
;; =============================================================================

(defn- reserve-subscriptions!
  "Atomically reserve every requested client topic.

  `subscribe!` may be called concurrently from ordinary threads. Checking and
  then installing topics in separate swaps allows both calls to send a v0
  request for the same topic, whose topic-only frames cannot be correlated.
  Reserve the whole request in one peer-state transaction instead."
  [peer topics strategies on-handshake-complete out]
  (let [conflict (volatile! nil)
        generations (into {} (map (fn [topic] [topic (random-uuid)]) topics))]
    (update-pubsub-state!
     peer
     (fn [state]
       (if-let [topic (first (filter #(contains? (:subscriptions state) %) topics))]
         (do (vreset! conflict topic) state)
         (reduce (fn [state topic]
                   (assoc-in state [:subscriptions topic]
                             {:strategy (get strategies topic)
                              :on-handshake-complete on-handshake-complete
                              :generation (get generations topic)
                              :out out
                              :handshake-complete? false}))
                 state
                 topics))))
    (if-let [topic @conflict]
      {:error (ex-info "Topic already has an active subscription" {:topic topic})}
      (do
        (doseq [_ topics]
          (metrics/subscription-event! :client :subscribe 1))
        {:ok true :generations generations}))))

(defn- remove-subscription!
  ([peer topic]
   (remove-subscription! peer topic nil))
  ([peer topic generation]
   (let [removed? (volatile! false)]
     (update-pubsub-state!
      peer
      (fn [state]
        (if (and (contains? (:subscriptions state) topic)
                 (or (nil? generation)
                     (= generation
                        (get-in state [:subscriptions topic :generation]))))
          (do (vreset! removed? true)
              (update state :subscriptions dissoc topic))
          state)))
     (when @removed?
       (metrics/subscription-event! :client :unsubscribe 1)))))

(defn- mark-handshake-complete!
  "Atomically mark ready only while `generation` is still active.

  Returning false means unsubscribe, failure cleanup, or a replacement
  connection won the race. In particular, this never recreates a removed
  subscription through `assoc-in`."
  [peer topic generation]
  (let [marked? (volatile! false)]
    (update-pubsub-state!
     peer
     (fn [state]
       (if (and (some? generation)
                (= generation (get-in state [:subscriptions topic :generation]))
                (not (get-in state [:subscriptions topic :cancelling?])))
         (do
           (vreset! marked? true)
           (assoc-in state [:subscriptions topic :handshake-complete?] true))
         state)))
    @marked?))

(defn- with-client-control
  "Run `f` holding the peer's client control lock. A resubscription after a
  resync swaps its generation and puts its request in one step, and so does an
  unsubscribe claiming topics, so an unsubscribe can never be put on the wire
  ahead of the subscribe it is meant to cancel. `f` must not park."
  [peer f]
  (let [lock (peer-lock peer [:client-control-lock])]
    #?(:clj (locking lock (f))
       :cljs (do lock (f)))))

(defn- subscribe-request
  "The subscribe frame, offering this connection's live window when it has one."
  [peer out id topics client-states resync-topics]
  (let [offer (:offer (live-conn peer out))]
    (cond-> (proto/subscribe-msg id topics client-states)
      offer (assoc :live-grant (into {} (map (fn [t] [t offer])) topics))
      (seq resync-topics) (assoc :resync (set resync-topics)))))

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
  (if-let [f (transport-fn peer :subscribe!)]
    (f peer topics opts)
    (direct-subscribe! peer topics opts)))

(defn direct-subscribe!
  "The point-to-point subscribe and handshake.

  Public for the same reason as `direct-publish!`, and needed more: a transport
  that disseminates publishes over a mesh should still run the handshake here,
  because a bulk acknowledged backpressured transfer is exactly what should NOT
  be broadcast. `:out` selects which connection to run it over -- see below.

  `opts` is as `subscribe!`, plus `:out`."
  [peer topics {:keys [strategies on-publish on-handshake-complete out] :as opts}]
  (let [{{S :supervisor} :volatile} @peer
        ;; `[:pubsub :out]` is ONE slot written by per-connection middleware, so
        ;; on a peer with several connections it is whichever was accepted last.
        ;; Harmless for a client with a single connection -- the case pub/sub
        ;; was built for -- and wrong for anything on a mesh, where "handshake
        ;; with the peer that has the state" is the whole point. So a caller
        ;; that knows which connection it means passes `:out`; the fallback is
        ;; the historical behaviour.
        out (or out (get-in (get-pubsub-state peer) [:out]))
        id #?(:clj (java.util.UUID/randomUUID)
              :cljs (random-uuid))
        reservation (reserve-subscriptions! peer topics strategies
                                            on-handshake-complete out)]
    (if (:error reservation)
      (doto (chan 1)
        (put! reservation)
        close!)
      (go-try S
              (try

        ;; Build client-states (await async init)
                (let [client-states (loop [topics-seq (seq topics)
                                           states {}]
                                      (if-let [topic (first topics-seq)]
                                        (let [state (<? S (proto/-init-client-state (get strategies topic)))]
                                          (recur (rest topics-seq) (assoc states topic state)))
                                        states))]
          ;; Send subscribe request
                  (log/debug :pubsub/sending-subscribe {:topics topics :id id})
                  (>? S out (subscribe-request peer out id topics client-states nil))

          ;; Return - actual handling happens in middleware
                  {:ok topics :id id})
                (catch #?(:clj Throwable :cljs :default) e
                  ;; No request was put on the wire if client-state creation or
                  ;; the subscribe put failed, so these reservations are safe
                  ;; to release locally.
                  (doseq [topic topics]
                    (remove-subscription! peer topic))
                  ;; Nor will a grant for it arrive: publishes queued waiting
                  ;; for one go out unpaced.
                  (when (and out (nil? (live-mode peer out)))
                    (set-live-mode! peer out {:mode :legacy}))
                  {:error e}))))))

(defn- resubscribe-after-resync!
  "The server dropped `topic`'s subscription after its live window overflowed.
  Resubscribe on the same connection under a new generation; the differential
  handshake catches up on what was dropped. A subscription cancelled or
  replaced meanwhile is left alone."
  [S peer out topic generation]
  (go-try S
          (try
            (let [sub (subscription peer topic)]
              (if-not (and sub
                           (= generation (:generation sub))
                           (not (:cancelling? sub)))
                {:ok false}
                (let [client-state (<? S (proto/-init-client-state (:strategy sub)))
                      new-generation (random-uuid)
                      msg (subscribe-request peer out (random-uuid) #{topic}
                                             {topic client-state} #{topic})
                      sent? (with-client-control
                              peer
                              (fn []
                                (let [swapped? (volatile! false)]
                                  (update-pubsub-state!
                                   peer
                                   (fn [state]
                                     (vreset! swapped? false)
                                     (if (and (= generation
                                                 (get-in state [:subscriptions topic :generation]))
                                              (not (get-in state [:subscriptions topic :cancelling?])))
                                       (do (vreset! swapped? true)
                                           (update-in state [:subscriptions topic] assoc
                                                      :generation new-generation
                                                      :handshake-complete? false))
                                       state)))
                                  (when @swapped?
                                    (async/put! out msg)))))]
                  (log/info :pubsub/resubscribed-after-overflow {:topic topic :sent? (boolean sent?)})
                  {:ok true})))
            (catch #?(:clj Throwable :cljs :default) e
              (log/error :pubsub/resubscribe-after-overflow-failed
                         {:topic topic :error (str e)})
              (remove-subscription! peer topic generation)
              {:error e}))))

(defn direct-unsubscribe!
  "Orderly point-to-point unsubscribe for `topics`.

  `:out` selects the connection, matching `direct-subscribe!`. When omitted,
  the connection recorded by the active subscription is preferred over the
  historical peer-wide `:out` slot. The result settles only after the remote
  drain marker has passed all earlier topic frames.

  Idempotent: a topic whose cancellation is already under way is not asked
  for again — the result attaches to the request in flight — and a topic
  with no subscription is already done. A second caller therefore settles
  when the first one does, instead of waiting for an answer the server never
  sends twice. The claim is made in the same state transition that reads the
  subscriptions, so two callers arriving together cannot both send a request
  for one topic; a second wire request would produce a second drain, which
  a resubscription made meanwhile could not tell from its own."
  [peer topics {:keys [out]}]
  (let [pubsub-state (get-pubsub-state peer)
        pending (get-in pubsub-state [:unsubscribe-state :pending])
        done (fn [v] (doto (chan 1) (async/offer! v) close!))]
    (if-not pending
      ;; Compatibility for peers constructed without middleware (and server
      ;; peers, which have no client-side `out`). There is no remote session to
      ;; drain in this case.
      (do
        (doseq [topic topics]
          (remove-subscription! peer topic))
        (done {:ok true}))
      (with-client-control
        peer
        (fn []
          (let [claimed (volatile! #{})
                already (volatile! #{})
                remembered-outs (volatile! #{})]
        ;; One transition: read which topics are active, mark them cancelling.
        ;; Snapshot effects already accepted may drain, but readiness can no
        ;; longer be published.
            (update-pubsub-state!
             peer
             (fn [state]
               (let [subs (:subscriptions state)
                     cancelling (into #{} (filter #(get-in subs [% :cancelling?])) topics)
                     active (into #{} (filter #(and (contains? subs %) (not (cancelling %)))) topics)]
                 (vreset! already cancelling)
                 (vreset! claimed active)
                 (vreset! remembered-outs (into #{} (keep #(get-in subs [% :out])) active))
                 (reduce (fn [state topic]
                           (assoc-in state [:subscriptions topic :cancelling?] true))
                         state
                         active))))
            (let [active @claimed
                  cancelling @already
                  out (or out
                          (when (= 1 (count @remembered-outs)) (first @remembered-outs))
                          (:out pubsub-state))]
              (cond
            ;; Nothing left to ask for: every topic is gone, or a request for it
            ;; is already in flight. Attach to those requests.
                (empty? active)
                (let [attached (volatile! [])]
                  (swap! pending
                         (fn [requests]
                           (reduce-kv (fn [requests id {:keys [remaining] :as request}]
                                        (if (some cancelling remaining)
                                          (let [w (chan 1)]
                                            (vswap! attached conj w)
                                            (assoc requests id (update request :waiters (fnil conj []) w)))
                                          requests))
                                      requests
                                      requests)))
                  (if (empty? @attached)
                    (done {:ok true})
                    (let [{{S :supervisor} :volatile} @peer]
                      (go-try S
                              (loop [ws (seq @attached)]
                                (if-let [w (first ws)]
                                  (do (<! w) (recur (next ws)))
                                  {:ok true}))))))

                out
                (let [id (random-uuid)
                      result-ch (chan 1)]
                  (swap! pending assoc id {:channel result-ch
                                           :remaining active
                                           :generations
                                           (into {} (map (fn [topic]
                                                           [topic (:generation
                                                                   (subscription peer topic))])
                                                         active))})
                  (async/put! out (proto/unsubscribe-msg id active)
                              (fn [delivered?]
                                (when-not delivered?
                                  (when-let [{:keys [channel waiters]} (get @pending id)]
                                    (swap! pending dissoc id)
                                    (let [error {:error (ex-info "Unsubscribe was not delivered"
                                                                 {:topics active})}]
                                      (async/offer! channel error)
                                      (close! channel)
                                      (doseq [w waiters]
                                        (async/offer! w error)
                                        (close! w)))))))
                  result-ch)

            ;; Claimed, but no connection to ask: release locally.
                :else
                (do
                  (doseq [topic active]
                    (remove-subscription! peer topic))
                  (done {:ok true}))))))))))

(defn unsubscribe!
  "Unsubscribe from topics and await the remote drain marker.

  Active topics must belong to one connection. Call `direct-unsubscribe!`
  explicitly for each connection when coordinating a multi-source client."
  [peer topics]
  (if-let [f (transport-fn peer :unsubscribe!)]
    (f peer topics)
    (let [pubsub-state (get-pubsub-state peer)
          outs (into #{} (keep #(get-in pubsub-state [:subscriptions % :out]))
                     topics)]
      (if (> (count outs) 1)
        (doto (chan 1)
          (put! {:error (ex-info "Topics belong to different connections"
                                 {:topics topics :connections (count outs)})})
          close!)
        (direct-unsubscribe! peer topics {:out (first outs)})))))

;; =============================================================================
;; Middleware
;; =============================================================================

(defn- estimated-frame-bytes
  "Use a codec-provided encoded size when available: the CBOR codec records it
  as `:kabel/encoded-bytes` in the decoded frame's METADATA, never as a key a
  peer could forge or a relay could leak. Legacy codecs do not annotate, so
  their frames are upper-bounded by their printed form. Printing is the
  application's code and may fail — a Datahike index node loads children from
  storage when printed — and a failed estimate must never take the receive
  lane down with it, so it counts as zero and is logged. Wire-profile v1
  requires the transport to supply the exact encoded size before
  decode/allocation."
  [msg]
  (or (:kabel/encoded-bytes (meta msg))
      (:kabel/encoded-bytes msg)
      (try
        (* 4 (count (pr-str msg)))
        (catch #?(:clj Throwable :cljs :default) e
          (log/debug :pubsub/frame-size-estimate-failed
                     {:type (:type msg) :error (ex-message e)})
          0))))

(defn- explicit-success?
  [result]
  (and (map? result)
       (true? (:ok result))
       (not (contains? result :error))))

(defn- caught-error?
  [value]
  #?(:clj (instance? Throwable value)
     :cljs (instance? js/Error value)))

(defn- safe-result!
  "Call an async strategy operation and normalize every failure into a value.

  Lifecycle runners must never die between accepting a frame and reporting its
  result to the pure state machine. `go-try` deliberately puts thrown errors on
  its result channel; consuming that channel with `<?` would throw again and
  strand the lane, so this boundary uses `<!` and converts the error explicitly."
  [S f context]
  (go-try S
          (try
            (let [result (<! (f))]
              (cond
                (caught-error? result) {:error result}
                (explicit-success? result) result
                :else {:error (ex-info "Pub/sub operation did not report explicit success"
                                       (assoc context :result result))}))
            (catch #?(:clj Throwable :cljs :default) e
              {:error e}))))

(defn- topic-apply-lock
  "Return the peer-wide serializer for one topic.

  Middleware instances are connection-scoped, but registered strategies and
  client subscription strategies are peer-scoped. The lock therefore lives in
  peer state so two connections can never invoke the same strategy at once."
  [peer topic]
  (let [candidate (chan 1)
        chosen (volatile! nil)]
    (async/offer! candidate :available)
    (update-pubsub-state!
     peer
     (fn [state]
       (if-let [existing (get-in state [:apply-locks topic])]
         (do (vreset! chosen existing) state)
         (do (vreset! chosen candidate)
             (assoc-in state [:apply-locks topic] candidate)))))
    @chosen))

(defn- serialized-result!
  "Run one strategy operation under the peer/topic apply serializer."
  [S peer topic f context]
  (let [lock (topic-apply-lock peer topic)]
    (go-try S
            (<! lock)
            (try
              (<! (safe-result! S f (assoc context :topic topic)))
              (finally
                ;; A one-slot semaphore must never park its releasing runner.
                (async/offer! lock :available))))))

(defn- apply-values!
  "Apply `values` sequentially. Stop on the first exception, closed result
  channel, or explicit strategy error."
  [S apply-fn values]
  (go-try S
          (loop [values (seq values)]
            (if-let [value (first values)]
              (let [result (<! (safe-result! S #(apply-fn value)
                                             {:operation :apply-value}))]
                (if (explicit-success? result)
                  (recur (next values))
                  {:error (or (:error result)
                              (ex-info "Pub/sub strategy did not report success"
                                       {:result result}))}))
              {:ok true}))))

(defn- lifecycle-event
  [msg]
  (case (:type msg)
    :pubsub/handshake-data
    {:type :snapshot/item
     :value (:data msg)
     :bytes (estimated-frame-bytes msg)}

    :pubsub/handshake-batch-complete
    {:type :snapshot/batch
     :index (:batch-idx msg)
     :count (:item-count msg)}

    :pubsub/handshake-complete
    {:type :snapshot/complete}

    :pubsub/publish
    {:type :live/publication
     :value msg
     :bytes (estimated-frame-bytes msg)}

    :pubsub/unsubscribe-drain
    {:type :close}

    :pubsub/resync
    {:type :live/resync}))

(defn- pending-unsubscribe?
  "Whether `id` names an unsubscribe request in flight that still awaits
   `topic`'s drain."
  [peer id topic]
  (when-let [pending (get-in (get-pubsub-state peer) [:unsubscribe-state :pending])]
    (contains? (get-in @pending [id :remaining]) topic)))

(defn- complete-unsubscribe-topic!
  "Account the drain of `topic` to request `id`, remove the subscription that
   request was made for, and settle the request once all its topics drained.
   Removal is pinned to the generation recorded when the request was sent: a
   subscription made since is a different one and stays. Returns true when
   the drain belonged to a request in flight, nil for a stale drain."
  [peer id topic]
  (when-let [pending (get-in (get-pubsub-state peer)
                             [:unsubscribe-state :pending])]
    (let [completed (volatile! nil)
          matched (volatile! nil)]
      (swap! pending
             (fn [requests]
               (vreset! completed nil)
               (vreset! matched nil)
               (if-let [{:keys [remaining generations] :as request} (get requests id)]
                 (if (contains? remaining topic)
                   (let [remaining (disj remaining topic)]
                     (vreset! matched [(get generations topic)])
                     (if (empty? remaining)
                       (do (vreset! completed request)
                           (dissoc requests id))
                       (assoc requests id (assoc request :remaining remaining))))
                   requests)
                 requests)))
      (when-let [[generation] @matched]
        (remove-subscription! peer topic generation)
        (when-let [{:keys [channel waiters]} @completed]
          (async/offer! channel {:ok true})
          (close! channel)
          ;; Later unsubscribes of the same topics attached here instead of
          ;; sending a second request; settle them with the same answer.
          (doseq [w waiters]
            (async/offer! w {:ok true})
            (close! w)))
        true))))

(defn- invoke-ready-callback
  [callback topic]
  (try
    (when callback
      (callback topic))
    {:ok true}
    (catch #?(:clj Throwable :cljs :default) e
      {:error e})))

(defn- run-lifecycle-effects!
  [S peer out opts on-publish authorize-publish-fn topic generation
   retire-connection! state effects]
  (go-try S
          (let [continue! (fn [next-state next-effects]
                            (run-lifecycle-effects!
                             S peer out opts on-publish authorize-publish-fn
                             topic generation retire-connection!
                             next-state next-effects))
                effects (seq effects)]
            (if-let [{:keys [op items values value index failure] :as effect}
                     (first effects)]
              (let [remaining (next effects)]
                (case op
                  :apply-snapshot-batch
                  (let [strategy (:strategy (subscription peer topic))
                        result (if strategy
                                 (<! (apply-values!
                                      S
                                      #(serialized-result!
                                        S peer topic
                                        (fn [] (proto/-apply-handshake-item strategy %))
                                        {:operation :apply-handshake-item})
                                      items))
                                 {:error (ex-info "No strategy for snapshot batch"
                                                  {:topic topic})})
                        transition (lifecycle/transition
                                    state
                                    (if (:ok result)
                                      {:type :snapshot/batch-result :ok true}
                                      {:type :snapshot/batch-result
                                       :error (:error result)}))]
                    (<! (continue! (:state transition)
                                   (concat (:effects transition) remaining))))

                  :ack-snapshot-batch
                  (do
                    (>? S out (proto/handshake-ack-msg topic index))
                    (<! (continue! state remaining)))

                  :ack-live
                  (do
                    (>? S out (proto/publish-ack-msg topic (:epoch effect) (:seq effect)))
                    (<! (continue! state remaining)))

                  :resync
                  (do
                    (log/warn :pubsub/resync-received {:topic topic})
                    (resubscribe-after-resync! S peer out topic generation)
                    (<! (continue! state remaining)))

                  :apply-captured-live
                  (let [result (<! (apply-values!
                                    S
                                    #(handle-publish! S peer out % on-publish
                                                      authorize-publish-fn)
                                    values))
                        transition (lifecycle/transition
                                    state
                                    (if (:ok result)
                                      {:type :snapshot/drain-result :ok true}
                                      {:type :snapshot/drain-result
                                       :error (:error result)}))]
                    (<! (continue! (:state transition)
                                   (concat (:effects transition) remaining))))

                  :apply-live
                  (let [result (<! (handle-publish! S peer out value on-publish
                                                    authorize-publish-fn))
                        transition (lifecycle/transition
                                    state
                                    (if (explicit-success? result)
                                      {:type :live/result :ok true}
                                      {:type :live/result
                                       :error (or (:error result)
                                                  (ex-info "Publish did not report success"
                                                           {:topic topic
                                                            :result result}))}))]
                    (<! (continue! (:state transition)
                                   (concat (:effects transition) remaining))))

                  :settle-ready
                  (if-not (true? (:ok effect))
                    ;; Error settlement only resolves the lifecycle's internal
                    ;; ready promise. It must never publish user-visible ready.
                    (<! (continue! state remaining))
                    (if-not (and (some? generation)
                                 (= generation (:generation (subscription peer topic)))
                                 (not (:cancelling? (subscription peer topic))))
                      (<! (continue! state remaining))
                      (let [on-complete
                            (or (get-in (get-pubsub-state peer)
                                        [:subscriptions topic
                                         :on-handshake-complete])
                                (get opts :on-handshake-complete))
                            ;; The callback is part of readiness: if it throws,
                            ;; the session failed and must not be marked ready.
                            callback-result (invoke-ready-callback on-complete
                                                                   topic)]
                        (if (:ok callback-result)
                          (if (mark-handshake-complete! peer topic generation)
                            (do
                              (log/info :pubsub/handshake-complete-received
                                        {:topic topic})
                              (<! (continue! state remaining)))
                            ;; Cancellation won after the callback. Do not
                            ;; resurrect state or publish readiness.
                            (<! (continue! state remaining)))
                          (let [transition (lifecycle/transition
                                            state
                                            {:type :abort
                                             :code :ready-callback-failed
                                             :error (:error callback-result)})]
                            (<! (continue!
                                 (:state transition)
                                 (concat (:effects transition) remaining))))))))

                  :fail
                  (do
                    (log/warn :pubsub/subscription-lifecycle-failed failure)
                    ;; Version zero cannot isolate or correlate a failed topic
                    ;; session. Retire the connection so stale frames cannot
                    ;; contaminate a later subscription.
                    (retire-connection! {:reason :lifecycle-failed
                                         :topic topic
                                         :failure failure})
                    (<! (continue! state remaining)))

                  (:settle-closed :closed)
                  (<! (continue! state remaining))

                  (throw (ex-info "Unknown pub/sub lifecycle effect"
                                  {:effect effect :topic topic}))))
              state))))

(defn- start-lifecycle-lane!
  [S peer out opts on-publish authorize-publish-fn topic first-msg
   retire-connection!]
  (let [lane (chan (get opts :lifecycle-lane-size 256))
        generation (:generation (subscription peer topic))
        limits (select-keys opts [:max-batch-items
                                  :max-batch-bytes
                                  :max-pending-publishes
                                  :max-pending-bytes])
        initial (if-let [sub (subscription peer topic)]
                  (if (:handshake-complete? sub)
                    (lifecycle/initial-live-state topic limits)
                    (lifecycle/initial-state topic limits))
                  ;; A publish to an owner or unknown topic has no client-side
                  ;; snapshot to wait for. Authorization/strategy lookup still
                  ;; happens in handle-publish!. Handshake frames without a
                  ;; subscription remain joining and fail validation.
                  (if (= :pubsub/publish (:type first-msg))
                    (lifecycle/initial-live-state topic limits)
                    (lifecycle/initial-state topic limits)))]
    (go-loop-super S [state initial
                      msg (<? S lane)]
                   (if msg
                     (if (and (= :pubsub/unsubscribe-drain (:type msg))
                              (not (pending-unsubscribe? peer (:id msg) topic)))
                       ;; A drain for a request this side no longer holds: the
                       ;; topic was released already and this lane belongs to a
                       ;; subscription made since. Closing on it would take
                       ;; that subscription down.
                       (do (log/warn :pubsub/stale-unsubscribe-drain {:topic topic :id (:id msg)})
                           (recur state (<? S lane)))
                       (let [transition (lifecycle/transition state (lifecycle-event msg))
                             next-state (<? S (run-lifecycle-effects!
                                               S peer out opts on-publish
                                               authorize-publish-fn topic
                                               generation
                                               retire-connection!
                                               (:state transition)
                                               (:effects transition)))]
                         (if (= :pubsub/unsubscribe-drain (:type msg))
                           (do
                             (complete-unsubscribe-topic! peer (:id msg) topic)
                             (close! lane))
                           (if (and (lifecycle/terminal? next-state)
                                    ;; A resyncing lane stays for the
                                    ;; unsubscribe drain of a cancellation
                                    ;; racing the resubscription, until the
                                    ;; new generation's lane replaces it.
                                    (not= :resyncing (:phase next-state)))
                             (close! lane)
                             (recur next-state (<? S lane))))))
                     (let [transition (lifecycle/transition state {:type :close})]
                       (<? S (run-lifecycle-effects!
                              S peer out opts on-publish authorize-publish-fn
                              topic generation retire-connection!
                              (:state transition)
                              (:effects transition))))))
    lane))

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
    (let [;; Join-time subscribe authorization. Default permissive so kabel
          ;; stays a plain pub/sub substrate; an app injects a policy that reads
          ;; the `:kabel/principal` an upstream auth middleware stamped.
          ;; Resolved once, here, so the two gates cannot drift apart again.
          authorize-fn (authz/gate opts {:op :subscribe
                                         :legacy-keys [:authorize-fn]
                                         :legacy-adapter authz/pubsub-legacy})
          ;; Defaults to the subscribe gate, so an existing consumer that passes
          ;; only :authorize-fn behaves exactly as before.
          authorize-publish-fn (authz/gate opts
                                           {:op :publish
                                            :legacy-keys [:authorize-publish-fn
                                                          :authorize-fn]
                                            :legacy-adapter authz/pubsub-legacy})
          on-publish (get opts :on-publish)
          pass-in (chan (get opts :pass-through-buffer-size 100))
          pass-out (chan)

          ;; Per-connection state
          pending-acks (atom {})  ;; {topic -> {batch-idx -> chan}}
          pending-unsubscribes (atom {})
          active-server-sessions (atom #{})
          lifecycle-lanes (atom {}) ;; {topic -> {:generation _ :channel _}}
          retired? (atom false)
          retired-topics (atom #{})

          ;; Control work may park without stopping the input router. Overflow
          ;; retires the connection; silently dropping protocol control would
          ;; be indistinguishable from a successful but incomplete session.
          subscribe-ch (chan 10)
          unsubscribe-ch (chan 10)
          lifecycle-types #{:pubsub/handshake-data
                            :pubsub/handshake-batch-complete
                            :pubsub/handshake-complete
                            :pubsub/publish
                            :pubsub/resync}

          ;; Live windows. What this side grants: as a subscriber (`offer`,
          ;; sent in subscribe requests) the server's publishes are held in
          ;; `:pending` during a handshake and share the lane with handshake
          ;; frames; as a server (`grant`) a client's publishes only share the
          ;; lane. Bytes keep half of the pending budget as slack: the
          ;; receiver also counts keys the transport adds to decoded frames.
          lane-size (get opts :lifecycle-lane-size 256)
          receive-limits (merge lifecycle/default-limits
                                (select-keys opts [:max-pending-publishes
                                                   :max-pending-bytes]))
          window (get opts :live-window (:live-window live-defaults))
          window-bytes (min (get opts :live-window-bytes
                                 (quot (:max-pending-bytes receive-limits) 2))
                            (quot (:max-pending-bytes receive-limits) 2))
          offer (when window
                  {:frames (min window (:max-pending-publishes receive-limits))
                   :bytes window-bytes
                   :lane lane-size})
          grant (when window
                  (let [frames (min window (- lane-size 4))]
                    (when (pos? frames)
                      {:frames frames :bytes window-bytes})))]

      ;; Store output channel in peer state for client-side operations
      (update-pubsub-state! peer assoc
                            :out out
                            :unsubscribe-state {:out out
                                                :pending pending-unsubscribes})

      (letfn [(retire-connection! [reason]
                (when (compare-and-set! retired? false true)
                  (log/warn :pubsub/retiring-v0-connection reason)
                  (doseq [[_ batches] @pending-acks
                          [_ ack-ch] batches]
                    (async/offer! ack-ch :closed)
                    (close! ack-ch))
                  (reset! pending-acks {})
                  (doseq [[_ {:keys [channel]}] @pending-unsubscribes]
                    (async/offer! channel
                                  {:error (ex-info "Connection closed before unsubscribe drain"
                                                   reason)})
                    (close! channel))
                  (reset! pending-unsubscribes {})
                  (reset! active-server-sessions #{})
                  (doseq [[_ {:keys [channel]}] @lifecycle-lanes]
                    (close! channel))
                  (doseq [topic (keys (:topics (get-pubsub-state peer)))]
                    (drop-subscriber! peer topic out))
                  (forget-connection! peer out)
                  (doseq [[topic sub-state] (subscriptions peer)]
                    (when (= out (:out sub-state))
                      (when-not (:handshake-complete? sub-state)
                        ;; The subscriber's completion callback will never
                        ;; fire; say so, since a silent removal looks exactly
                        ;; like a slow handshake from the outside.
                        (log/warn :pubsub/subscription-retired-before-ready
                                  {:topic topic :reason reason}))
                      (remove-subscription! peer topic)))
                  (close! subscribe-ch)
                  (close! unsubscribe-ch)
                  (close! pass-in)
                  (close! out)))

              (offer-or-retire! [ch value kind]
                (when-not (async/offer! ch value)
                  (retire-connection! {:reason :router-overflow :kind kind})))

              (route-lifecycle! [msg]
                (let [topic (:topic msg)
                      generation (:generation (subscription peer topic))
                      existing (get @lifecycle-lanes topic)
                      lane (if (and existing
                                    (= generation (:generation existing)))
                             (:channel existing)
                             (let [lane (start-lifecycle-lane!
                                         S peer out opts on-publish
                                         authorize-publish-fn topic msg
                                         retire-connection!)]
                               (when-let [old (:channel existing)]
                                 (close! old))
                               (swap! lifecycle-lanes assoc topic
                                      {:generation generation :channel lane})
                               lane))]
                  (offer-or-retire! lane msg :lifecycle)))]

        (update-pubsub-state! peer assoc-in [:live :conns out]
                              {:queued-bytes (atom 0)
                               :cap (get opts :max-connection-queued-bytes
                                         (:max-connection-queued-bytes live-defaults))
                               :limits (select-keys opts [:max-queued-publishes
                                                          :max-queued-bytes])
                               :offer offer
                               :grant grant
                               :retire! retire-connection!})

        ;; Subscribe requests are deliberately serialized for this connection.
        ;; The input router below remains free to deliver ACKs while a snapshot
        ;; sender waits, but a second subscribe cannot race the first topic
        ;; reservation.
        (go-loop-super S [msg (<? S subscribe-ch)]
                       (when msg
                         (let [result (<! (handle-subscription!
                                           S peer out msg pending-acks authorize-fn
                                           retired-topics active-server-sessions))]
                           (when (or (caught-error? result) (:error result))
                             (retire-connection!
                              {:reason :subscribe-failed
                               :error (if (caught-error? result)
                                        result
                                        (:error result))})))
                         (recur (<? S subscribe-ch))))

        (go-loop-super S [msg (<? S unsubscribe-ch)]
                       (when msg
                         (let [result (<! (handle-unsubscription!
                                           S peer out msg retired-topics
                                           active-server-sessions))]
                           (when (or (caught-error? result) (:error result))
                             (retire-connection!
                              {:reason :unsubscribe-failed
                               :error (if (caught-error? result)
                                        result
                                        (:error result))})))
                         (when-not @retired?
                           (recur (<? S unsubscribe-ch)))))

        ;; Lightweight FIFO router. It never parks on application work and it
        ;; never silently drops: every bounded destination is an immediate
        ;; offer whose failure retires this uncorrelatable v0 connection.
        (go-loop-super S [msg (<? S in)]
                       (if-not msg
                         (retire-connection! {:reason :input-closed})
                         (do
                           ;; The first frame from a server that sent no grant
                           ;; before it: a server without windows. Publishes
                           ;; queued waiting for its grant go out unpaced.
                           (when (and offer
                                      (nil? (live-mode peer out))
                                      (or (contains? lifecycle-types (:type msg))
                                          (= :pubsub/subscribe-ack (:type msg))))
                             (set-live-mode! peer out {:mode :legacy}))
                           (case (:type msg)
                             :pubsub/subscribe
                             (let [topics (:topics msg)]
                               ;; Reserve at the FIFO observation point, before
                               ;; the independent control runner can be
                               ;; overtaken by a following unsubscribe.
                               (if-let [topic (first (filter @active-server-sessions
                                                             topics))]
                                 (retire-connection!
                                  {:reason :duplicate-active-subscribe
                                   :topic topic})
                                 (do
                                   (swap! active-server-sessions into topics)
                                   ;; A client offering windows learns ours
                                   ;; before any frame of this subscription.
                                   (when (and grant (:live-grant msg))
                                     (async/put! out (proto/live-grant-msg
                                                      (:frames grant)
                                                      (:bytes grant))))
                                   (offer-or-retire! subscribe-ch msg :subscribe))))

                             :pubsub/live-grant
                             (let [{:keys [frames bytes]} msg]
                               (if (and (pos-int? frames) (pos-int? bytes))
                                 (set-live-mode! peer out {:mode :windowed
                                                           :grant {:frames frames
                                                                   :bytes bytes}})
                                 (retire-connection! {:reason :invalid-live-grant
                                                      :grant (select-keys msg [:frames :bytes])})))

                             :pubsub/publish-ack
                             (let [{:keys [topic epoch]} msg
                                   n (:seq msg)]
                               (when-let [sender (and (int? n) (sender-for peer out topic))]
                                 (let [acked (ls/swap-sender! sender ls/on-ack epoch n)
                                       {:keys [phase grant]} (ls/state sender)]
                                   ;; A full window acknowledged while live ends
                                   ;; a run of resyncs.
                                   (when (and (= :live phase)
                                              (>= acked (:frames grant 1))
                                              (get-in (get-pubsub-state peer)
                                                      [:live :resyncs [out topic]]))
                                     (update-pubsub-state! peer update-in [:live :resyncs]
                                                           dissoc [out topic])))))

                             :pubsub/subscribe-ack
                             (log/debug :pubsub/subscribe-ack-received
                                        {:topics (:topics msg) :id (:id msg)})

                             :pubsub/unsubscribe
                             (offer-or-retire! unsubscribe-ch msg :unsubscribe)

                             :pubsub/unsubscribe-ack
                             (doseq [topic (:topics msg)]
                               (route-lifecycle!
                                {:type :pubsub/unsubscribe-drain
                                 :topic topic
                                 :id (:id msg)}))

                             :pubsub/handshake-ack
                             (let [{:keys [topic batch-idx]} msg]
                               (log/debug :pubsub/handshake-ack-received
                                          {:topic topic :batch-idx batch-idx})
                               (when-let [ack-ch (get-in @pending-acks
                                                         [topic batch-idx])]
                                 (async/offer! ack-ch :ack)
                                 (close! ack-ch)))

                             :pubsub/error
                             (retire-connection! {:reason :remote-error
                                                  :topic (:topic msg)
                                                  :error (:error msg)
                                                  :message (:message msg)})

                             (if (contains? lifecycle-types (:type msg))
                               (route-lifecycle! msg)
                               (offer-or-retire! pass-in msg :pass-through)))
                           (when-not @retired?
                             (recur (<? S in)))))))

      ;; Pass through outgoing messages
      (go-loop-super S [msg (<? S pass-out)]
                     (when msg
                       (if @retired?
                         (close! pass-out)
                         (do
                           (>? S out msg)
                           (recur (<? S pass-out))))))

      [S peer [pass-in pass-out]])))

;; =============================================================================
;; Convenience
;; =============================================================================

(defn make-pubsub-peer-middleware
  "Create pubsub middleware with given options.

   Options:
   - :on-publish - (fn [topic payload]) callback
   - :on-handshake-complete - (fn [topic]) callback
   - :authorize-fn - (fn [principal topic] -> truthy) join-time SUBSCRIBE gate;
     `principal` is the message's `:kabel/principal` (stamped by an upstream
     auth middleware). Default permits everything.
   - :authorize-publish-fn - (fn [principal topic] -> truthy) gate on inbound
     PUBLISHES. Defaults to `:authorize-fn`, so passing only the latter keeps
     today's behaviour. Separate them when read and write authority differ —
     notably a one-directional deployment, where every inbound publish should
     be refused (`(constantly false)`) because only the server publishes.
   - :live-window - frames this side lets the other keep unacknowledged per
     topic (default 128); false disables live windows on the connection.
   - :live-window-bytes - the same in estimated bytes (default half of
     :max-pending-bytes).
   - :max-queued-publishes, :max-queued-bytes - a sender queue's bounds
     (default 4096, 16 MiB); per topic in `register-topic!` too.
   - :max-connection-queued-bytes - all queues of one connection (64 MiB).

   Per topic (`register-topic!`): :live-overflow (:resync or :fail) and
   :max-resyncs (default 3).

   Usage with kabel:
   ```clojure
   (peer/server-peer S handler id
     (comp (make-pubsub-peer-middleware opts)
           other-middleware)
     serialization-middleware)
   ```"
  [opts]
  (pubsub-middleware opts))
