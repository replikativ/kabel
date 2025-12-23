(ns kabel.integration-test
  "Cross-platform integration test - Node.js client connecting to JVM server."
  (:require [cljs.test :refer-macros [deftest is testing async]]
            [clojure.core.async :refer [timeout chan]]
            [superv.async :refer [go-try S <? >? put?]
             :include-macros true]
            [kabel.peer :as peer]
            [kabel.middleware.fressian :refer [fressian]]))

(def url "ws://localhost:47295")
(def client-id #uuid "c14c628b-b151-4967-ae0a-7c83e5622d0f")

(deftest ^:integration fressian-cross-platform-roundtrip
  (testing "Node.js client connecting to JVM server with fressian middleware"
    (async done
           (go-try S
                   (let [messages (atom [])
                         client (peer/client-peer
                                 S
                                 client-id
                                 (fn [[S peer [in out]]]
                                   (let [new-in (chan)
                                         new-out (chan)]
                                     (go-try S
                            ;; Send test messages
                                             (>? S out {:type :ping :value "hello"})
                                             (let [response1 (<? S in)]
                                               (swap! messages conj response1)
                                               (is (= (:type response1) :ping))
                                               (is (= (:value response1) "hello")))

                                             (>? S out {:type :ping2 :data [1 2 3]})
                                             (let [response2 (<? S in)]
                                               (swap! messages conj response2)
                                               (is (= (:type response2) :ping2))
                                               (is (= (:data response2) [1 2 3])))

                            ;; Wait a bit before cleanup
                                             (<? S (timeout 100)))
                                     [S peer [new-in new-out]]))
                                 fressian)]

          ;; Connect to server
                     (<? S (peer/connect S client url))

          ;; Wait for test to complete
                     (<? S (timeout 500))

          ;; Verify we got responses
                     (is (= 2 (count @messages)))

                     (done))))))

(defn ^:export run []
  (cljs.test/run-tests))
