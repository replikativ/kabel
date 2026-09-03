(ns kabel.remote-test
  "`kabel.remote` over an in-memory connection: two peers whose middleware
   stacks are applied to a crossed pair of channels, exactly as a transport
   would apply them."
  (:require [clojure.test :refer [deftest testing is #?(:cljs async)]]
            [kabel.peer :as peer]
            [kabel.remote :as remote]
            #?(:clj [superv.async :refer [S go-try <? <??]]
               :cljs [superv.async :refer [S go-try <?]])
            #?(:clj [clojure.core.async :as async :refer [chan close! put! timeout <! go go-loop >! take!]]
               :cljs [clojure.core.async :as async :refer [chan close! put! timeout <! go go-loop >! take!]]))
  #?(:cljs (:require-macros [superv.async :refer [go-try <?]]
                            [kabel.remote-test :refer [run-async]])))

;; =============================================================================
;; Helpers
;; =============================================================================

(defn- throwable? [x]
  (instance? #?(:clj Throwable :cljs js/Error) x))

(defn- stamp
  "Middleware that stamps `principal` on every inbound message, as the auth
   middleware would."
  [principal]
  (fn [[S peer [in out]]]
    (let [new-in (chan)]
      (go-loop [m (<! in)]
        (if m
          (do (>! new-in (cond-> m principal (assoc :kabel/principal principal)))
              (recur (<! in)))
          (close! new-in)))
      [S peer [new-in out]])))

(defn- link!
  "Connect peers `a` and `b` in memory. Returns a function that closes the
   link, as a dropped socket would."
  [a b]
  (let [a->b (chan 100)
        b->a (chan 100)
        run! (fn [peer in out]
               (peer/drain ((get-in @peer [:volatile :middleware]) [S peer [in out]])))]
    (run! a b->a a->b)
    (run! b a->b b->a)
    (fn [] (close! a->b) (close! b->a))))

(defn- wait-for-ready
  "Yields the remote's id once `peer` can reach it, nil after two seconds."
  [peer remote]
  (go-loop [n 0]
    (cond
      (remote/connected? peer (:id @remote)) (:id @remote)
      (< n 100) (do (<! (timeout 20)) (recur (inc n)))
      :else nil)))

(defn- caught
  "The exception `ch` yields, or the value."
  [ch]
  (go (let [v (<! ch)] v)))

(defmacro ^:private run-async
  "Run the go block `body` returns as a test on both platforms."
  [& body]
  (let [ch (gensym "ch")]
    `(let [~ch (go-try S ~@body)]
       ~(if (:ns &env)
          `(cljs.test/async done# (take! ~ch (fn [v#] (is (not (throwable? v#)) (str v#)) (done#))))
          `(<?? S ~ch)))))

;; =============================================================================
;; Tests
;; =============================================================================

(defn- pair
  "A serving peer `b` and a calling peer `a`, linked."
  [& [server-opts]]
  (let [a (peer/client-peer S (random-uuid) remote/middleware identity)
        b (peer/client-peer S (random-uuid) (comp remote/middleware (stamp (:principal server-opts))) identity)
        served (remote/serve b (dissoc server-opts :principal))
        unlink! (link! a b)]
    {:a a :b b :served served :unlink! unlink!}))

(deftest roundtrip-test
  (remote/register! 'kabel.remote-test/add (fn [{:keys [x y]}] (+ x y)))
  (remote/register! 'kabel.remote-test/add-async (fn [{:keys [x y]}] (go (+ x y))))
  (remote/register! 'kabel.remote-test/nothing (fn [_] nil))
  (let [{:keys [a b]} (pair)]
    (run-async
     (is (= (:id @b) (<? S (wait-for-ready a b))))
     (testing "a plain value and a channel both come back"
       (is (= 3 (<? S (remote/invoke a (:id @b) 'kabel.remote-test/add {:x 1 :y 2}))))
       (is (= 7 (<? S (remote/invoke a (:id @b) 'kabel.remote-test/add-async {:x 3 :y 4})))))
     (testing "nil is a result too"
       (is (nil? (<? S (remote/invoke a (:id @b) 'kabel.remote-test/nothing {})))))
     (testing "the route registry finds the peer from the remote id alone"
       (is (= 3 (<? S (remote/invoke (:id @b) 'kabel.remote-test/add {:x 1 :y 2})))))
     (testing "invoking oneself runs the function locally"
       (is (= 3 (<? S (remote/invoke a (:id @a) 'kabel.remote-test/add {:x 1 :y 2}))))))))

(deftest errors-test
  (remote/register! 'kabel.remote-test/boom
                    (fn [_] (throw (ex-info "boom" {:type :kabel.remote-test/boom :detail 1}))))
  (let [{:keys [a b]} (pair)]
    (run-async
     (<? S (wait-for-ready a b))
     (testing "a thrown exception arrives typed"
       (let [e (<! (caught (remote/invoke a (:id @b) 'kabel.remote-test/boom {})))]
         (is (throwable? e))
         (is (= :kabel.remote-test/boom (:type (ex-data e))))
         (is (= "boom" (ex-message e)))))
     (testing "an unknown function is reported as such"
       (let [e (<! (caught (remote/invoke a (:id @b) 'kabel.remote-test/missing {})))]
         (is (= ::remote/unknown-function (:type (ex-data e))))))
     (testing "a timeout is reported as such"
       (remote/register! 'kabel.remote-test/slow (fn [_] (go (<! (timeout 500)) :late)))
       (let [e (<! (caught (remote/invoke a (:id @b) 'kabel.remote-test/slow {} {:timeout-ms 50})))]
         (is (= ::remote/timeout (:type (ex-data e)))))))))

(deftest authorization-test
  (remote/register! 'kabel.remote-test/whoami (fn [{:keys [:kabel/principal]}] (:sub principal)))
  (testing "without a principal a denial asks for authentication"
    (let [{:keys [a b]} (pair {:authorize (fn [{:keys [principal]}] (some? principal))})]
      (run-async
       (<? S (wait-for-ready a b))
       (let [e (<! (caught (remote/invoke a (:id @b) 'kabel.remote-test/whoami {})))]
         (is (= ::remote/authentication-required (:type (ex-data e))))))))
  (testing "with a principal a denial is a refusal, and a grant sees the principal"
    (let [{:keys [a b]} (pair {:principal {:sub "alice"}
                               :authorize (fn [{:keys [op principal fn-name]}]
                                            (and (= op :invoke)
                                                 (= "alice" (:sub principal))
                                                 (= 'kabel.remote-test/whoami fn-name)))})]
      (run-async
       (<? S (wait-for-ready a b))
       (is (= "alice" (<? S (remote/invoke a (:id @b) 'kabel.remote-test/whoami {}))))
       (let [e (<! (caught (remote/invoke a (:id @b) 'kabel.remote-test/add {:x 1 :y 2})))]
         (is (= ::remote/not-authorized (:type (ex-data e))))))))
  (testing "the positional distributed-scope gate still works"
    (let [{:keys [a b]} (pair {:principal {:sub "bob"}
                               :authorize-fn (fn [principal fn-name _arg-map]
                                               (and (= "bob" (:sub principal))
                                                    (= 'kabel.remote-test/whoami fn-name)))})]
      (run-async
       (<? S (wait-for-ready a b))
       (is (= "bob" (<? S (remote/invoke a (:id @b) 'kabel.remote-test/whoami {}))))))))

(deftest not-serving-test
  (let [a (peer/client-peer S (random-uuid) remote/middleware identity)
        b (peer/client-peer S (random-uuid) remote/middleware identity)]
    (link! a b)
    (run-async
     (<? S (wait-for-ready a b))
     (let [e (<! (caught (remote/invoke a (:id @b) 'kabel.remote-test/add {:x 1 :y 2})))]
       (is (= ::remote/not-serving (:type (ex-data e))))))))

(deftest disconnect-test
  (remote/register! 'kabel.remote-test/forever (fn [_] (chan)))
  (let [{:keys [a b unlink!]} (pair)]
    (run-async
     (<? S (wait-for-ready a b))
     (testing "a call in flight fails when the connection closes"
       (let [result (caught (remote/invoke a (:id @b) 'kabel.remote-test/forever {}))]
         (<! (timeout 50))
         (unlink!)
         (let [e (<! result)]
           (is (= ::remote/disconnected (:type (ex-data e)))))))
     (testing "afterwards the peer is not connected"
       (let [e (<! (caught (remote/invoke a (:id @b) 'kabel.remote-test/add {:x 1 :y 2} {:timeout-ms 50})))]
         (is (= ::remote/not-connected (:type (ex-data e)))))))))

(deftest waits-for-connection-test
  (remote/register! 'kabel.remote-test/add (fn [{:keys [x y]}] (+ x y)))
  (let [a (peer/client-peer S (random-uuid) remote/middleware identity)
        b (peer/client-peer S (random-uuid) remote/middleware identity)]
    (remote/serve b)
    (run-async
     (let [early (remote/invoke a (:id @b) 'kabel.remote-test/add {:x 1 :y 2})]
       (<! (timeout 50))
       (link! a b)
       (is (= 3 (<? S early)))))))

(deftest legacy-dialect-test
  (remote/register! 'kabel.remote-test/add (fn [{:keys [x y]}] (+ x y)))
  (testing "a peer announcing itself in the distributed-scope dialect is answered in it"
    (let [b (peer/client-peer S (random-uuid) remote/middleware identity)
          to-b (chan 100)
          from-b (chan 100)
          old-id (random-uuid)]
      (remote/serve b)
      (peer/drain ((get-in @b [:volatile :middleware]) [S b [to-b from-b]]))
      (run-async
       ;; the new peer announces itself in its own dialect first
       (is (= :kabel.remote/register (:type (<! from-b))))
       (put! to-b {:type :is.simm.distributed-scope/register-scope :scope old-id})
       ;; ...and repeats it in the old one once it hears the old dialect
       (is (= :is.simm.distributed-scope/register-scope (:type (<! from-b))))
       (put! to-b {:type :is.simm.distributed-scope/invoke
                   :scope (:id @b) :request-scope old-id :request-id 1
                   :fn-name 'kabel.remote-test/add :arg-map {:x 2 :y 3}})
       (let [reply (<! from-b)]
         (is (= :is.simm.distributed-scope/invoke-result (:type reply)))
         (is (= 5 (:result reply)))
         (is (= 1 (:request-id reply))))
       (testing "errors travel as strings in the old dialect"
         (put! to-b {:type :is.simm.distributed-scope/invoke
                     :scope (:id @b) :request-scope old-id :request-id 2
                     :fn-name 'kabel.remote-test/missing :arg-map {}})
         (is (string? (:error (<! from-b)))))))))

#?(:clj
   (deftest blocking-work-goes-on-a-thread-test
     (testing "a function that has to block offloads to a thread and returns its channel"
       (remote/register! 'kabel.remote-test/offloaded
                         (fn [_] (async/thread (Thread/sleep 50) (.getName (Thread/currentThread)))))
       (let [{:keys [a b]} (pair)]
         (run-async
          (<? S (wait-for-ready a b))
          (let [thread-name (<? S (remote/invoke a (:id @b) 'kabel.remote-test/offloaded {}))]
            (is (string? thread-name))
            (is (not (re-find #"async-dispatch" thread-name))
                "the blocking part never ran on the go dispatch pool")))))))

(deftest gate-may-decide-on-a-channel-test
  (remote/register! 'kabel.remote-test/whoami (fn [{:keys [:kabel/principal]}] (:sub principal)))
  (testing "a gate that answers with a channel is awaited, allow and deny alike"
    (let [{:keys [a b]} (pair {:principal {:sub "alice"}
                               :authorize (fn [{:keys [fn-name]}]
                                            (go (<! (timeout 10))
                                                (= 'kabel.remote-test/whoami fn-name)))})]
      (run-async
       (<? S (wait-for-ready a b))
       (is (= "alice" (<? S (remote/invoke a (:id @b) 'kabel.remote-test/whoami {}))))
       (let [e (<! (caught (remote/invoke a (:id @b) 'kabel.remote-test/add {:x 1 :y 2})))]
         (is (= ::remote/not-authorized (:type (ex-data e)))))))))
