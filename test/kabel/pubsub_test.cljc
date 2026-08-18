(ns kabel.pubsub-test
  (:require [clojure.test :refer [deftest testing is]]
            [kabel.authorize :as authz]
            [kabel.pubsub :as pubsub]
            [kabel.pubsub.protocol :as proto]
            #?(:clj [superv.async :refer [S go-try <? <??]]
               :cljs [superv.async :refer [S go-try <?]])
            #?(:clj [clojure.core.async :as async :refer [go <! >! chan put! close! timeout alts!]]
               :cljs [clojure.core.async :as async :refer [chan put! close! timeout go <! >! alts!]])))

;; =============================================================================
;; Test Helpers
;; =============================================================================

(defn- legacy-gate
  "Resolve a gate exactly as `pubsub-middleware` does.

  These tests call the private handlers directly, so they have to resolve the
  gate themselves — and resolving it through `kabel.authorize` rather than
  hand-rolling the lookup is the point: it keeps the tests exercising the
  production resolution, and it means the legacy `(fn [principal topic])` shape
  stays covered."
  [opts op]
  (authz/gate opts {:op op
                    :legacy-keys (if (= op :publish)
                                   [:authorize-publish-fn :authorize-fn]
                                   [:authorize-fn])
                    :legacy-adapter authz/pubsub-legacy}))

(defn make-test-peer
  "Create a minimal peer atom for testing."
  []
  (atom {:volatile {:supervisor S}
         :id (random-uuid)}))

(defn make-channel-pair
  "Create an in/out channel pair for testing middleware."
  []
  [(chan 100) (chan 100)])

;; =============================================================================
;; Protocol Tests
;; =============================================================================

