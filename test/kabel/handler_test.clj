(ns kabel.handler-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.core.async :as async :refer [chan go put! timeout alts!!]]
            [clojure.core.async.impl.protocols :as async-proto]
            [kabel.middleware.handler :refer [handler]]
            [superv.async :refer [S go-try]]))

(defn- closed-within? [ch ms]
  (loop [n (quot ms 10)]
    (cond (async-proto/closed? ch) true
          (zero? n) false
          :else (do (Thread/sleep 10) (recur (dec n))))))

(deftest throwing-in-callback-closes-the-inbound-side-test
  (let [in (chan 10)
        out (chan 10)
        [_ _ [new-in _]] (handler (fn [m] (go-try S (if (= m :bad)
                                                      (throw (ex-info "undecodable" {}))
                                                      m)))
                                  (fn [m] (go m))
                                  [S nil [in out]])]
    (put! in :ok)
    (is (= :ok (first (alts!! [new-in (timeout 1000)]))))
    (put! in :bad)
    (is (closed-within? new-in 1000)
        "new-in closes, so the middleware above retires the connection")))

(deftest throwing-out-callback-closes-the-outbound-side-test
  (let [in (chan 10)
        out (chan 10)
        [_ _ [_ new-out]] (handler (fn [m] (go m))
                                   (fn [m] (go-try S (if (= m :bad)
                                                       (throw (ex-info "unencodable" {}))
                                                       m)))
                                   [S nil [in out]])]
    (put! new-out :ok)
    (is (= :ok (first (alts!! [out (timeout 1000)]))))
    (put! new-out :bad)
    (is (closed-within? out 1000) "the connection's out closes")
    (is (closed-within? new-out 1000) "later writes fail instead of piling up")))
