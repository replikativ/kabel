(ns kabel.pubsub-window-integration-test
  "Live windows end to end: bursts beyond the receiver's lane, overflow and
  resync, and peers with windows disabled standing in for older versions."
  (:require [clojure.test :refer [deftest testing is]]
            [kabel.pubsub :as pubsub]
            [kabel.pubsub.protocol :as proto]
            [kabel.peer :as peer]
            [kabel.http-kit :as http-kit]
            [kabel.pubsub-integration-test :refer [unique-port]]
            [superv.async :refer [<?? S]]
            [clojure.core.async :as async :refer [go <! >! chan put! close! timeout]]))

(defn- with-pair*
  "Run `(f server client)` with a server and a connected client peer, each with
  pubsub middleware built from its opts."
  [server-opts client-opts f]
  (let [url (str "ws://localhost:" (unique-port))
        handler (http-kit/create-http-kit-handler! S url (random-uuid))
        server (peer/server-peer S handler (random-uuid)
                                 (pubsub/make-pubsub-peer-middleware server-opts)
                                 identity)]
    (<?? S (peer/start server))
    (try
      (let [client (peer/client-peer S (random-uuid)
                                     (pubsub/make-pubsub-peer-middleware client-opts)
                                     identity)]
        (<?? S (peer/connect S client url))
        (f server client))
      (finally
        (<?? S (peer/stop server))))))

(defn- done [v] (doto (chan 1) (put! v) close!))

(defn- replica-strategy
  "A key/value replica. `-init-client-state` reports the keys held with their
  versions, the handshake sends what is newer, publishes assoc. `delay-ms`
  slows every apply, so a burst outpaces the receiver."
  [store {:keys [delay-ms applied]}]
  (reify proto/PSyncStrategy
    (-init-client-state [_]
      (done (into {} (map (fn [[k {:keys [version]}]] [k version])) @store)))
    (-handshake-items [_ client-versions]
      (let [ch (chan 100)]
        (go
          (doseq [[k v] @store
                  :when (< (get client-versions k -1) (:version v))]
            (<! (go (put! ch [k v]))))
          (close! ch))
        ch))
    (-apply-handshake-item [_ [k v]]
      (swap! store assoc k v)
      (done {:ok true}))
    (-apply-publish [_ [k v]]
      (go
        (when delay-ms (<! (timeout delay-ms)))
        (swap! store assoc k v)
        (when applied (swap! applied conj k))
        {:ok true}))))

(defn- eventually
  [ms f]
  (loop [n (quot ms 20)]
    (or (f)
        (when (pos? n)
          (Thread/sleep 20)
          (recur (dec n))))))

(defn- subscribe-and-wait!
  "Subscribe and wait for the handshake. Returns an atom counting completed
  handshakes: a resync completes another one."
  [client topic strategy]
  (let [ready (promise)
        handshakes (atom 0)]
    (is (:ok (<?? S (pubsub/subscribe! client #{topic}
                                       {:strategies {topic strategy}
                                        :on-handshake-complete
                                        (fn [_]
                                          (swap! handshakes inc)
                                          (deliver ready true))}))))
    (is (deref ready 10000 false) "handshake completes")
    handshakes))

(defn- publish-burst!
  [peer store topic n]
  (dotimes [i n]
    (let [v {:version 1 :i i}]
      (swap! store assoc i v)
      (pubsub/publish! peer topic [i v]))))

