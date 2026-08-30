(ns kabel.metrics-test
  (:require #?(:clj [clojure.core.async :refer [<!! >!! chan close!]])
            [clojure.test :refer [deftest is testing use-fixtures]]
            [kabel.metrics :as kabel-metrics]
            #?(:clj [kabel.pubsub :as pubsub])
            #?(:clj [kabel.pubsub.protocol :as proto])
            [replikativ.metrics :as metrics]
            #?(:clj [superv.async :refer [S]])))

(use-fixtures :each
  (fn [f]
    (metrics/reset!)
    (kabel-metrics/describe!)
    (f)))

(defn- series [metric labels]
  (get-in (metrics/snapshot) [metric :series labels]))

(deftest wire-size-is-the-websocket-application-size
  (testing "binary framing includes its four-byte serialization id"
    (is (= 7 (kabel-metrics/wire-size
              {:kabel/serialization :cbor
               :kabel/payload #?(:clj (byte-array [1 2 3])
                                 :cljs (js/Uint8Array. #js [1 2 3]))}))))
  (testing "text is counted as UTF-8, not UTF-16 code units"
    (is (= 4 (kabel-metrics/wire-size
              {:kabel/serialization :string :kabel/payload "🙂"}))))
  (testing "the codec-free fallback matches the binary pr-str path"
    (is (= 14 (kabel-metrics/wire-size {:type :x})))))

#?(:clj
   (deftest middleware-counts-logical-messages-wire-bytes-and-lifecycle
     (let [raw-in (chan)
           raw-out (chan)
           peer (atom {:id :client})
           [_ _ [logical-in logical-out]] (kabel-metrics/messages
                                           (kabel-metrics/wire [S peer [raw-in raw-out]]))
           inbound {:kabel/serialization :cbor
                    :kabel/payload (byte-array [1 2 3])
                    :type :query}
           outbound {:kabel/serialization :string
                     :kabel/payload "ok"
                     :type :result}]
       (>!! raw-in inbound)
       (is (= inbound (<!! logical-in)))
       (>!! logical-out outbound)
       (is (= outbound (<!! raw-out)))
       (close! raw-in)
       (is (nil? (<!! logical-in)))

       (is (= 1 (series :kabel_messages_total {:direction "in" :type "query"})))
       (is (= 1 (series :kabel_messages_total {:direction "out" :type "result"})))
       (is (= 7 (series :kabel_wire_bytes_total
                        {:direction "in" :serialization "cbor"})))
       (is (= 2 (series :kabel_wire_bytes_total
                        {:direction "out" :serialization "string"})))
       (is (= 1 (series :kabel_peer_connection_events_total
                        {:side "client" :event "connect"})))
       (is (= 1 (series :kabel_peer_connection_events_total
                        {:side "client" :event "disconnect"})))

       ;; Reusing a client peer distinguishes a reconnect without URL/id labels.
       (let [in-2 (chan)
             out-2 (chan)
             [_ _ [observed-in _]] (kabel-metrics/messages [S peer [in-2 out-2]])]
         (close! in-2)
         (is (nil? (<!! observed-in)))
         (is (= 1 (series :kabel_peer_connection_events_total
                          {:side "client" :event "reconnect"})))))))

(deftest subscription-events-are-aggregated-without-topic-labels
  (kabel-metrics/subscription-event! :server :subscribe 3)
  (kabel-metrics/subscription-event! :server :unsubscribe 1)
  (is (= 3 (series :kabel_pubsub_subscription_events_total
                   {:side "server" :event "subscribe"})))
  (is (= 1 (series :kabel_pubsub_subscription_events_total
                   {:side "server" :event "unsubscribe"}))))

#?(:clj
   (deftest direct-pubsub-records-client-subscription-lifecycle
     (let [out (chan 4)
           peer (atom {:volatile {:supervisor S}
                       :pubsub {:out out}})
           strategy (proto/pub-sub-only-strategy nil)]
       (is (= #{:topic}
              (:ok (<!! (pubsub/direct-subscribe!
                         peer #{:topic} {:strategies {:topic strategy}})))))
       (is (= 1 (series :kabel_pubsub_subscription_events_total
                        {:side "client" :event "subscribe"})))
       (is (:ok (<!! (pubsub/unsubscribe! peer #{:topic}))))
       (is (= 1 (series :kabel_pubsub_subscription_events_total
                        {:side "client" :event "unsubscribe"}))))))
