(ns kabel.transport-test
  (:require #?(:clj [clojure.core.async :as async :refer [chan close! >!! <!! timeout]]
               :cljs [clojure.core.async :refer [chan close!]])
            [clojure.test :refer [deftest is testing]]
            #?(:clj [kabel.client :as client])
            #?(:clj [kabel.peer :as peer])
            #?(:clj [clojure.core.async.impl.protocols :as async-protocols])
            [kabel.transport :as transport]
            [kabel.pubsub :as pubsub]
            [kabel.pubsub.protocol :as proto]
            #?(:clj [superv.async :refer [S simple-supervisor]])))

#?(:clj
   (deftest disconnect-completion-follows-physical-cleanup
     (let [raw-in (chan) raw-out (chan 8) entered (promise) release (promise)
           p (peer/client-peer
              S (random-uuid)
              (fn [connection]
                (transport/on-close! (transport/connection-context connection) :held-cleanup
                                     (fn [_] (deliver entered true) (deref release 5000 ::timeout)))
                connection)
              identity)]
       (try
         (let [done (with-redefs [client/client-connect! (fn [& _] (let [result (chan 1)] (>!! result [raw-in raw-out]) result))]
                      (<!! (peer/connect S p "ws://test.invalid")))]
           (close! raw-in)
           (is (= true (deref entered 3000 ::timeout)))
           (is (not (async-protocols/closed? done)) "Reconnect cannot observe completion before cleanup")
           (deliver release true)
           (is (identical? done (second (async/alts!! [done (timeout 3000)])))))
         (finally
           (deliver release true)
           (close! raw-in) (close! raw-out)
           (peer/unregister-peer! (:id @p)))))))

#?(:clj
   (deftest supervisor-abort-retires-actual-peer-connection
     (let [s (simple-supervisor :error-fn (fn [_]))
           raw-in (chan) raw-out (chan 8) disposed (promise)
           p (peer/client-peer s (random-uuid) (pubsub/make-pubsub-peer-middleware {}) identity)]
       (try
         (with-redefs [client/client-connect! (fn [& _] (let [result (chan 1)] (>!! result [raw-in raw-out]) result))]
           (<!! (peer/connect s p "ws://test.invalid")))
         (is (:ok (<!! (pubsub/subscribe! p #{:private}
                                          {:strategies {:private (proto/pub-sub-only-strategy nil)}}))))
         (add-watch p ::disposed
                    (fn [_ _ before after]
                      (when (and (seq (get-in before [:volatile ::transport/connections]))
                                 (empty? (get-in after [:volatile ::transport/connections])))
                        (deliver disposed true))))
         (doseq [abort (:aborts s)] (close! abort))
         (is (= true (deref disposed 3000 ::timeout)))
         (is (nil? (pubsub/subscription p :private)))
         (finally
           (remove-watch p ::disposed)
           (close! raw-in) (close! raw-out)
           (peer/unregister-peer! (:id @p)))))))

