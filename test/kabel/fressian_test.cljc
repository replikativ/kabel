(ns kabel.fressian-test
  (:require
   #?(:clj [clojure.test :refer :all]
      :cljs [cljs.test :refer-macros [deftest is testing run-tests async run-all-tests]])
   #?(:clj [superv.async :refer [<?? go-try S go-loop-try <? >? put?]]
      :cljs [superv.async :refer [go-try S go-loop-try <? >? put?]
             :include-macros true])
   #?(:clj [kabel.http-kit :as http-kit])
   #?(:clj [kabel.peer :as peer])
   #?(:clj [clojure.data.fressian :as fress]
      :cljs [fress.api :as fress])
   [clojure.core.async :refer [timeout chan alts! >!]]
   [kabel.middleware.fressian :refer [fressian merge-read-handlers merge-write-handlers]])
  #?(:cljs (:require-macros [clojure.core.async :refer [go go-loop alt!]])))

#?(:cljs (def byte-array #(-> % into-array js/Uint8Array.)))

#?(:cljs (defn uint->vec [arr] (vec (.call (.-slice (js/Array.)) arr))))

(deftest fressian-test
  (testing "Checking simple fressian encoding in both directions (non map)."
    (let [in (chan)
          out (chan)
          [_ _ [tin tout]] (fressian [S nil [in out]])]
      (put? S tout [1 :fressian "string"])
      #?(:clj
         (let [result (<?? S out)
               payload (:kabel/payload result)]
           (is (= (:kabel/serialization result) :fressian))
           (is (some? payload))
           (is (bytes? payload))))
      (put? S in {:kabel/serialization :fressian
                  :kabel/payload
                  #?(:clj (let [baos (java.io.ByteArrayOutputStream.)
                                writer (fress/create-writer baos)]
                            (fress/write-object writer [1 :fressian "string"])
                            (.toByteArray baos))
                     :cljs (fress/write [1 :fressian "string"]))})
      #?(:clj (is (= (<?? S tin) [1 :fressian "string"]))
         :cljs
         (async done
                (go-try S
                        (let [result (<? S out)
                              payload (:kabel/payload result)]
                          (is (= (:kabel/serialization result) :fressian))
                          (is (some? payload)))
                        (is (= (<? S tin) [1 :fressian "string"]))
                        (done)))))))

(deftest fressian-map-test
  (testing "Map pass through with merging."
    (let [in (chan)
          out (chan)
          [_ _ [tin tout]] (fressian [S nil [in out]])]
      (put? S tout {:type :some/publication :value 42})
      #?(:clj
         (let [result (<?? S out)]
           (is (= (:kabel/serialization result) :fressian))
           (is (some? (:kabel/payload result)))))
      (put? S in {:kabel/serialization :fressian
                  :kabel/host "1.2.3.4"
                  :kabel/payload
                  #?(:clj (let [baos (java.io.ByteArrayOutputStream.)
                                writer (fress/create-writer baos)]
                            (fress/write-object writer {:type :some/publication :value 42})
                            (.toByteArray baos))
                     :cljs (fress/write {:type :some/publication :value 42}))})
      #?(:clj (is (= (<?? S tin) {:type :some/publication,
                                  :kabel/host "1.2.3.4"
                                  :value 42}))
         :cljs (async done
                      (go-try S
                              (let [result (<? S out)]
                                (is (= (:kabel/serialization result) :fressian))
                                (is (some? (:kabel/payload result))))
                              (is (= (<? S tin) {:type :some/publication,
                                                 :kabel/host "1.2.3.4"
                                                 :value 42}))
                              (done)))))))

;; Custom record type for testing handlers
(defrecord CustomTestRecord [custom-type data])

