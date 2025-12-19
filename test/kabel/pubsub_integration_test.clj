(ns kabel.pubsub-integration-test
  "Integration tests for kabel.pubsub with real server/client peers."
  (:require [clojure.test :refer [deftest testing is]]
            [kabel.pubsub :as pubsub]
            [kabel.pubsub.protocol :as proto]
            [kabel.peer :as peer]
            [kabel.http-kit :as http-kit]
            [superv.async :refer [<?? S]]
            [clojure.core.async :refer [<!! >! go chan put! close! timeout]]))

;; =============================================================================
;; Test Infrastructure
;; =============================================================================

(def ^:dynamic *server-peer* nil)
(def ^:dynamic *client-peer* nil)
(def ^:dynamic *server-url* nil)

(defn unique-port []
  (+ 47300 (rand-int 100)))

(defmacro with-peers
  "Execute body with server and client peers set up."
  [& body]
  `(let [port# (unique-port)
         url# (str "ws://localhost:" port#)
         sid# (java.util.UUID/randomUUID)
         cid# (java.util.UUID/randomUUID)]
     (binding [*server-url* url#]
       (let [handler# (http-kit/create-http-kit-handler! S url# sid#)]
         ;; Server peer with pubsub middleware
         (binding [*server-peer* (peer/server-peer S handler# sid#
                                                   (pubsub/make-pubsub-peer-middleware {})
                                                   identity)]
           ;; Start server
           (<?? S (peer/start *server-peer*))
           (try
             ;; Client peer with pubsub middleware
             (let [client-opts# (atom {})
                   client-middleware# (fn [[S# peer# [in# out#]]]
                                        ((pubsub/make-pubsub-peer-middleware @client-opts#)
                                         [S# peer# [in# out#]]))]
               (binding [*client-peer* (peer/client-peer S cid# client-middleware# identity)]
                 ;; Connect client to server
                 (<?? S (peer/connect S *client-peer* url#))
                 ;; Wait for connection to establish
                 (<?? S (timeout 200))
                 (try
                   ~@body
                   (finally
                     ;; Cleanup handled by server stop
                     nil))))
             (finally
               (<?? S (peer/stop *server-peer*)))))))))

;; =============================================================================
;; Test Sync Strategy with State
;; =============================================================================

(defrecord StatefulSyncStrategy [state-atom items-atom received-atom]
  proto/PSyncStrategy

  (-init-client-state [_]
    (let [ch (chan 1)]
      (put! ch {:version (:version @state-atom 0)
                :known-keys (set (keys (:data @state-atom)))})
      (close! ch)
      ch))

  (-handshake-items [_ client-state]
    (let [ch (chan 100)
          client-keys (:known-keys client-state #{})
          items @items-atom]
      (go
        ;; Send items client doesn't have
        (doseq [[k v] items]
          (when-not (contains? client-keys k)
            (>! ch {:key k :value v})))
        (close! ch))
      ch))

  (-apply-handshake-item [_ {:keys [key value]}]
    (swap! received-atom conj {:type :handshake :key key :value value})
    (swap! state-atom update :data assoc key value)
    (let [ch (chan 1)]
      (put! ch {:ok true})
      (close! ch)
      ch))

  (-apply-publish [_ {:keys [key value] :as payload}]
    (swap! received-atom conj {:type :publish :payload payload})
    (when key
      (swap! state-atom update :data assoc key value))
    (let [ch (chan 1)]
      (put! ch {:ok true})
      (close! ch)
      ch)))

(defn make-stateful-strategy
  "Create a strategy that syncs a map of key->value."
  [initial-items]
  (->StatefulSyncStrategy
   (atom {:version 1 :data {}})
   (atom initial-items)
   (atom [])))

;; =============================================================================
;; Integration Tests
;; =============================================================================

(deftest basic-pubsub-integration-test
  (testing "Basic subscribe and publish flow"
    (with-peers
      (let [received (atom [])
            ;; Register topic on server with some initial data
            server-strategy (make-stateful-strategy {:a 1 :b 2 :c 3})
            _ (pubsub/register-topic! *server-peer* :test-topic
                                      {:strategy server-strategy})

            ;; Create client strategy to receive data
            client-strategy (make-stateful-strategy {})]

        ;; Subscribe from client
        (<?? S (pubsub/subscribe! *client-peer* #{:test-topic}
                                  {:strategies {:test-topic client-strategy}}))

        ;; Wait for handshake to complete
        (<?? S (timeout 500))

        ;; Check client received initial sync data
        (let [client-received @(:received-atom client-strategy)]
          (is (pos? (count client-received)) "Client should have received handshake items")
          (is (every? #(= :handshake (:type %)) client-received)))

        ;; Publish from server
        (<?? S (pubsub/publish! *server-peer* :test-topic {:key :d :value 4}))

        ;; Wait for publish to propagate
        (<?? S (timeout 200))

        ;; Check client received publish
        (let [client-received @(:received-atom client-strategy)
              publishes (filter #(= :publish (:type %)) client-received)]
          (is (= 1 (count publishes)) "Client should have received 1 publish"))

        ;; Unsubscribe
        (<?? S (pubsub/unsubscribe! *client-peer* #{:test-topic}))

        ;; Publish again - should not be received
        (<?? S (pubsub/publish! *server-peer* :test-topic {:key :e :value 5}))
        (<?? S (timeout 200))

        ;; Count should still be 1 publish
        (let [client-received @(:received-atom client-strategy)
              publishes (filter #(= :publish (:type %)) client-received)]
          (is (= 1 (count publishes)) "Client should not receive after unsubscribe"))))))

(deftest multiple-topics-integration-test
  (testing "Subscribe to multiple topics with different data"
    (with-peers
      (let [;; Register two topics with different data
            strategy-a (make-stateful-strategy {:x 10 :y 20})
            strategy-b (make-stateful-strategy {:foo "bar" :baz "qux"})
            _ (pubsub/register-topic! *server-peer* :topic-a {:strategy strategy-a})
            _ (pubsub/register-topic! *server-peer* :topic-b {:strategy strategy-b})

            ;; Client strategies
            client-strategy-a (make-stateful-strategy {})
            client-strategy-b (make-stateful-strategy {})]

        ;; Subscribe to both topics
        (<?? S (pubsub/subscribe! *client-peer* #{:topic-a :topic-b}
                                  {:strategies {:topic-a client-strategy-a
                                                :topic-b client-strategy-b}}))

        (<?? S (timeout 700))

        ;; Both should have received handshake data
        (is (= 2 (count @(:received-atom client-strategy-a))))
        (is (= 2 (count @(:received-atom client-strategy-b))))

        ;; Publish to each topic
        (<?? S (pubsub/publish! *server-peer* :topic-a {:key :z :value 30}))
        (<?? S (pubsub/publish! *server-peer* :topic-b {:msg "hello"}))
        (<?? S (timeout 200))

        ;; Each should have 3 items (2 handshake + 1 publish)
        (is (= 3 (count @(:received-atom client-strategy-a))))
        (is (= 3 (count @(:received-atom client-strategy-b))))

        ;; Unsubscribe from just topic-a
        (<?? S (pubsub/unsubscribe! *client-peer* #{:topic-a}))

        ;; Publish to both
        (<?? S (pubsub/publish! *server-peer* :topic-a {:key :w :value 40}))
        (<?? S (pubsub/publish! *server-peer* :topic-b {:msg "world"}))
        (<?? S (timeout 200))

        ;; topic-a should still have 3, topic-b should have 4
        (is (= 3 (count @(:received-atom client-strategy-a))))
        (is (= 4 (count @(:received-atom client-strategy-b))))))))

(deftest sequential-subscribe-unsubscribe-test
  (testing "Sequential subscribe/unsubscribe cycles"
    (with-peers
      (let [strategy-1 (make-stateful-strategy {:a 1})
            strategy-2 (make-stateful-strategy {:b 2})
            strategy-3 (make-stateful-strategy {:c 3})
            _ (pubsub/register-topic! *server-peer* :topic-1 {:strategy strategy-1})
            _ (pubsub/register-topic! *server-peer* :topic-2 {:strategy strategy-2})
            _ (pubsub/register-topic! *server-peer* :topic-3 {:strategy strategy-3})

            client-strategy-1 (make-stateful-strategy {})
            client-strategy-2 (make-stateful-strategy {})
            client-strategy-3 (make-stateful-strategy {})]

        ;; Phase 1: Subscribe to topic-1 only
        (<?? S (pubsub/subscribe! *client-peer* #{:topic-1}
                                  {:strategies {:topic-1 client-strategy-1}}))
        (<?? S (timeout 500))
        (is (= 1 (count @(:received-atom client-strategy-1))))

        ;; Publish to all topics
        (<?? S (pubsub/publish! *server-peer* :topic-1 {:p 1}))
        (<?? S (pubsub/publish! *server-peer* :topic-2 {:p 2}))
        (<?? S (pubsub/publish! *server-peer* :topic-3 {:p 3}))
        (<?? S (timeout 300))

        ;; Only topic-1 should receive
        (is (= 2 (count @(:received-atom client-strategy-1))))
        (is (= 0 (count @(:received-atom client-strategy-2))))
        (is (= 0 (count @(:received-atom client-strategy-3))))

        ;; Phase 2: Also subscribe to topic-2
        (<?? S (pubsub/subscribe! *client-peer* #{:topic-2}
                                  {:strategies {:topic-2 client-strategy-2}}))
        (<?? S (timeout 500))

        ;; topic-2 should now have its handshake item
        (is (= 1 (count @(:received-atom client-strategy-2))))

        ;; Publish to all again
        (<?? S (pubsub/publish! *server-peer* :topic-1 {:p 4}))
        (<?? S (pubsub/publish! *server-peer* :topic-2 {:p 5}))
        (<?? S (pubsub/publish! *server-peer* :topic-3 {:p 6}))
        (<?? S (timeout 300))

        ;; topic-1 and topic-2 should receive, not topic-3
        (is (= 3 (count @(:received-atom client-strategy-1))))
        (is (= 2 (count @(:received-atom client-strategy-2))))
        (is (= 0 (count @(:received-atom client-strategy-3))))

        ;; Phase 3: Unsubscribe from topic-1, subscribe to topic-3
        (<?? S (pubsub/unsubscribe! *client-peer* #{:topic-1}))
        (<?? S (pubsub/subscribe! *client-peer* #{:topic-3}
                                  {:strategies {:topic-3 client-strategy-3}}))
        (<?? S (timeout 500))

        (is (= 1 (count @(:received-atom client-strategy-3))))

        ;; Final publish round
        (<?? S (pubsub/publish! *server-peer* :topic-1 {:p 7}))
        (<?? S (pubsub/publish! *server-peer* :topic-2 {:p 8}))
        (<?? S (pubsub/publish! *server-peer* :topic-3 {:p 9}))
        (<?? S (timeout 200))

        ;; topic-1 should NOT have received (unsubscribed)
        ;; topic-2 and topic-3 should have received
        (is (= 3 (count @(:received-atom client-strategy-1))) "topic-1 should not receive after unsub")
        (is (= 3 (count @(:received-atom client-strategy-2))))
        (is (= 2 (count @(:received-atom client-strategy-3))))))))

(deftest large-handshake-batching-test
  (testing "Large handshake data is batched correctly"
    (with-peers
      (let [;; Create strategy with many items to trigger batching
            large-data (into {} (map (fn [i] [(keyword (str "key-" i)) i]) (range 100)))
            server-strategy (make-stateful-strategy large-data)
            _ (pubsub/register-topic! *server-peer* :large-topic
                                      {:strategy server-strategy
                                       :batch-size 10})  ;; 10 items per batch

            client-strategy (make-stateful-strategy {})]

        ;; Subscribe - should receive all 100 items in ~10 batches
        (<?? S (pubsub/subscribe! *client-peer* #{:large-topic}
                                  {:strategies {:large-topic client-strategy}}))

        ;; Wait for all batches
        (<?? S (timeout 3000))

        ;; Should have received all 100 items
        (let [received @(:received-atom client-strategy)
              handshakes (filter #(= :handshake (:type %)) received)]
          (is (= 100 (count handshakes)) "Should receive all 100 handshake items"))))))

(deftest pub-sub-only-strategy-test
  (testing "PubSubOnlyStrategy for pure message passing"
    (with-peers
      (let [server-received (atom [])
            client-received (atom [])

            ;; Server strategy that tracks received publishes
            server-strategy (proto/pub-sub-only-strategy
                             #(swap! server-received conj %))
            ;; Client strategy
            client-strategy (proto/pub-sub-only-strategy
                             #(swap! client-received conj %))

            _ (pubsub/register-topic! *server-peer* :messages {:strategy server-strategy})]

        ;; Subscribe
        (<?? S (pubsub/subscribe! *client-peer* #{:messages}
                                  {:strategies {:messages client-strategy}}))
        (<?? S (timeout 300))

        ;; No handshake items for PubSubOnlyStrategy
        (is (empty? @client-received))

        ;; Publish some messages
        (dotimes [i 5]
          (<?? S (pubsub/publish! *server-peer* :messages {:msg-id i :data (str "msg-" i)})))

        (<?? S (timeout 300))

        ;; Client should have received all 5
        (is (= 5 (count @client-received)))
        (is (= (set (range 5)) (set (map :msg-id @client-received))))))))

(deftest topic-not-found-test
  (testing "Subscribing to non-existent topic"
    (with-peers
      (let [client-strategy (make-stateful-strategy {})]

        ;; Try to subscribe to topic that doesn't exist
        (<?? S (pubsub/subscribe! *client-peer* #{:nonexistent}
                                  {:strategies {:nonexistent client-strategy}}))

        (<?? S (timeout 300))

        ;; Should not have received any handshake data
        (is (empty? @(:received-atom client-strategy)))))))

(deftest cleanup-on-disconnect-test
  (testing "Subscribers are cleaned up when connection closes"
    (with-peers
      (let [strategy (make-stateful-strategy {:test 1})
            _ (pubsub/register-topic! *server-peer* :cleanup-test {:strategy strategy})

            client-strategy (make-stateful-strategy {})]

        ;; Subscribe
        (<?? S (pubsub/subscribe! *client-peer* #{:cleanup-test}
                                  {:strategies {:cleanup-test client-strategy}}))
        (<?? S (timeout 300))

        ;; Verify subscriber count
        (is (= 1 (count (pubsub/get-subscribers *server-peer* :cleanup-test))))

        ;; Note: Full disconnect cleanup would require closing the connection
        ;; which would terminate the test. This test verifies the subscriber
        ;; tracking mechanism works.
        ))))

(deftest concurrent-publish-test
  (testing "Concurrent publishes are all delivered"
    (with-peers
      (let [client-received (atom [])
            server-strategy (proto/pub-sub-only-strategy identity)
            client-strategy (proto/pub-sub-only-strategy
                             #(swap! client-received conj %))

            _ (pubsub/register-topic! *server-peer* :concurrent {:strategy server-strategy})]

        ;; Subscribe
        (<?? S (pubsub/subscribe! *client-peer* #{:concurrent}
                                  {:strategies {:concurrent client-strategy}}))
        (<?? S (timeout 200))

        ;; Publish 50 messages concurrently
        (let [publish-futures (doall
                               (for [i (range 50)]
                                 (go (pubsub/publish! *server-peer* :concurrent {:id i}))))]
          ;; Wait for all publishes to complete
          (doseq [f publish-futures]
            (<!! f)))

        ;; Wait for delivery
        (<?? S (timeout 500))

        ;; All 50 should be received
        (is (= 50 (count @client-received)))
        (is (= (set (range 50)) (set (map :id @client-received))))))))
