(ns kabel.pubsub-test
  (:require [clojure.test :refer [deftest testing is]]
            [kabel.pubsub :as pubsub]
            [kabel.pubsub.protocol :as proto]
            [superv.async :refer [S go-try <?]]
            #?(:clj [clojure.core.async :as async :refer [go <! >! chan put! close! timeout alts!]]
               :cljs [clojure.core.async :as async :refer [chan put! close! timeout] :include-macros true]))
  #?(:cljs (:require-macros [clojure.core.async :refer [go <! >! alts!]])))

;; =============================================================================
;; Test Helpers
;; =============================================================================

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
      #?(:clj (Thread/sleep 100))
      (is (= items @received)))))

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
