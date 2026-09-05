(ns kabel.remote-macro-test
  "Remote macros over the same crossed-channel connection used by the runtime tests."
  (:require [clojure.core.async :refer [chan close! timeout <! go-loop take!]]
            [clojure.test :refer [deftest is testing #?(:cljs async)]]
            [kabel.peer :as peer]
            [kabel.remote :as remote]
            [kabel.remote.macro :refer [go-remote #?@(:clj [defn-go-remote])]]
            [kabel.remote.missionary :as missionary
             :refer [sp-remote #?@(:clj [defn-sp-remote])]]
            #?(:clj [superv.async :refer [S go-try <? <??]]
               :cljs [superv.async :refer [S go-try <?]]))
  #?(:cljs (:require-macros [kabel.remote-macro-test :refer [run-async]]
                            [kabel.remote.macro :refer [defn-go-remote]]
                            [kabel.remote.missionary :refer [defn-sp-remote]]
                            [superv.async :refer [go-try <?]])))

(defn- throwable? [x]
  (instance? #?(:clj Throwable :cljs js/Error) x))

(defn- link!
  "Connect two peers through crossed in-memory channels."
  [a b]
  (let [a->b (chan 100)
        b->a (chan 100)
        run! (fn [peer in out]
               (peer/drain ((get-in @peer [:volatile :middleware]) [S peer [in out]])))]
    (run! a b->a a->b)
    (run! b a->b b->a)
    (fn [] (close! a->b) (close! b->a))))

(defn- wait-for-ready
  "Yield the remote id when the in-memory route is ready, or nil on timeout."
  [peer remote]
  (go-loop [attempt 0]
    (cond
      (remote/connected? peer (:id @remote)) (:id @remote)
      (< attempt 100) (do (<! (timeout 20)) (recur (inc attempt)))
      :else nil)))

(defmacro ^:private run-async
  "Run an asynchronous test body on Clojure and ClojureScript."
  [& body]
  (let [ch (gensym "ch")]
    `(let [~ch (go-try S ~@body)]
       ~(if (:ns &env)
          `(cljs.test/async done#
                            (take! ~ch (fn [value#]
                                         (is (not (throwable? value#)) (str value#))
                                         (done#))))
          `(<?? S ~ch)))))

(defn-go-remote go-roundtrip [server-id value]
  (go-remote server-id [value]
             {:received value :returned (inc value)}))

(defn-go-remote go-multi-hop [client-id server-id value]
  (go-remote server-id [client-id value]
             (let [answer (inc value)]
               (<? S (go-remote client-id [answer]
                                (* answer 2))))))

(defn-go-remote go-nil [server-id]
  (go-remote server-id [] nil))

(defn-sp-remote sp-roundtrip [server-id value]
  (sp-remote server-id [value]
             (inc value)))

(defn- pair
  "Create two linked peers which both serve generated remote functions."
  []
  (let [a (peer/client-peer S (random-uuid) remote/middleware identity)
        b (peer/client-peer S (random-uuid) remote/middleware identity)]
    (remote/serve a)
    (remote/serve b)
    (link! a b)
    {:a a :b b}))

(deftest core-async-macros-test
  (let [{:keys [a b]} (pair)
        client-id (:id @a)
        server-id (:id @b)]
    (run-async
     (is (= server-id (<? S (wait-for-ready a b))))
     (is (= client-id (<? S (wait-for-ready b a))))
     (testing "a captured value travels to the server and its result returns"
       (is (= {:received 4 :returned 5}
              (<? S (go-roundtrip server-id 4)))))
     (testing "a server body can invoke a generated continuation on the client"
       (is (= 12 (<? S (go-multi-hop client-id server-id 5)))))
     (testing "nil remains a successful result"
       (is (nil? (<? S (go-nil server-id))))))))

#?(:clj
   (deftest undeclared-free-variable-test
     (testing "macro expansion names a variable omitted from the capture vector"
       (let [error (try
                     (macroexpand
                      '(kabel.remote.macro/defn-go-remote invalid-remote [server-id]
                         (go-remote server-id [] missing-value)))
                     nil
                     (catch Throwable e e))]
         (is (throwable? error))
         (is (re-find #"missing-value" (str error)))))))

(deftest missionary-macros-test
  (let [{:keys [a b]} (pair)
        server-id (:id @b)]
    (run-async
     (is (= server-id (<? S (wait-for-ready a b))))
     (is (= 10
            (<? S (missionary/task->chan (sp-roundtrip server-id 9))))))))