(deftest pub-sub-only-strategy-test
  (testing "PubSubOnlyStrategy returns closed channel for nil client state"
    (let [strategy (proto/pub-sub-only-strategy nil)
          ch (proto/-init-client-state strategy)
          result (async/poll! ch)]
      ;; Closed channel returns nil on poll
      (is (nil? result))))

  (testing "PubSubOnlyStrategy handshake-items returns closed channel"
    (let [strategy (proto/pub-sub-only-strategy nil)
          ch (proto/-handshake-items strategy nil)
          result (async/poll! ch)]
      (is (nil? result))))

  (testing "PubSubOnlyStrategy apply-publish calls callback and returns channel"
    (let [received (atom nil)
          strategy (proto/pub-sub-only-strategy #(reset! received %))
          ch (proto/-apply-publish strategy {:data 123})
          result (async/poll! ch)]
      (is (:ok result))
      (is (= {:data 123} @received)))))

;; =============================================================================
;; Message Helper Tests
;; =============================================================================

(deftest message-helpers-test
  (testing "subscribe-msg creates correct structure"
    (let [msg (proto/subscribe-msg :req-1 #{:topic-a :topic-b} {:topic-a {:state 1}})]
      (is (= :pubsub/subscribe (:type msg)))
      (is (= :req-1 (:id msg)))
      (is (= #{:topic-a :topic-b} (:topics msg)))
      (is (= {:topic-a {:state 1}} (:client-states msg)))))

  (testing "publish-msg creates correct structure"
    (let [msg (proto/publish-msg :my-topic {:value 42})]
      (is (= :pubsub/publish (:type msg)))
      (is (= :my-topic (:topic msg)))
      (is (= {:value 42} (:payload msg)))))

  (testing "pubsub-msg? identifies pubsub messages"
    (is (proto/pubsub-msg? {:type :pubsub/subscribe}))
    (is (proto/pubsub-msg? {:type :pubsub/publish}))
    (is (not (proto/pubsub-msg? {:type :other/message})))
    (is (not (proto/pubsub-msg? {:type :no-namespace})))))

;; =============================================================================
;; Registration Tests
;; =============================================================================

(deftest register-topic-test
  (testing "register-topic! adds topic to peer state"
    (let [peer (make-test-peer)
          strategy (proto/pub-sub-only-strategy nil)]
      (pubsub/register-topic! peer :test-topic {:strategy strategy})
      (is (pubsub/topic-registered? peer :test-topic))
      (is (some? (pubsub/get-topic-config peer :test-topic)))))

  (testing "unregister-topic! removes topic"
    (let [peer (make-test-peer)
          strategy (proto/pub-sub-only-strategy nil)]
      (pubsub/register-topic! peer :test-topic {:strategy strategy})
      (pubsub/unregister-topic! peer :test-topic)
      (is (not (pubsub/topic-registered? peer :test-topic)))))

  (testing "get-subscribers returns empty set initially"
    (let [peer (make-test-peer)
          strategy (proto/pub-sub-only-strategy nil)]
      (pubsub/register-topic! peer :test-topic {:strategy strategy})
      (is (= #{} (pubsub/get-subscribers peer :test-topic))))))

;; =============================================================================
;; Custom Strategy for Testing
;; =============================================================================

(defrecord TestSyncStrategy [items-to-send received-items]
  proto/PSyncStrategy

  (-init-client-state [_]
    (let [ch (chan 1)]
      (put! ch {:client-version 1})
      (close! ch)
      ch))

  (-handshake-items [_ client-state]
    (let [ch (chan)]
      (go
        (doseq [item @items-to-send]
          (>! ch item))
        (close! ch))
      ch))

  (-apply-handshake-item [_ item]
    (swap! received-items conj item)
    (let [ch (chan 1)]
      (put! ch {:ok true})
      (close! ch)
      ch))

  (-apply-publish [_ payload]
    (swap! received-items conj {:type :publish :payload payload})
    (let [ch (chan 1)]
      (put! ch {:ok true})
      (close! ch)
      ch)))

(defn make-test-sync-strategy
  "Create a test strategy that sends/receives specific items."
  [items-to-send]
  (->TestSyncStrategy (atom items-to-send) (atom [])))

;; =============================================================================
;; Integration Tests
;; =============================================================================

#?(:clj
   (deftest handshake-flow-test
     (testing "handshake sends items in batches"
       ;; This would require running the full middleware
       ;; For now, just test the strategy interface
       (let [items [{:key :a :value 1}
                    {:key :b :value 2}
                    {:key :c :value 3}]
             strategy (make-test-sync-strategy items)
             handshake-ch (proto/-handshake-items strategy {:client-version 1})
             received (atom [])]
         ;; Read all items from channel
         (go
           (loop []
             (when-let [item (<! handshake-ch)]
               (swap! received conj item)
               (recur))))
         ;; Wait a bit for async
         (Thread/sleep 100)
         (is (= items @received))))))

;; =============================================================================
;; End-to-End Tests (with middleware)
;; =============================================================================

;; These tests require setting up actual channel pairs and running middleware
;; They're more complex and might need adjustments based on actual usage

(deftest middleware-subscribe-test
  (testing "middleware handles subscribe request"
    ;; TODO: Full middleware test with channel pairs
    ;; For now, verify middleware can be created
    (let [middleware-fn (pubsub/make-pubsub-peer-middleware {})]
      (is (fn? middleware-fn)))))

;; =============================================================================
;; Subscribe authorization gate (:authorize-fn)
;; =============================================================================

#?(:clj
   (deftest subscribe-authorize-gate-test
     (let [handle-subscription! @#'pubsub/handle-subscription!
           drain (fn [out] ;; collect what handle-subscription! wrote, briefly
                   (loop [acc []]
                     (let [[v _] (async/alts!! [out (timeout 300)])]
                       (if v (recur (conj acc v)) acc))))
           sub-msg {:type :pubsub/subscribe :id :r1 :topics #{:t}
                    :client-states {} :kabel/principal {:sub "alice"}}]

       (testing "denied principal: :pubsub/error, no subscriber added, empty ack"
         (let [peer (make-test-peer)
               out (chan 100)]
           (pubsub/register-topic! peer :t {:strategy (proto/pub-sub-only-strategy nil)})
           (<?? S (handle-subscription! S peer out sub-msg (atom {})
                                        (legacy-gate {:authorize-fn (fn [_ _] false)} :subscribe)))
           (let [msgs (drain out)]
             (is (some #(and (= :pubsub/error (:type %)) (= :t (:topic %))) msgs)
                 "an error is returned for the denied topic")
             (is (= #{} (pubsub/get-subscribers peer :t))
                 "a denied subscriber is not added to the topic")
             (is (some #(and (= :pubsub/subscribe-ack (:type %))
                             (= #{} (:topics %))) msgs)
                 "the ack lists no successful topics"))))

       (testing "allowed principal: subscriber added, topic in ack"
         (let [peer (make-test-peer)
               out (chan 100)]
           (pubsub/register-topic! peer :t {:strategy (proto/pub-sub-only-strategy nil)})
           (<?? S (handle-subscription! S peer out sub-msg (atom {})
                                        (legacy-gate {:authorize-fn (fn [_ _] true)} :subscribe)))
           (let [msgs (drain out)]
             (is (contains? (pubsub/get-subscribers peer :t) out)
                 "an authorized subscriber joins the topic")
             (is (some #(and (= :pubsub/subscribe-ack (:type %))
                             (contains? (:topics %) :t)) msgs)
                 "the ack reports the topic as successful"))))

       (testing "the gate sees the message's :kabel/principal"
         (let [peer (make-test-peer)
               out (chan 100)
               seen (atom nil)]
           (pubsub/register-topic! peer :t {:strategy (proto/pub-sub-only-strategy nil)})
           (<?? S (handle-subscription! S peer out sub-msg (atom {})
                                        (legacy-gate {:authorize-fn (fn [p _] (reset! seen p) true)}
                                                     :subscribe)))
           (is (= {:sub "alice"} @seen)))))))

;; =============================================================================
;; Publish authorization gate (:authorize-fn on the WRITE path)
;; =============================================================================

#?(:clj
   (deftest publish-authorize-gate-test
     ;; The gate used to be consulted on subscribe ONLY, so authorization decided
     ;; who could READ a store while any peer that could reach a registered topic
     ;; could `-apply-publish` into it. For konserve-sync that is a write into the
     ;; backing store with no grant checked.
     (let [handle-publish! @#'pubsub/handle-publish!
           drain (fn [out]
                   (loop [acc []]
                     (let [[v _] (async/alts!! [out (timeout 300)])]
                       (if v (recur (conj acc v)) acc))))
           ;; a strategy that records what was applied, standing in for a store
           applied (atom [])
           ;; only -apply-publish is exercised on this path
           recording-strategy (reify proto/PSyncStrategy
                                (-apply-publish [_ payload]
                                  (go-try S (swap! applied conj payload) true)))
           pub-msg {:type :pubsub/publish :topic :t :payload {:k :v}
                    :kabel/principal {:sub "mallory"}}]

       (testing "denied principal: nothing applied, :pubsub/error returned"
         (reset! applied [])
         (let [peer (make-test-peer)
               out (chan 100)]
           (pubsub/register-topic! peer :t {:strategy recording-strategy})
           (<?? S (handle-publish! S peer out pub-msg nil
                                   (legacy-gate {:authorize-fn (fn [_ _] false)} :publish)))
           (is (= [] @applied)
               "a denied publish must not reach the topic's strategy")
           (is (some #(and (= :pubsub/error (:type %))
                           (= :pubsub/unauthorized (:error %))) (drain out))
               "the sender is told the publish was refused")))

       (testing "allowed principal: the publish still applies"
         (reset! applied [])
         (let [peer (make-test-peer)
               out (chan 100)]
           (pubsub/register-topic! peer :t {:strategy recording-strategy})
           (<?? S (handle-publish! S peer out pub-msg nil
                                   (legacy-gate {:authorize-fn (fn [_ _] true)} :publish)))
           (is (= [{:k :v}] @applied)
               "an authorized publish is applied unchanged")))

       (testing "the gate sees the message's :kabel/principal and topic"
         (reset! applied [])
         (let [peer (make-test-peer)
               out (chan 100)
               seen (atom nil)]
           (pubsub/register-topic! peer :t {:strategy recording-strategy})
           (<?? S (handle-publish! S peer out pub-msg nil
                                   (legacy-gate
                                    {:authorize-fn (fn [principal topic]
                                                     (reset! seen [principal topic])
                                                     true)}
                                    :publish)))
           (is (= [{:sub "mallory"} :t] @seen))))

       (testing "a denied publish is not forwarded to other subscribers"
         (reset! applied [])
         (let [peer (make-test-peer)
               out (chan 100)
               other (chan 100)]
           (pubsub/register-topic! peer :t {:strategy recording-strategy})
           (@#'pubsub/add-subscriber! peer :t other)
           (<?? S (handle-publish! S peer out pub-msg nil
                                   (legacy-gate {:authorize-fn (fn [_ _] false)} :publish)))
           (is (empty? (drain other))
               "refusing a write must also stop it reaching readers"))))))

;; =============================================================================
;; Separating the two gates (:authorize-publish-fn)
;; =============================================================================

;; The tests above drive `handle-publish!` directly, so they prove the WRITE
;; gate is consulted but say nothing about WHICH option the middleware routes
;; there. That wiring is the whole point of `:authorize-publish-fn`, so it is
;; what these tests exercise: build real middleware, feed it real messages,
;; observe which predicate each operation consulted.

#?(:clj
   (defn- run-middleware
     "Wire `opts` into pubsub middleware over a channel pair, push `msgs` in, and
      return whatever came back out. Mirrors how a peer drives it."
     [opts msgs]
     (let [peer (make-test-peer)
           [in out] (make-channel-pair)
           mw (pubsub/make-pubsub-peer-middleware opts)]
       (mw [S peer [in out]])
       (doseq [m msgs] (put! in m))
       ;; drain briefly — the middleware answers asynchronously
       (loop [acc []]
         (let [[v _] (async/alts!! [out (timeout 300)])]
           (if v (recur (conj acc v)) acc))))))

#?(:clj
   (deftest publish-gate-defaults-to-the-subscribe-gate
     (testing "a consumer passing only :authorize-fn is unaffected by the new key"
       ;; Backward compatibility is the contract: this is exactly what every
       ;; existing consumer does, and it must keep gating publishes.
       (let [asked (atom [])
             peer (make-test-peer)
             out (chan 100)
             handle-publish! @#'pubsub/handle-publish!
             applied (atom [])
             strategy (reify proto/PSyncStrategy
                        (-apply-publish [_ p] (go-try S (swap! applied conj p) true)))]
         (pubsub/register-topic! peer :t {:strategy strategy})
         ;; The resolution under test now lives in `kabel.authorize/gate`: with
         ;; no :authorize-publish-fn, the publish gate falls back to
         ;; :authorize-fn — which is what keeps a consumer who set only the
         ;; latter behaving exactly as before.
         (let [opts {:authorize-fn (fn [_ topic] (swap! asked conj topic) false)}
               resolved (legacy-gate opts :publish)]
           (<?? S (handle-publish! S peer out
                                   {:type :pubsub/publish :topic :t :payload {:k :v}
                                    :kabel/principal {:sub "mallory"}}
                                   nil resolved))
           (is (= [:t] @asked) "the subscribe gate was consulted for the publish")
           (is (= [] @applied) "and its refusal held"))))))

#?(:clj
   (deftest the-two-gates-are-independent
     (testing "subscribe may be permitted while every publish is refused"
       ;; The one-directional deployment: the server owns its stores and
       ;; publishes them; a client only ever subscribes, so the correct publish
       ;; policy is to refuse every inbound write. One predicate cannot say this.
       (let [sub-asked (atom 0)
             pub-asked (atom 0)
             msgs (run-middleware
                   {:authorize-fn (fn [_ _] (swap! sub-asked inc) true)
                    :authorize-publish-fn (fn [_ _] (swap! pub-asked inc) false)}
                   [{:type :pubsub/publish :topic :t :payload {:k :v}
                     :kabel/principal {:sub "mallory"}}])]
         (is (= 1 @pub-asked) "the publish gate decided the publish")
         (is (= 0 @sub-asked) "the subscribe gate was not consulted for it")
         (is (some #(and (= :pubsub/error (:type %))
                         (= :pubsub/unauthorized (:error %))) msgs)
             "and the refusal reached the sender")))))

;; =============================================================================
;; The unified gate
;; =============================================================================

(deftest authorize-gate-unification
  ;; `:authorize-fn` meant two incompatible things under one name: pubsub called
  ;; it (fn [principal topic]), dissemination called it (fn [topic origin]). A
  ;; consumer passing one predicate to both bound `principal` to a topic —
  ;; silently, and in whichever direction their predicate happened to lean.
  (testing "the new :authorize takes a map and cannot be misordered"
    (let [seen (atom nil)
          g (authz/gate {:authorize (fn [ctx] (reset! seen ctx) true)}
                        {:op :publish
                         :legacy-keys [:authorize-fn]
                         :legacy-adapter authz/pubsub-legacy})]
      (is (true? (g {:principal :alice :topic :t :payload {:root "r"}})))
      (is (= {:op :publish :principal :alice :topic :t :payload {:root "r"}} @seen))
      (testing "and it carries the payload, so a policy can ask WHICH database"
        (is (= {:root "r"} (:payload @seen))))))

  (testing ":authorize wins over the legacy key"
    (let [g (authz/gate {:authorize (constantly true)
                         :authorize-fn (constantly false)}
                        {:op :subscribe
                         :legacy-keys [:authorize-fn]
                         :legacy-adapter authz/pubsub-legacy})]
      (is (true? (g {:principal :a :topic :t})))))

  (testing "the released positional shape is called exactly as it always was"
    ;; This is the only compatibility that exists, and it exists because
    ;; kabel.pubsub shipped it. A layer that has never been released gets one
    ;; shape and no adapter.
    (let [seen (atom nil)
          g (authz/gate {:authorize-fn (fn [p t] (reset! seen [p t]) true)}
                        {:op :subscribe :legacy-keys [:authorize-fn]
                         :legacy-adapter authz/pubsub-legacy})]
      (g {:principal :alice :topic :t})
      (is (= [:alice :t] @seen) "principal first, then topic — unchanged")))

  (testing "a caller with no released positional form passes no legacy keys"
    (let [g (authz/gate {:authorize (fn [{:keys [op principal]}]
                                      (and (= :publish op) (= :alice principal)))}
                        {:op :publish})]
      (is (true? (g {:principal :alice :topic :t})))
      (is (false? (g {:principal :mallory :topic :t})))))

  (testing "no policy configured permits everything, as before"
    (let [g (authz/gate {} {:op :publish :legacy-keys [:authorize-fn]
                            :legacy-adapter authz/pubsub-legacy})]
      (is (true? (g {:principal :anyone :topic :t})))))

  (testing "a truthy non-boolean is normalised"
    (let [g (authz/gate {:authorize (constantly "yes")}
                        {:op :publish :legacy-keys []
                         :legacy-adapter authz/pubsub-legacy})]
      (is (true? (g {:topic :t}))))))
