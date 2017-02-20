(ns kabel.core-test
  (:require [clojure.test :refer :all]
            [kabel.client :as cli]
            [kabel.http-kit :as http-kit]
            [kabel.peer :as peer]
            [superv.async :refer [<?? go-try S go-loop-try <? >? put?]]
            [clojure.core.async :refer [timeout go go-loop <! >! <!! put! chan]]
            [kabel.middleware.transit :refer [transit]]
            [hasch.core :refer [uuid]]))


(defn pong-middleware [[S peer [in out]]]
  (let [new-in (chan)
        new-out (chan)]
    (go-loop [i (<! in)]
      (when i
        (>! out i)
        (recur (<! in))))
    [S peer [new-in new-out]]))

(deftest roundtrip-test
  (testing "Testing a roundtrip between a server and a client."
    (let [sid #uuid "fd0278e4-081c-4925-abb9-ff4210be271b"
          cid #uuid "898dcf36-e07a-4338-92fd-f818d573444a"
          url "ws://localhost:47291"
          handler (http-kit/create-http-kit-handler! S url sid)
          speer (peer/server-peer S handler sid pong-middleware identity)
          cpeer (peer/client-peer S cid
                                  (fn [[S peer [in out]]]
                                    (let [new-in (chan)
                                          new-out (chan)]
                                      (go-try S
                                              (put? S out "ping")
                                              (is (= "ping" (<? S in)))
                                              (put? S out "ping2")
                                              (is (= "ping2" (<? S in))))
                                      [S peer [new-in new-out]]))
                                  identity)]
      (<?? S (peer/start speer))
      (<?? S (peer/connect S cpeer url))
      (<?? S (timeout 1000))
      (<?? S (peer/stop speer)))))


(deftest stress-test
  (testing "Pushing a thousand messages through."
    (let [sid #uuid "fd0278e4-081c-4925-abb9-ff4210be271b"
          cid #uuid "898dcf36-e07a-4338-92fd-f818d573444a"
          url "ws://localhost:47292"
          handler (http-kit/create-http-kit-handler! S url sid)
          speer (peer/server-peer S handler sid pong-middleware identity)
          cpeer (peer/client-peer S cid (fn [[S peer [in out]]]
                                          (let [new-in (chan)
                                                new-out (chan)]
                                            (go-try S
                                              (doseq [i (range 100)]
                                                (>? S out i))
                                              (doseq [i (range 100)]
                                                (is (= i (<? S in)))))
                                            [S peer [new-in new-out]]))
                                  identity)]
      (<?? S (peer/start speer))
      (<?? S (peer/connect S cpeer url))
      (<?? S (timeout 2000))
      (<?? S (peer/stop speer)))))
