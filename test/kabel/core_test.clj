(ns kabel.core-test
  (:require [clojure.test :refer :all]
            [kabel.client :as cli]
            [kabel.http-kit :as http-kit]
            [kabel.peer :as peer]
            [superv.async :refer [<?? go-try S go-loop-try <? >? put?]]
            [clojure.core.async :refer [timeout]]
            [hasch.core :refer [uuid]]))


(defn pong-middleware [[S peer [in out]]]
  (go-loop-try S [i (<? S in)]
               (when i
                 (>? S out i)
                 (recur (<? S in))))
  [S peer [in out]])

(deftest roundtrip-test
  (testing "Testing a roundtrip between a server and a client."
    (let [sid #uuid "fd0278e4-081c-4925-abb9-ff4210be271b"
          cid #uuid "898dcf36-e07a-4338-92fd-f818d573444a"
          url "ws://localhost:47291"
          handler (http-kit/create-http-kit-handler! S url sid)
          speer (peer/server-peer S handler sid pong-middleware)
          cpeer (peer/client-peer S cid (fn [[S peer [in out]]]
                                          (put? S out {:type :ping}) 
                                          (is (= (<?? S in)
                                                 {:type :ping
                                                  :sender sid
                                                  :host "localhost"}))
                                          [S peer [in out]]))]
      (<?? S (peer/start speer))
      (<?? S (peer/connect S cpeer url))
      (<?? S (timeout 1000))
      (<?? S (peer/stop speer)))))
