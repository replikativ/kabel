(ns kabel.wamp-test
  (:require [clojure.test :refer :all]
            [kabel.client :as cli]
            [kabel.http-kit :as http-kit]
            [kabel.peer :as peer]
            [kabel.middleware.wamp :refer :all]
            [superv.async :refer [<?? go-try S go-loop-try <? >? put?]]
            [clojure.core.async :refer [timeout go go-loop <! >! >!! put! chan]
             :as async]
            [kabel.middleware.json :refer [json]]
            [hasch.core :refer [uuid]]))

;; TODO do not abuse poloniex

#_(deftest wamp-test
  (testing "Testing a simple subscription on the wamp middleware."
    (let [event-ch (chan)
          client (peer/client-peer S #uuid "898dcf36-e07a-4338-92fd-f818d573444a"
                                   (partial wamp-middleware
                                            ["ticker"]
                                            "realm1"
                                            #(put! event-ch %))
                                   json)]
      (<?? S (peer/connect S client "wss://api.poloniex.com/"))
      (is (= (take 2 (<?? S event-ch)) [:EVENT "ticker"])))))


(comment

  (defn foo [m]
    (prn "FOO" m))



;; require websocket subprotocol support


)

