(ns kabel.pubsub.overlay
  "Running `kabel.pubsub` over the peer-to-peer overlay.

  ## What changes, and what does not

  Only two things differ between pub/sub over one connection and pub/sub over a
  mesh: where a publish **goes**, and what a subscription **means**. Everything
  else — topics, `PSyncStrategy`, the batched ack-driven handshake,
  backpressure — is identical, so it is untouched.

  That is the whole design. `PSyncStrategy` already expresses both paths a
  replicated value needs:

      -apply-publish                          the live path
      -init-client-state → -handshake-items   the DIFFERENTIAL state sync
        → -apply-handshake-item

  and three consumers already implement it — konserve-sync's timestamp diff,
  datahike's tx-broadcast, spindel's signal-sync (which carries yggdrasil's
  `-join` and `-apply-delta` inside its strategy). None of them needs to change
  to become federated, because the semantics were never the transport's
  business.

  ## The three substitutions

  **Publish** becomes dissemination: multi-hop, signed at origin, verified at
  every hop, deduplicated by interval set, and repairable.

  **Subscribe** becomes topic interest. A relay carries a topic RANGE, so
  `[:tx-report]` or one store id is a thing a peer can agree to relay without
  carrying the network.

  **The handshake stays point-to-point**, because it is a bulk, acknowledged,
  backpressured transfer and should be. What changes is who with: today it is
  \"your server\", here it is any peer carrying the range — so state sync becomes
  multi-source.

  ## The horizon is a re-handshake

  `kabel.dissemination/beyond-horizon` reports peers whose gap δ-repair cannot
  close, because the messages have fallen out of the repair store. The correct
  answer is not a bespoke state request: it is to **run the handshake again**,
  which is exactly the differential state sync `PSyncStrategy` already
  provides — and precisely replikativ's observation that a state sync per
  identity is much smaller than replaying every change that produced it.

  The transport says *I cannot fill this gap*, which is a transport fact. The
  application answers with a differential sync, which is an application fact.
  Neither needs to know the other's semantics."
  (:require [kabel.pubsub :as pubsub]
            [kabel.pubsub.protocol :as proto]
            [kabel.overlay.runtime :as rt]
            #?(:clj [superv.async :refer [<? go-try]])
            [clojure.core.async :refer [chan put! close!]])
  #?(:cljs (:require-macros [superv.async :refer [<? go-try]])))

(defn- ok [v]
  (let [ch (chan 1)] (put! ch v) (close! ch) ch))

(defn deliver-to-strategy!
  "Hand a disseminated payload to the topic's strategy, and to `:on-publish`.

  Installed by `install!` as the runtime's delivery function: dissemination
  emits a `[:deliver topic payload]` action rather than calling anything, so
  the state machine stays pure and the runtime decides what delivery means.
  Here it means what a one-hop publish has always meant — `-apply-publish`
  followed by the `:on-publish` callback, in that order, exactly as
  `kabel.pubsub/handle-publish!` does on the direct path.

  Calling `:on-publish` matters for the PR's central claim: a strategy is not
  supposed to be able to tell which transport carried it, and neither is the
  application. `subs` carries the opts the subscription was made with, because
  `:on-publish` is not kept in the peer's subscription state."
  [S peer subs topic payload]
  (let [sub (get-in (or (:pubsub @peer) {}) [:subscriptions topic])
        topic-cfg (get-in (or (:pubsub @peer) {}) [:topics topic])
        strategy (or (:strategy sub) (:strategy topic-cfg))
        on-publish (get-in @subs [topic :on-publish])]
    (go-try S
            (when strategy
              (<? S (proto/-apply-publish strategy payload)))
            (when on-publish
              (on-publish topic payload)))))

(defn transport
  "A `kabel.pubsub` transport backed by `running` (from
  `kabel.overlay.runtime/start!`).

  Prefer `install!`, which wires delivery as well — a transport alone carries
  publishes across the network but nothing hands them to a strategy.

  `subs` is an atom of `{topic opts}`, remembered so that a horizon-triggered
  `re-handshake!` can reproduce the ORIGINAL subscription rather than a
  stripped-down one."
  [S peer running subs]
  {:publish!
   (fn [_peer topic payload]
     ;; `submit!` returns false when the runtime's event queue is full, and its
     ;; docstring is explicit that refusing is the point. Reporting {:ok true}
     ;; for a publish that was dropped would invert the only backpressure
     ;; signal the runtime has.
     (if (rt/publish! running topic payload)
       (ok {:ok true :transport :overlay})
       (ok {:error :kabel/overloaded :transport :overlay})))

   :subscribe!
   (fn [peer topics opts]
     (go-try S
             ;; Interest FIRST. It is what makes dissemination forward these
             ;; topics to us at all; without it the handshake could complete
             ;; and no live publish would ever arrive.
             (if-not (rt/subscribe-topics! running topics)
               ;; Refused means the interest was never registered, so this peer
               ;; would be permanently invisible to the mesh for these topics.
               ;; Reporting success here is the worst of the options.
               {:error :kabel/overloaded :transport :overlay}
               (do
                 (doseq [t topics] (swap! subs assoc t opts))
                 ;; Then the ordinary point-to-point handshake — the SAME code
                 ;; the direct transport runs. A handshake is a bulk,
                 ;; acknowledged, backpressured transfer between two peers;
                 ;; disseminating it would flood the network with one peer's
                 ;; catch-up. What the overlay changes is who you may handshake
                 ;; WITH, not how.
                 (assoc (<? S (#'pubsub/direct-subscribe! peer topics opts))
                        :transport :overlay)))))})

(defn re-handshake!
  "Answer a horizon gap by re-running the handshake for everything this peer
  subscribes to.

  Deliberately not a bespoke \"state request\" message. `PSyncStrategy` already
  has exactly one differential state sync — `-init-client-state` →
  `-handshake-items` → `-apply-handshake-item` — and a peer that has fallen
  beyond the repair horizon is in precisely the position of a peer that just
  subscribed: it needs the CURRENT state, not the messages that produced it.
  Reusing the subscribe path means the recovery route is the same code the
  join route is, so it cannot rot separately.

  Reuses the ORIGINAL opts per topic. Passing only `{:strategies …}` would be
  the same code with a different argument: `init-subscription-state!` replaces
  the whole subscription map, so `:on-handshake-complete` would be silently
  dropped and never fire again.

  Point-to-point on purpose. This is a bulk, acknowledged, backpressured
  transfer between two peers, and disseminating it would flood everyone with
  one peer's catch-up — the opposite of what a horizon gap calls for."
  [S peer subs]
  (let [held (get-in (or (:pubsub @peer) {}) [:subscriptions])
        topics (into #{} (filter #(get-in held [% :strategy]) (keys held)))]
    (when (seq topics)
      ;; One call per distinct opts map, so each topic is restored with the
      ;; callbacks it was subscribed with.
      (doseq [[opts ts] (group-by #(get @subs %) topics)]
        (#'pubsub/direct-subscribe!
         peer (set ts)
         (or opts {:strategies (into {} (for [t ts]
                                          [t (get-in held [t :strategy])]))}))))))

(defn install!
  "Wire `peer`'s pub/sub onto `running`, and route disseminated payloads into
  the topic strategies.

  Returns the peer."
  [S peer running]
  (let [;; The opts each topic was subscribed with. `kabel.pubsub` keeps
        ;; `:strategy` and `:on-handshake-complete` on the peer but not
        ;; `:on-publish`, and a re-handshake needs all of them.
        subs (atom {})]
    (rt/set-deliver! running (fn [topic payload]
                               (deliver-to-strategy! S peer subs topic payload)))
    (rt/set-state-sync! running (fn [_from _stranded]
                                  (re-handshake! S peer subs)))
    (pubsub/set-transport! peer (transport S peer running subs))
    peer))
