(ns kabel.pubsub.overlay-test
  "The claim under test: moving `kabel.pubsub` onto the peer-to-peer overlay is
  a SUBSTITUTION, not a rewrite.

  The same strategy object, the same `subscribe!`/`publish!` calls, the same
  assertions — run twice, once over a direct connection and once over the
  overlay. If a strategy cannot tell which transport carried it, then
  konserve-sync, datahike's tx-broadcast and spindel's signal-sync become
  federated by a config key, because all three already express both paths
  through `PSyncStrategy`."
  (:require [clojure.test :refer [deftest testing is]]
            [kabel.http-kit :as http-kit]
            [kabel.identity :as id]
            [kabel.peer :as peer]
            [kabel.pubsub :as pubsub]
            [kabel.pubsub.overlay :as pso]
            [kabel.pubsub.protocol :as proto]
            [kabel.overlay.runtime :as rt]
            [kabel.dissemination :as d]
            [superv.async :refer [S <??]]
            [clojure.core.async :refer [timeout chan put! close!]]))

(def ^:private port-counter (atom 49200))
(defn- unique-port [] (swap! port-counter inc))

(defn- wait-for [ms pred]
  (let [deadline (+ (System/currentTimeMillis) ms)]
    (loop []
      (cond (pred) true
            (> (System/currentTimeMillis) deadline) false
            :else (do (<?? S (timeout 25)) (recur))))))

(defn- done [v] (let [c (chan 1)] (put! c v) (close! c) c))

(defn- recording-strategy
  "Records what it was handed. Deliberately the SAME implementation under both
  transports — the point is that it cannot tell them apart."
  [applied]
  (reify proto/PSyncStrategy
    (-init-client-state [_] (done {:since 0}))
    (-handshake-items [_ _] (done []))
    (-apply-handshake-item [_ _] (done {:ok true}))
    (-apply-publish [_ payload]
      (swap! applied conj payload)
      (done {:ok true}))))

;; =============================================================================
;; The seam itself
;; =============================================================================

(deftest absent-transport-is-todays-behaviour
  (testing "the released API is untouched when nobody opts in"
    ;; `:direct` is not a mode anyone selects — it is what happens when the
    ;; :transport key is absent, which is every existing consumer.
    (let [p (peer/client-peer S "test-peer" identity identity)]
      (is (nil? (get-in (:pubsub @p) [:transport]))
          "a fresh peer must not have a transport installed")
      (pubsub/set-transport! p {:publish! (fn [_ _ _] :routed)})
      (is (= :routed (pubsub/publish! p :t "x"))
          "publish! must delegate to an installed transport")
      (pubsub/set-transport! p nil)
      (is (nil? (get-in (:pubsub @p) [:transport]))
          "removing the transport must restore the direct path"))))

;; =============================================================================
;; The same scenario, both transports
;; =============================================================================

