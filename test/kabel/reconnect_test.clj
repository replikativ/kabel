(ns kabel.reconnect-test
  "`kabel.peer/maintain` against a real http-kit server that goes away and
   comes back."
  (:require [clojure.test :refer [deftest is testing]]
            [kabel.http-kit :as http-kit]
            [kabel.peer :as peer]
            [kabel.remote :as remote]
            [superv.async :refer [<?? S go-try <?]]
            [clojure.core.async :refer [chan <!! timeout alts!! close!]]))

(def ^:private server-id #uuid "7d1e0d2e-2f5a-4d6c-9a1f-1c2b3d4e5f60")
(def ^:private client-id #uuid "0b6a2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d")

(defn- echo-middleware [[S peer [in out]]]
  (let [new-in (chan)]
    (clojure.core.async/go-loop [m (clojure.core.async/<! in)]
      (when m
        (clojure.core.async/>! out m)
        (recur (clojure.core.async/<! in))))
    [S peer [new-in out]]))

(defn- start-server! [url]
  (let [handler (http-kit/create-http-kit-handler! S url server-id)
        server (peer/server-peer S handler server-id remote/middleware identity)]
    (remote/serve server)
    (<?? S (peer/start server))
    server))

(defn- await-status
  "Block until `statuses` has seen `status`, or fail after 5 s."
  [statuses status]
  (let [deadline (+ (System/currentTimeMillis) 5000)]
    (loop []
      (cond
        (some #(= status (:status %)) @statuses) true
        (> (System/currentTimeMillis) deadline) false
        :else (do (Thread/sleep 20) (recur))))))

(deftest maintain-reconnects-test
  (remote/register! 'kabel.reconnect-test/ping (fn [_] :pong))
  (let [url "ws://localhost:47297"
        statuses (atom [])
        server (start-server! url)
        client (peer/client-peer S client-id remote/middleware identity)
        handle (peer/maintain S client url {:on-status #(swap! statuses conj %)
                                            :backoff {:initial-ms 100 :max-ms 200 :jitter 0}})]
    (try
      (testing "the first connection is reported and works"
        (is (await-status statuses :connected))
        (is (= :pong (<?? S (remote/invoke client server-id 'kabel.reconnect-test/ping {})))))
      (testing "losing the server is reported, and so is every failed attempt"
        (<?? S (peer/stop server))
        (is (await-status statuses :disconnected))
        (is (await-status statuses :failed)))
      (testing "the server coming back is found by the backoff loop"
        (reset! statuses [])
        (let [server (start-server! url)]
          (try
            (is (await-status statuses :connected))
            (is (= :pong (<?? S (remote/invoke client server-id 'kabel.reconnect-test/ping {} {:timeout-ms 2000}))))
            (finally
              (<?? S (peer/stop server))))))
      (testing "stop! ends the loop and the last status is :stopped"
        ((:stop! handle))
        (let [[_ port] (alts!! [(:done handle) (timeout 5000)])]
          (is (= port (:done handle))))
        (is (= :stopped (:status @(:status handle)))))
      (finally
        ((:stop! handle))))))

(deftest maintain-gives-up-test
  (let [statuses (atom [])
        client (peer/client-peer S (random-uuid) identity identity)
        handle (peer/maintain S client "ws://localhost:47298"
                              {:on-status #(swap! statuses conj %)
                               :backoff {:initial-ms 20 :max-ms 40 :jitter 0}
                               :max-attempts 3})]
    (let [[_ port] (alts!! [(:done handle) (timeout 10000)])]
      (is (= port (:done handle))))
    (is (= 3 (count (filter #(= :failed (:status %)) @statuses))))
    (is (= {:status :stopped :reason :gave-up}
           (select-keys @(:status handle) [:status :reason])))))