(deftest server-burst-beyond-the-lane-is-paced-not-fatal
  ;; 2000 publications against a 256-entry lane and a receiver applying at
  ;; ~1 ms each: without windows the lane overflows and the connection retires.
  (with-pair*
    {} {}
    (fn [server client]
      (let [server-store (atom {})
            client-store (atom {})
            applied (atom [])]
        (pubsub/register-topic! server :t {:strategy (replica-strategy server-store {})})
        (subscribe-and-wait! client :t (replica-strategy client-store {:delay-ms 1
                                                                       :applied applied}))
        (publish-burst! server server-store :t 2000)
        (is (eventually 30000 #(= 2000 (count @applied))) "every publication applied")
        (is (= (range 2000) @applied) "in order")
        (is (:handshake-complete? (pubsub/subscription client :t))
            "the subscription survived")))))

(deftest client-burst-is-paced-by-the-servers-window
  (with-pair*
    {} {}
    (fn [server client]
      (let [server-store (atom {})
            applied (atom [])]
        (pubsub/register-topic! server :t {:strategy (replica-strategy server-store
                                                                       {:delay-ms 1
                                                                        :applied applied})})
        (subscribe-and-wait! client :t (replica-strategy (atom {}) {}))
        (let [results (mapv (fn [i] (<?? S (pubsub/publish! client :t [i {:version 1}])))
                            (range 2000))]
          (is (every? :ok results) "publish! queues; it never fails on pressure"))
        (is (eventually 30000 #(= 2000 (count @applied))))
        (is (= (range 2000) @applied))))))

(deftest overflow-resyncs-and-the-replica-converges
  (with-pair*
    {} {}
    (fn [server client]
      (let [server-store (atom {})
            client-store (atom {})]
        (pubsub/register-topic! server :t {:strategy (replica-strategy server-store {})
                                           :max-queued-publishes 8})
        (let [handshakes (subscribe-and-wait! client :t
                                              (replica-strategy client-store {:delay-ms 2}))]
          (publish-burst! server server-store :t 500)
          (is (eventually 30000 #(and (= @server-store @client-store)
                                      (:handshake-complete? (pubsub/subscription client :t))))
              "the resync handshake delivers what the overflow dropped")
          (is (>= @handshakes 2) "a resync happened, on the same connection"))))))

(deftest repeated-overflow-escalates-to-retirement
  (with-pair*
    {} {}
    (fn [server client]
      (let [server-store (atom {})]
        (pubsub/register-topic! server :t {:strategy (replica-strategy server-store {})
                                           :max-queued-publishes 4
                                           :max-resyncs 0})
        (subscribe-and-wait! client :t (replica-strategy (atom {}) {:delay-ms 5}))
        (publish-burst! server server-store :t 200)
        (is (eventually 10000 #(nil? (pubsub/subscription client :t)))
            "the connection is retired: the loss is loud")))))

(deftest unsubscribe-drains-the-queue-before-its-marker
  (with-pair*
    {} {}
    (fn [server client]
      (let [server-store (atom {})
            applied (atom [])]
        (pubsub/register-topic! server :t {:strategy (replica-strategy server-store {})})
        (subscribe-and-wait! client :t (replica-strategy (atom {}) {:delay-ms 1
                                                                    :applied applied}))
        (publish-burst! server server-store :t 300)
        (is (= {:ok true} (<?? S (pubsub/unsubscribe! client #{:t}))))
        (is (= (range 300) @applied)
            "everything queued before the unsubscribe was applied before it settled")
        (is (empty? (pubsub/get-subscribers server :t)))))))

(deftest peers-without-windows-interoperate
  (testing "server without windows: the client detects it and sends unpaced"
    (with-pair*
      {:live-window false} {}
      (fn [server client]
        (let [server-store (atom {})
              applied (atom [])]
          (pubsub/register-topic! server :t {:strategy (replica-strategy server-store
                                                                         {:applied applied})})
          (subscribe-and-wait! client :t (replica-strategy (atom {}) {}))
          (is (= :legacy (:mode (get-in @client [:pubsub :live :modes
                                                 (get-in @client [:pubsub :out])]))))
          (dotimes [i 100] (pubsub/publish! client :t [i {:version 1}]))
          (is (eventually 10000 #(= (range 100) @applied)))
          (is (not-any? #(= :windowed (:mode (deref (:state %))))
                        (vals (get-in @server [:pubsub :live :senders])))
              "the server ignores the client's offer")))))
  (testing "client without windows: the server sends it plain frames"
    (with-pair*
      {} {:live-window false}
      (fn [server client]
        (let [server-store (atom {})
              client-store (atom {})]
          (pubsub/register-topic! server :t {:strategy (replica-strategy server-store {})})
          (subscribe-and-wait! client :t (replica-strategy client-store {}))
          (publish-burst! server server-store :t 100)
          (is (eventually 10000 #(= @server-store @client-store)))
          (is (every? #(= :legacy (:mode (deref (:state %))))
                      (vals (get-in @server [:pubsub :live :senders])))))))))

(defn- slow-handshake-strategy
  "As `replica-strategy`, but the server's handshake emits one item per
  `item-ms`, so publications arrive while it runs."
  [store item-ms]
  (let [base (replica-strategy store {})]
    (reify proto/PSyncStrategy
      (-init-client-state [_] (proto/-init-client-state base))
      (-handshake-items [_ client-versions]
        (let [ch (chan)]
          (go
            (loop [items (seq (filter (fn [[k v]] (< (get client-versions k -1) (:version v)))
                                      @store))]
              (when-let [item (first items)]
                (<! (timeout item-ms))
                (when (>! ch item)
                  (recur (next items)))))
            (close! ch))
          ch))
      (-apply-handshake-item [_ item] (proto/-apply-handshake-item base item))
      (-apply-publish [_ payload] (proto/-apply-publish base payload)))))

(deftest overflow-during-the-handshake-ends-it-with-resync
  (with-pair*
    {} {}
    (fn [server client]
      (let [server-store (atom (into {} (map (fn [i] [(- -1 i) {:version 1}])) (range 200)))
            client-store (atom {})]
        (pubsub/register-topic! server :t {:strategy (slow-handshake-strategy server-store 2)
                                           :max-queued-publishes 4})
        (let [ready (promise)
              handshakes (atom 0)]
          (is (:ok (<?? S (pubsub/subscribe! client #{:t}
                                             {:strategies {:t (replica-strategy client-store {})}
                                              :on-handshake-complete
                                              (fn [_] (swap! handshakes inc) (deliver ready true))}))))
          ;; The client holds publications behind the snapshot and acks none
          ;; of them yet: window plus queue fill during the handshake.
          (Thread/sleep 50)
          (publish-burst! server server-store :t 300)
          (is (deref ready 30000 false))
          (is (eventually 30000 #(= @server-store @client-store)))
          (is (= 1 @handshakes)
              "the overflowed handshake never completed; the resync's one did"))))))

(deftest unsubscribe-while-the-resubscription-initializes
  ;; The resync arrived and the client is computing its state for the new
  ;; subscribe when the application unsubscribes. The drain must settle the
  ;; unsubscribe; it must not retire the connection and its other topics.
  (with-pair*
    {} {}
    (fn [server client]
      (let [server-store (atom {})
            client-store (atom {})
            inits (atom 0)
            release (promise)
            base (replica-strategy client-store {:delay-ms 2})
            gated (reify proto/PSyncStrategy
                    (-init-client-state [_]
                      (if (= 1 (swap! inits inc))
                        (proto/-init-client-state base)
                        (go (<! (async/thread (deref release 10000 nil)))
                            (<! (proto/-init-client-state base)))))
                    (-handshake-items [_ cs] (proto/-handshake-items base cs))
                    (-apply-handshake-item [_ i] (proto/-apply-handshake-item base i))
                    (-apply-publish [_ p] (proto/-apply-publish base p)))]
        (pubsub/register-topic! server :t {:strategy (replica-strategy server-store {})
                                           :max-queued-publishes 8})
        (pubsub/register-topic! server :other {:strategy (replica-strategy (atom {}) {})})
        (subscribe-and-wait! client :other (replica-strategy (atom {}) {}))
        (subscribe-and-wait! client :t gated)
        (publish-burst! server server-store :t 500)
        (is (eventually 10000 #(= 2 @inits)) "the resync started a resubscription")
        (is (= {:ok true} (<?? S (pubsub/unsubscribe! client #{:t}))))
        (deliver release true)
        (Thread/sleep 200)
        (is (nil? (pubsub/subscription client :t)) "the resubscription stood down")
        (is (:handshake-complete? (pubsub/subscription client :other))
            "the connection and its other topic survived")
        (is (empty? (pubsub/get-subscribers server :t)))))))
