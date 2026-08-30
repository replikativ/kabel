(ns kabel.ring-ws-limits-test
  (:require [clojure.core.async :as async]
            [clojure.test :refer [deftest is testing]]
            [kabel.ring-ws :as ring-ws]
            [ring.websocket.protocols :as wsp]
            [superv.async :refer [S]]))

(defn- wait-for [timeout-ms predicate]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (cond
        (predicate) true
        (< (System/currentTimeMillis) deadline)
        (do (Thread/sleep 5) (recur))
        :else false))))

(defn- socket [sends closes]
  (reify
    wsp/Socket
    (-open? [_] true)
    (-send [_ message]
      (swap! sends conj {:message message :mode :sync}))
    (-ping [_ _] nil)
    (-pong [_ _] nil)
    (-close [_ code reason]
      (swap! closes conj [code reason]))

    wsp/AsyncSocket
    (-send-async [_ message succeed fail]
      (swap! sends conj {:message message :mode :async
                         :succeed succeed :fail fail}))))

(defn- connection
  ([] (connection {}))
  ([opts]
   (let [handler (ring-ws/create-ws-handler!
                  S "ws://localhost:49999" :peer (atom {}) (atom {})
                  (assoc opts :run-server (fn [_ _] (fn [& _]))))
         response ((:handler handler) {:remote-addr "test"})
         channels (async/<!! (:new-conns handler))]
     {:handler handler
      :listener (:ring.websocket/listener response)
      :channels channels})))

(deftest raw-server-output-is-bounded-before-a-socket-opens
  (let [{[_ out] :channels} (connection {:out-buffer-items 2})]
    (is (true? (async/offer! out :one)))
    (is (true? (async/offer! out :two)))
    (is (not (true? (async/offer! out :three))))))

(deftest asynchronous-server-writes-have-one-operation-in-flight
  (let [{:keys [listener channels]} (connection)
        [_ out] channels
        sends (atom [])
        closes (atom [])
        ws (socket sends closes)]
    (wsp/on-open listener ws)
    (async/>!! out {:kabel/serialization :string :kabel/payload "one"})
    (async/>!! out {:kabel/serialization :string :kabel/payload "two"})
    (is (wait-for 1000 #(= 1 (count @sends))))
    (is (= "one" (:message (first @sends))))
    ((:succeed (first @sends)))
    (is (wait-for 1000 #(= 2 (count @sends))))
    (is (= "two" (:message (second @sends))))
    ((:succeed (second @sends)))
    (async/close! out)
    (is (wait-for 1000 #(seq @closes)))))

(deftest oversized-frames-never-reach-the-server-socket
  (let [{:keys [listener channels]}
        (connection {:max-frame-bytes 8})
        [_ out] channels
        sends (atom [])
        closes (atom [])
        ws (socket sends closes)]
    (wsp/on-open listener ws)
    (async/>!! out {:kabel/serialization :string :kabel/payload "123456789"})
    (is (wait-for 1000 #(seq @closes)))
    (is (empty? @sends))
    (is (= 1009 (ffirst @closes)))))

(deftest incoming-frame-and-queue-limits-close-the-connection
  (testing "frame bytes are checked before decode or admission"
    (let [{:keys [listener channels]} (connection {:max-frame-bytes 8})
          sends (atom [])
          closes (atom [])
          ws (socket sends closes)]
      (wsp/on-open listener ws)
      (wsp/on-message listener ws "123456789")
      (is (= 1009 (ffirst @closes)))
      (wsp/on-close listener ws 1009 "test")
      (async/close! (first channels))))

  (testing "a full inbound lane rejects instead of accumulating pending puts"
    (let [{:keys [listener channels]} (connection)
          [in _] channels
          sends (atom [])
          closes (atom [])
          ws (socket sends closes)]
      (wsp/on-open listener ws)
      (dotimes [_ 1025]
        (wsp/on-message listener ws "x"))
      (is (= 1024
             (loop [n 0]
               (if (some? (async/poll! in))
                 (recur (inc n))
                 n))))
      (is (= 1013 (ffirst @closes)))
      (wsp/on-close listener ws 1013 "test"))))
