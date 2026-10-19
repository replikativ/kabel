(ns kabel.missionary-compat-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.core.async :as a]
            [kabel.remote.missionary :as missionary]))

(defn- observe [ch]
  (let [result (promise)]
    ((missionary/chan->task ch)
     #(deliver result {:value %}) #(deliver result {:error %}))
    result))

(deftest channel-bridge-accepts-immediate-deferred-and-closed-values
  (let [ch (a/chan 1)]
    (a/>!! ch :buffered)
    (is (= {:value :buffered} (deref (observe ch) 2000 :timed-out))))
  (let [ch (a/chan) result (observe ch)]
    (a/put! ch :deferred)
    (is (= {:value :deferred} (deref result 2000 :timed-out))))
  (let [ch (a/chan)]
    (a/close! ch)
    (is (= {:value nil} (deref (observe ch) 2000 :timed-out)))))

(deftest channel-bridge-preserves-an-exception-as-task-failure
  (let [ch (a/chan 1) error (ex-info "Original failure" {:fixture true})]
    (a/>!! ch error)
    (is (identical? error (:error (deref (observe ch) 2000 :timed-out))))))