(deftest fressian-custom-handlers-test
  (testing "Custom read/write handlers work correctly."
    (let [;; Custom type for testing (using defrecord to avoid recursion)
          test-value (->CustomTestRecord :test "hello")

          ;; Custom write handler - serialize as tagged map
          custom-write-handlers
          (atom {#?(:clj kabel.fressian_test.CustomTestRecord
                    :cljs kabel.fressian-test/CustomTestRecord)
                 {"custom-test-record"
                  #?(:clj (reify org.fressian.handlers.WriteHandler
                            (write [_ writer val]
                              (.writeTag writer "custom-test-record" 1)
                              ;; Write as a plain map to avoid recursion
                              (.writeObject writer {:custom-type (:custom-type val)
                                                    :data (:data val)
                                                    :custom-marker true})))
                     :cljs (fn [writer val]
                             (fress/write-tag writer "custom-test-record" 1)
                             (fress/write-object writer {:custom-type (:custom-type val)
                                                         :data (:data val)
                                                         :custom-marker true})))}})

          ;; Custom read handler - read map and add marker
          custom-read-handlers
          (atom {"custom-test-record"
                 #?(:clj (reify org.fressian.handlers.ReadHandler
                           (read [_ reader _tag _component-count]
                             (let [m (.readObject reader)]
                               (assoc m :was-custom true))))
                    :cljs (fn [reader _tag _component-count]
                            (let [m (fress/read-object reader)]
                              (assoc m :was-custom true))))})

          in (chan)
          out (chan)
          [_ _ [tin tout]] (fressian custom-read-handlers custom-write-handlers [S nil [in out]])]

      (put? S tout test-value)

      #?(:clj
         (let [result (<?? S out)
               ;; Deserialize to check custom handler was applied
               ;; Use the same handler merging as the middleware
               merged-handlers (merge-read-handlers @custom-read-handlers (atom {}))
               bytes (:kabel/payload result)
               deserialized (fress/read bytes :handlers merged-handlers)]
           (is (= (:kabel/serialization result) :fressian))
           (is (= :test (:custom-type deserialized)))
           (is (= "hello" (:data deserialized)))
           (is (= true (:custom-marker deserialized)) "Write handler should add custom-marker")
           (is (= true (:was-custom deserialized)) "Read handler should add was-custom"))

         :cljs
         (async done
                (go-try S
                        (let [result (<? S out)
                              merged-handlers (merge-read-handlers @custom-read-handlers (atom {}))
                              bytes (:kabel/payload result)
                              deserialized (fress/read bytes :handlers merged-handlers)]
                          (is (= (:kabel/serialization result) :fressian))
                          (is (= :test (:custom-type deserialized)))
                          (is (= "hello" (:data deserialized)))
                          (is (= true (:custom-marker deserialized)) "Write handler should add custom-marker")
                          (is (= true (:was-custom deserialized)) "Read handler should add was-custom"))
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
   (deftest fressian-roundtrip-test
     (testing "Testing a roundtrip with fressian between a server and a client."
       (let [sid #uuid "fd0278e4-081c-4925-abb9-ff4210be271b"
             cid #uuid "898dcf36-e07a-4338-92fd-f818d573444a"
             url "ws://localhost:47294"
             handler (http-kit/create-http-kit-handler! S url sid)
             speer (peer/server-peer S handler sid pong-middleware fressian)
             cpeer (peer/client-peer S cid (fn [[S peer [in out]]]
                                             (let [new-in (chan)
                                                   new-out (chan)]
                                               (go-try S
                                                       (put? S out "ping")
                                                       (is (= "ping" (<? S in)))
                                                       (put? S out {:msg "ping2" :data [1 2 3]})
                                                       (let [received (<? S in)]
                                                         (is (= "ping2" (:msg received)))
                                                         (is (= [1 2 3] (:data received)))))
                                               [S peer [new-in new-out]]))
                                     fressian)]
         (<?? S (peer/start speer))
         (<?? S (peer/connect S cpeer url))
         (<?? S (timeout 1000))
         (<?? S (peer/stop speer))))))

(defn ^:export run []
  (run-tests))
