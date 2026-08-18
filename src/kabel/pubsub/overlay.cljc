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
            #?(:clj [clojure.core.async :as async :refer [chan put! close!]]
               :cljs [clojure.core.async :as async :refer [chan put! close!]]))
  #?(:cljs (:require-macros [superv.async :refer [<? go-try]])))

(defn- ok [v]
  (let [ch (chan 1)] (put! ch v) (close! ch) ch))

(defn deliver-to-strategy!
  "Hand a disseminated payload to the topic's strategy.

  This is the `:deliver!` effect: dissemination emits a `[:deliver topic
  payload]` action rather than calling anything, so the state machine stays
  pure and the runtime decides what delivery means. Here it means
  `-apply-publish`, which is what a one-hop publish has always meant."
  [S peer topic payload]
  (let [sub (get-in (or (:pubsub @peer) {}) [:subscriptions topic])
        topic-cfg (get-in (or (:pubsub @peer) {}) [:topics topic])
        strategy (or (:strategy sub) (:strategy topic-cfg))]
    (when strategy
      (go-try S (<? S (proto/-apply-publish strategy payload))))))

(defn transport
  "A `kabel.pubsub` transport backed by `running` (from
  `kabel.overlay.runtime/start!`).

  Install with `(pubsub/set-transport! peer (transport S peer running))`.
  Omitting it leaves the direct one-hop transport in place, which is why an
  application that never opts in behaves exactly as it did."
  [S peer running]
  {:publish!
   (fn [_peer topic payload]
     (rt/publish! running topic payload)
     (ok {:ok true :transport :overlay}))

   :subscribe!
   (fn [peer topics opts]
     (go-try S
             ;; Interest FIRST. It is what makes dissemination forward these
             ;; topics to us at all, and registering it before the handshake
             ;; means a publish that lands mid-handshake is delivered rather
             ;; than dropped for lack of interest.
             (rt/subscribe-topics! running topics)
             ;; Then the ordinary point-to-point handshake — the SAME code the
             ;; direct transport runs. A handshake is a bulk, acknowledged,
             ;; backpressured transfer between two peers; disseminating it
             ;; would flood the network with one peer's catch-up. What the
             ;; overlay changes is who you may handshake WITH, not how.
             (assoc (<? S (#'pubsub/direct-subscribe! peer topics opts))
                    :transport :overlay)))})

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

  `-init-client-state` bounds it: a peer that is barely behind sends a nearly
  empty handshake, so this is cheap when the gap is small and correct when it
  is not.

  Point-to-point on purpose. This is a bulk, acknowledged, backpressured
  transfer between two peers, and disseminating it would flood everyone with
  one peer's catch-up — the opposite of what a horizon gap calls for."
  [S peer]
  (let [subs (get-in (or (:pubsub @peer) {}) [:subscriptions])
        topics (set (keys subs))
        strategies (into {} (for [[t {:keys [strategy]}] subs
                                  :when strategy]
                              [t strategy]))
        topics (into #{} (filter strategies topics))]
    (when (seq topics)
      (#'pubsub/direct-subscribe! peer topics {:strategies strategies}))))

(defn install!
  "Wire `peer`'s pub/sub onto `running`, and route disseminated payloads into
  the topic strategies.

  Returns the peer."
  [S peer running]
  (rt/set-deliver! running (fn [topic payload]
                             (deliver-to-strategy! S peer topic payload)))
  (rt/set-state-sync! running (fn [_from _stranded]
                                (re-handshake! S peer)))
  (pubsub/set-transport! peer (transport S peer running))
  peer)
