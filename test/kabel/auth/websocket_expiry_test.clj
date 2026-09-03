(ns kabel.auth.websocket-expiry-test
  "Token expiry on a live connection, with real HS256 tokens: the client
   refreshes before `exp`, and the server acts when it does not."
  (:require [clojure.test :refer [deftest is testing]]
            [kabel.auth.jwt :as jwt]
            [kabel.auth.websocket :as auth]
            [kabel.peer :as peer]
            [superv.async :refer [S <??]]
            [clojure.core.async :as async :refer [chan close! put! timeout <!! >!! alts!!]]))

(def ^:private secret "expiry-test-secret")

(defn- token-expiring-in
  "A token whose `exp` is at least `seconds` ahead: JWT time is whole seconds."
  [seconds]
  (jwt/sign-hs256 secret {:sub "alice" :exp (+ (quot (+ (System/currentTimeMillis) 999) 1000) seconds)}))

(defn- link!
  "Client auth middleware talking to server validate middleware over channels.
   Returns the application channels on both ends and the raw client `out`."
  [client-opts server-opts]
  (let [client (peer/client-peer S (random-uuid) identity identity)
        server (peer/client-peer S (random-uuid) identity identity)
        c->s (chan 10)
        s->c (chan 10)
        [_ _ [client-in _]] ((auth/authenticate-middleware client-opts) [S client [s->c c->s]])
        [_ _ [server-in server-out]] ((auth/validate-middleware server-opts) [S server [c->s s->c]])]
    {:client client :client-in client-in :server-in server-in :server-out server-out
     :c->s c->s :s->c s->c
     :close! (fn [] (close! c->s) (close! s->c))}))

(defn- principal-of
  "Send a message through the server side and return the principal it carries."
  [{:keys [server-in c->s]}]
  (>!! c->s {:type :probe})
  (let [[m _] (alts!! [server-in (timeout 1000)])]
    (:kabel/principal m)))

(defn- closed? [ch]
  (let [[v _] (alts!! [[ch :probe] (timeout 50)])]
    (false? v)))

(deftest server-closes-expired-connection-test
  (let [link (link! {:token (token-expiring-in 1) :auto-refresh? false}
                    {:jwt {:alg :HS256 :secret secret :leeway-seconds 0}})]
    (Thread/sleep 200)
    (is (= "alice" (:sub (principal-of link))) "validated within the token's lifetime")
    (Thread/sleep 2300)
    (is (closed? (:s->c link)) "the server closed the connection at expiry")))

(deftest server-downgrades-to-anonymous-test
  (let [link (link! {:token (token-expiring-in 1) :auto-refresh? false}
                    {:jwt {:alg :HS256 :secret secret :leeway-seconds 0} :on-expiry :anonymous})]
    (Thread/sleep 200)
    (is (= "alice" (:sub (principal-of link))))
    (Thread/sleep 2300)
    (is (not (closed? (:s->c link))) "the connection stays open")
    (is (nil? (principal-of link)) "but carries no principal any more")))

(deftest client-refreshes-before-expiry-test
  (let [refreshes (atom 0)
        token (fn [] (swap! refreshes inc) (token-expiring-in 2))
        link (link! {:token token :refresh-before-ms 1000}
                    {:jwt {:alg :HS256 :secret secret :leeway-seconds 0}})]
    (Thread/sleep 200)
    (is (= "alice" (:sub (principal-of link))))
    (Thread/sleep 2500)
    (is (>= @refreshes 2) "the token source was read again for the refresh")
    (is (not (closed? (:s->c link))) "the connection outlived the first token")
    (is (= "alice" (:sub (principal-of link))) "and is still authenticated")
    ((:close! link))))

(deftest explicit-refresh-replaces-principal-test
  (let [link (link! {:token (token-expiring-in 60) :auto-refresh? false}
                    {:jwt {:alg :HS256 :secret secret}})]
    (Thread/sleep 200)
    (let [p (<?? S (auth/refresh-token! (:client link)
                                        (jwt/sign-hs256 secret
                                                        {:sub "alice" :role "admin"
                                                         :exp (+ (quot (System/currentTimeMillis) 1000) 60)})))]
      (is (= "admin" (:role p))))
    (is (= "admin" (:role (principal-of link))))))

(deftest remote-principal-is-never-trusted-test
  (let [server (peer/client-peer S (random-uuid) identity identity)
        c->s (chan 10)
        s->c (chan 10)
        [_ _ [server-in _]] ((auth/validate-middleware {:jwt {:alg :HS256 :secret secret}}) [S server [c->s s->c]])]
    (testing "an unauthenticated connection cannot stamp a principal itself"
      (>!! c->s {:type :probe :kabel/principal {:sub "mallory"}})
      (let [[m _] (alts!! [server-in (timeout 1000)])]
        (is (= {:type :probe} m))))
    (testing "an authenticated one gets the validated principal, whatever it sent"
      (>!! c->s {:type :kabel/auth :token (token-expiring-in 60)})
      (<!! s->c)
      (>!! c->s {:type :probe :kabel/principal {:sub "mallory"}})
      (let [[m _] (alts!! [server-in (timeout 1000)])]
        (is (= "alice" (get-in m [:kabel/principal :sub])))))))