(deftest composed-legacy-middleware-can-own-connection-cleanup
  (let [context (transport/new-context :initiator) calls (atom [])
        legacy (fn [[s p _]] [s p [(chan) (chan)]])
        owner (fn [connection]
                (transport/on-close! (transport/connection-context connection) :owner
                                     #(swap! calls conj %))
                connection)]
    (transport/apply-middleware (comp owner legacy)
                                (transport/with-context [::supervisor (atom {}) [(chan) (chan)]] context))
    (transport/dispose! context :stopped)
    (transport/dispose! context :again)
    (transport/on-close! context :late #(swap! calls conj [:late %]))
    (is (= [:stopped [:late :stopped]] @calls))))

(deftest cleanup-failure-does-not-leak-other-owners-or-replacement-context
  (let [context (transport/new-context :initiator) replacement (transport/new-context :initiator {::transport/id (::transport/id @context)})
        p (atom {}) calls (atom 0)]
    (transport/register! p context)
    (transport/register! p replacement)
    (transport/on-close! context :bad (fn [_] (throw (ex-info "failed cleanup" {}))))
    (transport/on-close! context :good (fn [_] (swap! calls inc)))
    (is (thrown? #?(:clj Exception :cljs js/Error) (transport/unregister! p context)))
    (is (= 1 @calls))
    (is (identical? replacement (get (transport/connections p) (::transport/id @context))))
    (transport/unregister! p replacement)))

#?(:clj
   (deftest disposed-pubsub-allows-same-peer-subscription-after-reconnect
     (let [p (atom {:volatile {:supervisor S}})
           first-context (transport/new-context :initiator)
           second-context (transport/new-context :initiator)
           first-out (chan 8) second-out (chan 8)]
       ;; Construct the same composed middleware shape a real peer uses.
       ;; The inner legacy layer deliberately drops pair metadata.
       (let [install! (fn [context out]
                        (transport/register! p context)
                        (transport/apply-middleware
                         (comp (pubsub/make-pubsub-peer-middleware {})
                               (fn [[s peer [in out]]] [s peer [in out]]))
                         (transport/with-context [S p [(chan) out]] context)))
             subscribe! #(pubsub/subscribe! p #{:private}
                                            {:strategies {:private (proto/pub-sub-only-strategy nil)}})]
         (try
           (install! first-context first-out)
           (is (:ok (<!! (subscribe!))))
           (is (some? (pubsub/subscription p :private)))
           ;; Simulate the connection owner's finally, WITHOUT delivering nil
           ;; to the router. Supervisor abort follows precisely this boundary.
           (transport/unregister! p first-context)
           (is (nil? (pubsub/subscription p :private)))
           (install! second-context second-out)
           (is (:ok (<!! (subscribe!))))
           (let [new-sub (pubsub/subscription p :private)]
             (transport/unregister! p first-context)
             (is (= new-sub (pubsub/subscription p :private))))
           (finally
             (transport/unregister! p second-context)))))))

(deftest connection-context-is-local-and-stable
  (let [a (transport/new-context :initiator
                                 {::transport/expected-target :authority/alice})
        b (transport/new-context :initiator
                                 {::transport/expected-target :authority/alice})]
    (is (not= (::transport/id @a) (::transport/id @b)))
    (is (= :initiator (::transport/role @a)))
    (is (true? (::transport/initiator? @a)))
    (is (= :authority/alice (::transport/expected-target @a)))
    (is (= #{} (::transport/negotiated-capabilities @a)))
    (is (nil? (::transport/authenticated-authority @a)))))

(deftest legacy-middleware-does-not-have-to-preserve-context-metadata
  (let [context (transport/new-context :responder)
        peer (atom {:id :peer})
        first-in (chan)
        first-out (chan)
        next-in (chan)
        next-out (chan)
        connection (transport/with-context [::supervisor peer
                                            [first-in first-out]]
                     context)
        ;; This is the original Kabel middleware contract: destructure the
        ;; triple and return a brand-new, unannotated channel pair.
        legacy (fn [[S p [_in _out]]]
                 [S p [next-in next-out]])
        result (transport/apply-middleware legacy connection)]
    (is (= [::supervisor peer [next-in next-out]] result))
    (is (identical? context (transport/connection-context result)))
    (transport/update! result
                       {::transport/authenticated-authority :authority/alice
                        ::transport/negotiated-capabilities #{:netz/v1}})
    (is (= :authority/alice
           (::transport/authenticated-authority @context)))
    (is (= #{:netz/v1} (::transport/negotiated-capabilities @context)))
    (close! first-in)
    (close! first-out)
    (close! next-in)
    (close! next-out)))

(deftest peer-connection-registry-is-keyed-by-connection-not-peer
  (let [peer (atom {:volatile {}})
        a (transport/new-context :initiator)
        b (transport/new-context :responder)]
    (transport/register! peer a)
    (transport/register! peer b)
    (is (= #{(::transport/id @a) (::transport/id @b)}
           (set (keys (transport/connections peer)))))
    (transport/unregister! peer a)
    (is (= {(::transport/id @b) b} (transport/connections peer)))))

#?(:clj
   (deftest peer-orders-transport-before-serialization-and-application
     (let [new-conns (chan 1)
           raw-in (chan)
           raw-out (chan)
           stages (atom [])
           observed (promise)
           step (fn [stage authenticated?]
                  (fn [connection]
                    (swap! stages conj stage)
                    (when authenticated?
                      (transport/update!
                       connection
                       {::transport/authenticated-authority :authority/alice}))
                    (when (= stage :application)
                      (deliver observed
                               @(transport/connection-context connection)))
                    connection))
           p (peer/server-peer
              S {:new-conns new-conns :url "ws://localhost:1"}
              (random-uuid)
              (step :application false)
              (step :serialization false)
              (atom {}) (atom {})
              {:transport-middleware (step :transport true)
               :connection-context {::transport/transport :test}})]
       (>!! new-conns [raw-in raw-out
                       {::transport/remote-address "198.51.100.7"}])
       (let [context (deref observed 1000 ::timeout)]
         (is (not= ::timeout context))
         (is (= [:transport :serialization :application] @stages))
         (is (= :responder (::transport/role context)))
         (is (= :test (::transport/transport context)))
         (is (= "198.51.100.7" (::transport/remote-address context)))
         (is (= :authority/alice
                (::transport/authenticated-authority context))))
       (close! raw-in)
       (close! raw-out)
       (close! new-conns)
       (<!! (timeout 50))
       (is (empty? (transport/connections p)))
       (peer/unregister-peer! (:id @p)))))

#?(:clj
   (deftest client-connect-context-is-per-dial
     (let [raw-in (chan)
           raw-out (chan)
           observed (promise)
           p (peer/client-peer
              S (random-uuid) identity identity (atom {}) (atom {})
              {:transport-middleware
               (fn [connection]
                 (deliver observed @(transport/connection-context connection))
                 connection)})]
       (with-redefs [client/client-connect!
                     (fn [_ _ _ _ _]
                       (let [result (chan 1)]
                         (clojure.core.async/put! result [raw-in raw-out])
                         result))]
         (peer/connect S p "wss://peer.example/netz"
                       {::transport/expected-target :authority/alice})
         (let [context (deref observed 1000 ::timeout)]
           (is (not= ::timeout context))
           (is (= :authority/alice (::transport/expected-target context)))
           (is (= "wss://peer.example/netz"
                  (::transport/dial-address context)))))
       (close! raw-in)
       (close! raw-out)
       (<!! (timeout 50))
       (peer/unregister-peer! (:id @p)))))
