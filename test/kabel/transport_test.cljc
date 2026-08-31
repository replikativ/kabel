(ns kabel.transport-test
  (:require #?(:clj [clojure.core.async :refer [chan close! >!! <!! timeout]]
               :cljs [clojure.core.async :refer [chan close!]])
            [clojure.test :refer [deftest is testing]]
            #?(:clj [kabel.peer :as peer])
            [kabel.transport :as transport]
            #?(:clj [superv.async :refer [S]])))

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
