(ns kabel.transit-test
  (:require
   #?(:clj [clojure.test :refer :all]
      :cljs [cljs.test :refer-macros [deftest is testing run-tests async run-all-tests]])
   [superv.async :refer [#?(:clj <??) go-try S go-loop-try <? >? put?]
    #?@(:cljs [:include-macros true])]
   #?(:clj [kabel.http-kit :as http-kit])
   #?(:clj [kabel.peer :as peer])
   [#?(:clj clojure.core.async :cljs cljs.core.async) :refer [timeout chan]]
   [kabel.middleware.transit :refer [transit]]))

#?(:cljs (def byte-array #(-> % into-array js/Uint8Array.)))

#?(:cljs (defn uint->vec [arr] (vec (.call (.-slice (js/Array.)) arr))))

(deftest transit-test
  (testing "Checking some simple transit encoding in both directions (non map)."
    (let [in (chan)
          out (chan)
          [_ _ [tin tout]] (transit [S nil [in out]])]
      (put? S tout [1 :transit "string"])
      #?(:clj
         (is (= (update (<?? S out) :kabel/payload vec)
                {:kabel/serialization :transit-json
                 :kabel/payload
                 [91 49 44 34 126 58 116 114 97 110 115 105
                  116 34 44 34 115 116 114 105 110 103 34 93]})))
      (put? S in {:kabel/serialization :transit-json
                  :kabel/payload
                  (byte-array [91 49 44 34 126 58 116 114 97 110 115 105
                               116 34 44 34 115 116 114 105 110 103 34 93])})
      #?(:clj (is (= (<?? S tin) [1 :transit "string"]))
         :cljs
         (async done
                (go-try S
                        (is (= (update (<? S out) :kabel/payload uint->vec)
                               {:kabel/serialization :transit-json
                                :kabel/payload
                                [91 49 44 34 126 58 116 114 97 110 115 105
                                 116 34 44 34 115 116 114 105 110 103 34 93]}))
                        (is (= (<? S tin) [1 :transit "string"]))
                        (done)))))))


(deftest transit-map-test
  (testing "Map pass through with merging."
      (let [in (chan)
            out (chan)
            [_ _ [tin tout]] (transit [S nil [in out]])]
        (put? S tout {:type :some/publication :value 42})
        #?(:clj
           (is (= (update (<?? S out) :kabel/payload vec)
                  {:kabel/serialization :transit-json
                   :kabel/payload
                   [91 34 94 32 34 44 34 126 58 116 121 112 101 34 44 34 126 58 115 111 109
                    101 47 112 117 98 108 105 99 97 116 105 111 110 34 44 34 126 58 118 97
                    108 117 101 34 44 52 50 93]})))
        (put? S in {:kabel/serialization :transit-json
                    :kabel/host "1.2.3.4"
                    :kabel/payload
                    (byte-array [91 34 94 32 34 44 34 126 58 116 121 112 101 34 44 34 126 58 115 111 109
                                 101 47 112 117 98 108 105 99 97 116 105 111 110 34 44 34 126 58 118 97
                                 108 117 101 34 44 52 50 93])})
        #?(:clj (is (= (<?? S tin) {:type :some/publication,
                                    :kabel/host "1.2.3.4"
                                    :value 42}))
           :cljs (async done
                        (go-try S
                                (is (= (update (<? S out) :kabel/payload uint->vec)
                                       {:kabel/serialization :transit-json
                                        :kabel/payload
                                        [91 34 94 32 34 44 34 126 58 116 121 112 101 34 44 34 126 58 115 111 109
                                         101 47 112 117 98 108 105 99 97 116 105 111 110 34 44 34 126 58 118 97
                                         108 117 101 34 44 52 50 93]}))
                                (is (= (<? S tin) {:type :some/publication,
                                                    :kabel/host "1.2.3.4"
                                                    :value 42}))
                                (done)))))))

(defn pong-middleware [[S peer [in out]]]
  (let [new-in (chan)
        new-out (chan)]
    (go-loop-try S [i (<? S in)]
      (when i
        (>? S out i)
        (recur (<? S in))))
    [S peer [new-in new-out]]))

#?(:clj
   (deftest transit-roundtrip-test
     (testing "Testing a roundtrip with transit between a server and a client."
       (let [sid #uuid "fd0278e4-081c-4925-abb9-ff4210be271b"
             cid #uuid "898dcf36-e07a-4338-92fd-f818d573444a"
             url "ws://localhost:47291"
             handler (http-kit/create-http-kit-handler! S url sid)
             speer (peer/server-peer S handler sid pong-middleware transit)
             cpeer (peer/client-peer S cid (fn [[S peer [in out]]]
                                             (let [new-in (chan)
                                                   new-out (chan)]
                                               (go-try S
                                                       (put? S out "ping")
                                                       (is (= "ping" (<? S in)))
                                                       (put? S out "ping2")
                                                       (is (= "ping2" (<? S in))))
                                               [S peer [new-in new-out]]))
                                     transit)]
         (<?? S (peer/start speer))
         (<?? S (peer/connect S cpeer url))
         (<?? S (timeout 1000))
         (<?? S (peer/stop speer)))))
   )


(defn ^:export run []
  (run-tests))