(defn- run-direct
  "Two peers, one connection: today's pub/sub. The server owns the topic and
  publishes; the client subscribes."
  [applied strategy]
  (let [port (unique-port)
        url (str "ws://localhost:" port)
        server (peer/server-peer S (http-kit/create-http-kit-handler! S url "server")
                                 "server"
                                 (pubsub/make-pubsub-peer-middleware {}) identity)]
    (<?? S (peer/start server))
    (try
      (pubsub/register-topic! server :t {:strategy strategy})
      (let [client (peer/client-peer S "client"
                                     (pubsub/make-pubsub-peer-middleware {}) identity)]
        (<?? S (peer/connect S client url))
        (<?? S (pubsub/subscribe! client #{:t} {:strategies {:t strategy}}))
        (<?? S (timeout 500))
        (<?? S (pubsub/publish! server :t {:hello :world}))
        (is (wait-for 5000 #(seq @applied)) "direct: nothing was ever delivered")
        nil)
      (finally (<?? S (peer/stop server))))))

(defn- run-overlay
  "The same two peers and the same calls — but the middleware now carries the
  overlay as well, so the handshake stays point-to-point while the publish is
  disseminated. Returns the two runtimes for inspection."
  [applied strategy]
  (let [port (unique-port)
        url (str "ws://localhost:" port)
        server-kp (<?? S (id/generate-identity))
        client-kp (<?? S (id/generate-identity))
        server-id (id/peer-id (:genesis server-kp))
        client-id (id/peer-id (:genesis client-kp))
        [server-ov install-server!] (rt/deferred-middleware)
        ;; Composed, not replaced. `comp` applies right-to-left, so the overlay
        ;; sees the raw socket and passes everything it does not recognise
        ;; through to pub/sub — which is exactly the division of labour: the
        ;; overlay owns its own frames, pub/sub owns the handshake.
        server (peer/server-peer S (http-kit/create-http-kit-handler! S url server-id)
                                 server-id
                                 (comp (pubsub/make-pubsub-peer-middleware {}) server-ov)
                                 identity)
        server-run (<?? S (rt/start! S server {:identity server-kp
                                               :addresses [url]
                                               :topics #{}}))]
    (install-server! (:middleware server-run))
    (<?? S (peer/start server))
    (try
      (let [[client-ov install-client!] (rt/deferred-middleware)
            client (peer/client-peer S client-id
                                     (comp (pubsub/make-pubsub-peer-middleware {})
                                           client-ov)
                                     identity)
            client-run (<?? S (rt/start! S client
                                         {:identity client-kp
                                          :addresses []
                                          :topics #{}
                                          :seeds [{:peer-id server-id
                                                   :addresses [url]
                                                   :group "seed"}]}))]
        (install-client! (:middleware client-run))
        (pso/install! S server server-run)
        (pso/install! S client client-run)
        (pubsub/register-topic! server :t {:strategy strategy})
        (<?? S (peer/connect S client url))
        (is (wait-for 8000 #(contains? (rt/connections client) server-id))
            "the overlay never connected, so the comparison would be vacuous")
        (<?? S (pubsub/subscribe! client #{:t} {:strategies {:t strategy}}))
        (<?? S (timeout 500))
        (<?? S (pubsub/publish! server :t {:hello :world}))
        (is (wait-for 8000 #(seq @applied)) "overlay: nothing was ever delivered")
        {:client client-run :server server-run})
      (finally (<?? S (peer/stop server))))))

(deftest a-publish-reaches-the-strategy-on-either-transport
  (doseq [[label run] [[:direct run-direct] [:overlay run-overlay]]]
    (testing (name label)
      (let [applied (atom [])
            strategy (recording-strategy applied)
            runs (run applied strategy)]
        ;; The identical assertion under both transports. That it is identical
        ;; IS the result.
        (is (= [{:hello :world}] @applied)
            (str (name label) ": the strategy saw the wrong payloads"))
        (when runs
          ;; The atom is shared by both peers' strategies, so "something was
          ;; applied" alone would also be satisfied by a purely local delivery.
          ;; Assert the payload actually CROSSED.
          (let [c (:dissemination (rt/overlay-state (:client runs)))
                sv (:dissemination (rt/overlay-state (:server runs)))]
            (is (= 1 (get-in sv [:stats :published]))
                "the server should have published exactly once")
            (is (= 1 (get-in c [:stats :delivered]))
                "the CLIENT must be the peer that delivered — otherwise the
                 payload never left the publisher")
            (is (zero? (get-in sv [:stats :delivered] 0))
                "the publisher is not subscribed, so it must not deliver to
                 itself — this is the interest filter doing its job")))))))

;; =============================================================================
;; The horizon is a re-handshake
;; =============================================================================

(deftest a-gap-past-the-horizon-asks-the-application-for-a-state-sync
  ;; The transport's honest statement is "these messages no longer exist
  ;; anywhere, so no repair of mine can help you". That is a transport fact.
  ;; The application answers with a differential state sync, which is an
  ;; application fact. Neither has to understand the other's semantics — the
  ;; action is the whole interface between them.
  (let [;; A publisher whose store keeps only the last 5 of 20 messages, so
        ;; everything below seq 15 has fallen out of repair range.
        pub (reduce (fn [s i] (first (d/publish s :t i)))
                    (d/make-state :pub #{:t} {:store-size 5})
                    (range 20))
        ;; A peer that has seen only message 0 — hopelessly behind.
        behind (d/mark-seen (d/make-state :behind #{:t}) :pub 0 0)
        {acts :actions} (d/handler behind
                                   {:type :message
                                    :from :pub
                                    :payload {:type :have
                                              :summary (d/summary pub)
                                              :horizon (d/horizon pub)}}
                                   {:now 0})
        syncs (filter #(= :state-sync (first %)) acts)]
    (is (= 1 (count syncs))
        "a stranded peer must be told, or it asks for the unfetchable forever")
    (is (= [:state-sync :pub [{:origin :pub :epoch 0 :missing-below 15}]]
           (first syncs))
        "the signal names who reported it and exactly what cannot be repaired")
    ;; Both, not either: the reachable part is still repaired by gossip and
    ;; only the unreachable part escalates. A design that escalated the whole
    ;; gap would do a full state sync for a peer that missed three messages.
    (is (seq (filter #(and (= :send (first %)) (= :want (:type (nth % 2)))) acts))
        "the part that CAN still be repaired must still be requested"))

  (testing "a caught-up peer is never asked to state sync"
    (let [pub (reduce (fn [s i] (first (d/publish s :t i)))
                      (d/make-state :pub #{:t} {:store-size 5})
                      (range 20))
          current (reduce (fn [s i] (d/mark-seen s :pub 0 i))
                          (d/make-state :current #{:t}) (range 20))
          {acts :actions} (d/handler current
                                     {:type :message
                                      :from :pub
                                      :payload {:type :have
                                                :summary (d/summary pub)
                                                :horizon (d/horizon pub)}}
                                     {:now 0})]
      (is (empty? (filter #(= :state-sync (first %)) acts))))))

(deftest re-handshake-reuses-the-join-path
  ;; Recovery and join are the same problem — "give me the current state" — so
  ;; they must be the same code, or the rarely-exercised one rots.
  (let [inits (atom 0)
        applied (atom [])
        strategy (reify proto/PSyncStrategy
                   (-init-client-state [_] (swap! inits inc) (done {:since 0}))
                   (-handshake-items [_ _] (done []))
                   (-apply-handshake-item [_ _] (done {:ok true}))
                   (-apply-publish [_ p] (swap! applied conj p) (done {:ok true})))
        port (unique-port)
        url (str "ws://localhost:" port)
        server (peer/server-peer S (http-kit/create-http-kit-handler! S url "srv")
                                 "srv" (pubsub/make-pubsub-peer-middleware {}) identity)]
    (<?? S (peer/start server))
    (try
      (pubsub/register-topic! server :t {:strategy strategy})
      (let [client (peer/client-peer S "cli"
                                     (pubsub/make-pubsub-peer-middleware {}) identity)]
        (<?? S (peer/connect S client url))
        (<?? S (pubsub/subscribe! client #{:t} {:strategies {:t strategy}}))
        (is (wait-for 5000 #(= 1 @inits)) "the initial handshake never ran")
        ;; Now the horizon fires. No new message type, no new state: the peer
        ;; simply asks again, and -init-client-state bounds what that costs.
        (<?? S (pso/re-handshake! S client))
        (is (wait-for 5000 #(= 2 @inits))
            "a horizon gap must re-run the SAME differential sync the join used")
        ;; The strategies survive the round trip — a re-handshake that lost
        ;; them would silently stop applying anything afterwards.
        (is (= #{:t} (set (keys (get-in @client [:pubsub :subscriptions]))))))
      (finally (<?? S (peer/stop server))))))
