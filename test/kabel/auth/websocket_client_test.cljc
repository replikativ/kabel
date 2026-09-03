(ns kabel.auth.websocket-client-test
  "The client side of `kabel.auth.websocket` against a scripted server: what
   it sends, what it does with the answers, on both platforms."
  (:require [clojure.test :refer [deftest testing is #?(:cljs async)]]
            [kabel.auth.websocket :as auth]
            [kabel.peer :as peer]
            #?(:clj [superv.async :refer [S go-try <? <??]]
               :cljs [superv.async :refer [S go-try <?]])
            [clojure.core.async :as async :refer [chan close! put! timeout <! >! go take!]])
  #?(:cljs (:require-macros [superv.async :refer [go-try <?]]
                            [kabel.auth.websocket-client-test :refer [run-async]])))

(defn- throwable? [x]
  (instance? #?(:clj Throwable :cljs js/Error) x))

(defmacro ^:private run-async
  "Run the go block `body` returns as a test on both platforms."
  [& body]
  (let [ch (gensym "ch")]
    `(let [~ch (go-try S ~@body)]
       ~(if (:ns &env)
          `(cljs.test/async done# (take! ~ch (fn [v#] (is (not (throwable? v#)) (str v#)) (done#))))
          `(<?? S ~ch)))))

(defn- connect!
  "Apply the client auth middleware to a fresh connection. Returns the raw
   channels the scripted server reads and writes, and the application
   channels above the middleware."
  [peer opts]
  (let [raw-in (chan 10)
        raw-out (chan 10)
        [_ _ [app-in app-out]] ((auth/authenticate-middleware opts) [S peer [raw-in raw-out]])]
    {:raw-in raw-in :raw-out raw-out :app-in app-in :app-out app-out}))

(deftest token-source-is-read-per-connection-test
  (let [peer (peer/client-peer S (random-uuid) identity identity)
        tokens (atom ["t1" "t2"])
        token-fn (fn [] (let [t (first @tokens)] (swap! tokens rest) t))]
    (run-async
     (testing "the first connection sends the first token"
       (let [{:keys [raw-out raw-in]} (connect! peer {:token token-fn})
             sent (<! raw-out)]
         (is (= {:type :kabel/auth :token "t1"} sent))
         (>! raw-in {:type :kabel/auth-ok :principal {:sub "a"}})
         (close! raw-in)))
     (testing "the next connection reads the source again"
       (let [{:keys [raw-out]} (connect! peer {:token token-fn})]
         (is (= "t2" (:token (<! raw-out))))))
     (testing "an atom is read too, and a channel-returning function is awaited"
       (let [{:keys [raw-out]} (connect! peer {:token (atom "t3")})]
         (is (= "t3" (:token (<! raw-out)))))
       (let [{:keys [raw-out]} (connect! peer {:token (fn [] (go "t4"))})]
         (is (= "t4" (:token (<! raw-out)))))))))

(deftest refresh-test
  (let [peer (peer/client-peer S (random-uuid) identity identity)
        errors (atom [])
        principals (atom [])]
    (run-async
     (let [{:keys [raw-out raw-in app-in]} (connect! peer {:token "t1"
                                                           :on-auth #(swap! principals conj %)
                                                           :on-error #(swap! errors conj %)})]
       (<! raw-out)
       (>! raw-in {:type :kabel/auth-ok :principal {:sub "a"}})
       (testing "refresh-token! sends the new token and yields the accepted principal"
         (let [result (auth/refresh-token! peer "t2")]
           (is (= {:type :kabel/auth-refresh :token "t2"} (<! raw-out)))
           (>! raw-in {:type :kabel/auth-ok :principal {:sub "a" :fresh true}})
           (is (= {:sub "a" :fresh true} (<? S result)))
           (is (= [{:sub "a"} {:sub "a" :fresh true}] @principals))))
       (testing "a rejected refresh throws and reaches on-error"
         (let [result (auth/refresh-token! peer "bad")]
           (<! raw-out)
           (>! raw-in {:type :kabel/auth-error :error "invalid-token"})
           (let [e (<! result)]
             (is (throwable? e))
             (is (= :kabel.auth/refresh-rejected (:type (ex-data e)))))
           (is (= "invalid-token" (:error (last @errors))))))
       (testing "the server announcing expiry reaches on-error and not the application"
         (>! raw-in {:type :kabel/auth-error :error "token-expired"})
         (>! raw-in {:type :app/hello})
         (is (= {:type :app/hello} (<! app-in)))
         (is (= "token-expired" (:error (last @errors)))))
       (close! raw-in)))))

(deftest no-token-test
  (let [peer (peer/client-peer S (random-uuid) identity identity)
        errors (atom [])]
    (run-async
     (let [{:keys [raw-out]} (connect! peer {:token (fn [] nil) :on-error #(swap! errors conj %)})]
       (let [[v _] (async/alts! [raw-out (timeout 200)])]
         (is (nil? v) "nothing is sent without a token"))
       (is (= "no-token" (:error (first @errors))))))))
