(ns kabel.auth.websocket-test
  (:require [clojure.test :refer [deftest testing is]]
            [kabel.auth.websocket :as ws]
            [kabel.auth.jwt :as jwt]
            [clojure.core.async :refer [chan close! go <! >! <!! >!! timeout alt!!]]
            [superv.async :refer [S]]))

(def test-secret "test-secret-for-websocket-testing")

(defn make-test-token
  "Generate a valid test token."
  [claims]
  (let [now (quot (System/currentTimeMillis) 1000)]
    (jwt/sign-hs256 test-secret
                    (merge {:sub "test-user"
                            :email "test@example.com"
                            :name "Test User"
                            :iat now
                            :exp (+ now 3600)}
                           claims))))

(defn make-expired-token
  "Generate an expired test token."
  [claims]
  (let [now (quot (System/currentTimeMillis) 1000)]
    (jwt/sign-hs256 test-secret
                    (merge {:sub "test-user"
                            :exp (- now 100)} ;; expired
                           claims))))

(deftest auth-flow-test
  (testing "Successful authentication"
    (let [in (chan)
          out (chan)
          middleware (ws/validate-middleware
                      {:jwt {:secret test-secret :alg :HS256}})
          [_ _ [new-in new-out]] (middleware [S nil [in out]])
          token (make-test-token {:email "alice@example.com"})]

      ;; Send auth message
      (>!! in {:type :kabel/auth :token token})

      ;; Should receive auth-ok
      (let [response (alt!! (timeout 1000) :timeout
                            out ([v] v))]
        (is (= :kabel/auth-ok (:type response)))
        (is (= "alice@example.com" (get-in response [:principal :email]))))

      ;; Send a regular message
      (>!! in {:type :my-message :data "hello"})

      ;; Should have principal attached
      (let [msg (alt!! (timeout 1000) :timeout
                       new-in ([v] v))]
        (is (= :my-message (:type msg)))
        (is (= "alice@example.com" (get-in msg [:kabel/principal :email])))))))

(deftest validate-middleware-closure-test
  (testing "raw input closure reaches the application input"
    (let [in (chan)
          out (chan)
          [_ _ [new-in _new-out]] ((ws/validate-middleware {:dev-mode true})
                                   [S nil [in out]])]
      (close! in)
      (is (nil? (alt!! (timeout 1000) :timeout new-in ([v] v))))))

  (testing "application output closure reaches the raw output"
    (let [in (chan)
          out (chan)
          [_ _ [_new-in new-out]] ((ws/validate-middleware {:dev-mode true})
                                   [S nil [in out]])]
      (close! new-out)
      (is (nil? (alt!! (timeout 1000) :timeout out ([v] v)))))))

(deftest auth-failure-test
  (testing "Invalid token"
    (let [in (chan)
          out (chan)
          middleware (ws/validate-middleware
                      {:jwt {:secret test-secret :alg :HS256}})
          [_ _ [new-in new-out]] (middleware [S nil [in out]])]

      ;; Send auth with invalid token
      (>!! in {:type :kabel/auth :token "invalid-token"})

      ;; Should receive auth-error
      (let [response (alt!! (timeout 1000) :timeout
                            out ([v] v))]
        (is (= :kabel/auth-error (:type response)))
        (is (= "invalid-token" (:error response))))))

  (testing "Expired token"
    (let [in (chan)
          out (chan)
          middleware (ws/validate-middleware
                      {:jwt {:secret test-secret :alg :HS256}})
          [_ _ [new-in new-out]] (middleware [S nil [in out]])
          token (make-expired-token {:email "expired@example.com"})]

      ;; Send auth with expired token
      (>!! in {:type :kabel/auth :token token})

      ;; Should receive auth-error
      (let [response (alt!! (timeout 1000) :timeout
                            out ([v] v))]
        (is (= :kabel/auth-error (:type response)))))))

(deftest auth-refresh-test
  (testing "Token refresh"
    (let [in (chan)
          out (chan)
          middleware (ws/validate-middleware
                      {:jwt {:secret test-secret :alg :HS256}})
          [_ _ [new-in new-out]] (middleware [S nil [in out]])
          token1 (make-test-token {:email "refresh@example.com" :name "User 1"})
          token2 (make-test-token {:email "refresh@example.com" :name "User 2"})]

      ;; Initial auth
      (>!! in {:type :kabel/auth :token token1})
      (let [response (alt!! (timeout 1000) :timeout
                            out ([v] v))]
        (is (= :kabel/auth-ok (:type response)))
        (is (= "User 1" (get-in response [:principal :name]))))

      ;; Refresh with new token
      (>!! in {:type :kabel/auth-refresh :token token2})
      (let [response (alt!! (timeout 1000) :timeout
                            out ([v] v))]
        (is (= :kabel/auth-ok (:type response))))

      ;; Next message should have updated principal
      (>!! in {:type :check-principal})
      (let [msg (alt!! (timeout 1000) :timeout
                       new-in ([v] v))]
        (is (= "User 2" (get-in msg [:kabel/principal :name])))))))

(deftest dev-mode-test
  (testing "Dev mode skips token validation"
    (let [in (chan)
          out (chan)
          middleware (ws/validate-middleware
                      {:dev-mode true
                       :dev-principal {:sub "dev-user"
                                       :email "dev@test.com"
                                       :name "Dev User"}})
          [_ _ [new-in new-out]] (middleware [S nil [in out]])]

      ;; Send auth with any token (even invalid)
      (>!! in {:type :kabel/auth :token "any-token"})

      ;; Should succeed with dev principal
      (let [response (alt!! (timeout 1000) :timeout
                            out ([v] v))]
        (is (= :kabel/auth-ok (:type response)))
        (is (= "dev@test.com" (get-in response [:principal :email])))))))

(deftest unauthenticated-message-test
  (testing "Message before auth has no principal"
    (let [in (chan)
          out (chan)
          middleware (ws/validate-middleware
                      {:jwt {:secret test-secret :alg :HS256}})
          [_ _ [new-in new-out]] (middleware [S nil [in out]])]

      ;; Send message without authenticating first
      (>!! in {:type :my-message :data "test"})

      ;; Should pass through without principal
      (let [msg (alt!! (timeout 1000) :timeout
                       new-in ([v] v))]
        (is (= :my-message (:type msg)))
        (is (nil? (:kabel/principal msg)))))))

(deftest outbound-stripping-test
  (testing "Outbound messages have :kabel/* keys stripped"
    (let [in (chan)
          out (chan)
          middleware (ws/validate-middleware
                      {:jwt {:secret test-secret :alg :HS256}})
          [_ _ [new-in new-out]] (middleware [S nil [in out]])]

      ;; Send outbound message with :kabel/* keys
      (>!! new-out {:type :response
                    :data "test"
                    :kabel/principal {:sub "attacker"}
                    :kabel/internal "should-be-stripped"})

      ;; Should be stripped
      (let [msg (alt!! (timeout 1000) :timeout
                       out ([v] v))]
        (is (= :response (:type msg)))
        (is (= "test" (:data msg)))
        (is (nil? (:kabel/principal msg)))
        (is (nil? (:kabel/internal msg)))))))

(deftest principal-helpers-test
  (testing "with-principal binds *principal*"
    (is (nil? (ws/current-principal)))
    (ws/with-principal {:sub "bound-user" :email "bound@example.com"}
      (fn []
        (is (= "bound@example.com" (:email (ws/current-principal))))
        (is (= "bound-user" (:sub (ws/require-principal)))))))

  (testing "require-principal throws when not authenticated"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Authentication required"
                          (ws/require-principal)))))

;; =============================================================================
;; Authenticate Middleware Tests (outbound auth - prove MY identity)
;; =============================================================================

(deftest authenticate-middleware-test
  (testing "Sends auth immediately on connect"
    (let [in (chan)
          out (chan)
          auth-result (promise)
          middleware (ws/authenticate-middleware
                      {:token "my-token"
                       :on-auth (fn [principal]
                                  (deliver auth-result principal))})
          [_ _ [new-in _new-out]] (middleware [S nil [in out]])]

      ;; Should have sent auth message
      (let [auth-msg (alt!! (timeout 1000) :timeout
                            out ([v] v))]
        (is (= :kabel/auth (:type auth-msg)))
        (is (= "my-token" (:token auth-msg))))

      ;; Simulate remote accepting auth
      (>!! in {:type :kabel/auth-ok :principal {:sub "user1" :email "user1@test.com"}})

      ;; Wait for callback
      (is (= "user1@test.com" (:email (deref auth-result 1000 :timeout))))

      ;; Regular messages should pass through
      (>!! in {:type :my-message :data "hello"})
      (let [msg (alt!! (timeout 1000) :timeout
                       new-in ([v] v))]
        (is (= :my-message (:type msg)))
        (is (= "hello" (:data msg))))))

  (testing "Handles auth-error by closing channel"
    (let [in (chan)
          out (chan)
          error-result (promise)
          middleware (ws/authenticate-middleware
                      {:token "bad-token"
                       :on-error (fn [error]
                                   (deliver error-result error))})
          [_ _ [new-in _new-out]] (middleware [S nil [in out]])]

      ;; Consume the auth message
      (<!! out)

      ;; Simulate remote rejecting auth
      (>!! in {:type :kabel/auth-error :error "invalid-token"})

      ;; Wait for error callback
      (is (= "invalid-token" (:error (deref error-result 1000 :timeout))))

      ;; new-in should be closed
      (is (nil? (alt!! (timeout 100) :timeout
                       new-in ([v] v))))
      ;; Rejection tears down the raw transport output as well.
      (is (nil? (alt!! (timeout 100) :timeout
                       out ([v] v))))))

  (testing "Rejects auth-ok without a principal"
    (let [in (chan)
          out (chan)
          error-result (promise)
          middleware (ws/authenticate-middleware
                      {:token "test-token"
                       :on-error #(deliver error-result %)})
          [_ _ [new-in _new-out]] (middleware [S nil [in out]])]
      (<!! out)
      (>!! in {:type :kabel/auth-ok})
      (is (= "auth-invalid-response"
             (:error (deref error-result 1000 :timeout))))
      (is (nil? (alt!! (timeout 100) :timeout new-in ([v] v))))))

  (testing "Propagates channel closure after successful auth"
    (let [in (chan)
          out (chan)
          middleware (ws/authenticate-middleware {:token "test-token"})
          [_ _ [new-in new-out]] (middleware [S nil [in out]])]
      (<!! out)
      (>!! in {:type :kabel/auth-ok :principal {:sub "user1"}})

      (close! in)
      (is (nil? (alt!! (timeout 1000) :timeout
                       new-in ([v] v))))

      (close! new-out)
      (is (nil? (alt!! (timeout 1000) :timeout
                       out ([v] v))))))

  (testing "Fails closed on handshake timeout"
    (let [in (chan)
          out (chan)
          error-result (promise)
          middleware (ws/authenticate-middleware
                      {:token "test-token"
                       :timeout-ms 25
                       :pending-limit 100000
                       :on-error #(deliver error-result %)})
          [_ _ [new-in _new-out]] (middleware [S nil [in out]])]
      (<!! out)
      ;; Keep input continuously ready across the deadline. Timeout is the
      ;; priority arm once ready and cannot be starved by this stream.
      (go (dotimes [i 10000]
            (>! in {:type :early :i i})))
      (is (= "auth-timeout" (:error (deref error-result 1000 :timeout))))
      (is (nil? (alt!! (timeout 100) :timeout new-in ([v] v))))
      (is (nil? (alt!! (timeout 100) :timeout out ([v] v))))))

  (testing "The initial auth write is covered by the handshake deadline"
    (let [in (chan)
          ;; Deliberately leave the unbuffered transport output unconsumed.
          out (chan)
          error-result (promise)
          middleware (ws/authenticate-middleware
                      {:token "test-token"
                       :timeout-ms 25
                       :on-error #(deliver error-result %)})
          [_ _ [new-in new-out]] (middleware [S nil [in out]])]
      (is (= "auth-timeout" (:error (deref error-result 1000 :timeout))))
      (is (nil? (alt!! (timeout 100) :timeout new-in ([v] v))))
      (is (false? (>!! new-out {:type :must-not-be-accepted})))))

  (testing "Bounds frames buffered before the auth result"
    (let [in (chan)
          out (chan)
          error-result (promise)
          middleware (ws/authenticate-middleware
                      {:token "test-token"
                       :pending-limit 1
                       :on-error #(deliver error-result %)})
          [_ _ [new-in _new-out]] (middleware [S nil [in out]])]
      (<!! out)
      (>!! in {:type :early-1})
      (>!! in {:type :early-2})
      (let [error (deref error-result 1000 :timeout)]
        (is (= "auth-pending-overflow" (:error error)))
        (is (= :inbound (:direction error))))
      (is (nil? (alt!! (timeout 100) :timeout new-in ([v] v))))))

  (testing "Lifecycle callbacks cannot strand or reverse settlement"
    (let [in (chan)
          out (chan)
          middleware (ws/authenticate-middleware
                      {:token "test-token"
                       :on-auth (fn [_] (throw (ex-info "consumer callback failed" {})))})
          [_ _ [new-in new-out]] (middleware [S nil [in out]])]
      (<!! out)
      (>!! in {:type :kabel/auth-ok :principal {:sub "user1"}})
      (>!! in {:type :after-auth})
      (>!! new-out {:type :after-auth-out})
      (is (= :after-auth (:type (alt!! (timeout 1000) :timeout new-in ([v] v)))))
      (is (= :after-auth-out (:type (alt!! (timeout 1000) :timeout out ([v] v))))))

    (let [in (chan)
          out (chan)
          success-result (promise)
          error-result (promise)
          middleware (ws/authenticate-middleware
                      {:token "test-token"
                       :pending-limit 0
                       :on-auth #(deliver success-result %)
                       :on-error (fn [error]
                                   (deliver error-result error)
                                   (throw (ex-info "consumer error callback failed" {})))})
          [_ _ [_new-in new-out]] (middleware [S nil [in out]])]
      (<!! out)
      (>!! new-out {:type :too-early})
      (is (= "auth-pending-overflow" (:error (deref error-result 1000 :timeout))))
      ;; A late acceptance cannot reverse the terminal overflow settlement.
      (>!! in {:type :kabel/auth-ok :principal {:sub "late"}})
      (is (= :timeout (deref success-result 100 :timeout)))
      (is (nil? (alt!! (timeout 100) :timeout out ([v] v))))))

  (testing "Buffers interleaved inbound and outbound traffic until auth succeeds"
    (let [in (chan)
          out (chan)
          auth-result (promise)
          middleware (ws/authenticate-middleware
                      {:token "test-token"
                       :on-auth #(deliver auth-result %)})
          [_ _ [new-in new-out]] (middleware [S nil [in out]])]

      ;; Consume the auth message
      (<!! out)

      ;; Another server middleware may speak before the auth response. Neither
      ;; direction may escape to the application/transport yet.
      (>!! in {:type :server-init :data "hello"})
      (>!! new-out {:type :subscribe :topic :private})
      (is (= :timeout (alt!! (timeout 50) :timeout new-in ([v] v))))
      (is (= :timeout (alt!! (timeout 50) :timeout out ([v] v))))

      (>!! in {:type :kabel/auth-ok :principal {:sub "user1"}})
      (is (= "user1" (:sub (deref auth-result 1000 :timeout))))

      ;; Buffered messages retain their direction and order after acceptance.
      (is (= {:type :server-init :data "hello"}
             (alt!! (timeout 1000) :timeout new-in ([v] v))))
      (is (= {:type :subscribe :topic :private}
             (alt!! (timeout 1000) :timeout out ([v] v)))))))

;; =============================================================================
;; Unified Auth Middleware Tests
;; =============================================================================

(deftest auth-middleware-unified-test
  (testing "Authenticate-only mode (prove my identity to remote)"
    (let [in (chan)
          out (chan)
          middleware (ws/auth-middleware {:authenticate {:token "my-token"} :permissive true})
          [_ _ [new-in _new-out]] (middleware [S nil [in out]])]

      ;; Should send auth
      (let [auth-msg (alt!! (timeout 1000) :timeout
                            out ([v] v))]
        (is (= :kabel/auth (:type auth-msg)))
        (is (= "my-token" (:token auth-msg))))

      ;; Simulate remote accepting auth and verify messages pass through
      (>!! in {:type :kabel/auth-ok :principal {:sub "user"}})
      (>!! in {:type :data-msg})
      (let [msg (alt!! (timeout 1000) :timeout
                       new-in ([v] v))]
        (is (= :data-msg (:type msg))))))

  (testing "Validate-only mode (verify remote's identity)"
    (let [in (chan)
          out (chan)
          middleware (ws/auth-middleware {:validate {:dev-mode true
                                                     :dev-principal {:sub "dev" :email "dev@test.com"}}})
          [_ _ [new-in _new-out]] (middleware [S nil [in out]])]

      ;; Remote sends auth message
      (>!! in {:type :kabel/auth :token "any-token"})

      ;; Should receive auth-ok
      (let [response (alt!! (timeout 1000) :timeout
                            out ([v] v))]
        (is (= :kabel/auth-ok (:type response)))
        (is (= "dev@test.com" (get-in response [:principal :email]))))

      ;; Next message should have principal attached
      (>!! in {:type :data-msg})
      (let [msg (alt!! (timeout 1000) :timeout
                       new-in ([v] v))]
        (is (= :data-msg (:type msg)))
        (is (= "dev@test.com" (get-in msg [:kabel/principal :email]))))))

  (testing "No config returns identity middleware"
    (let [in (chan)
          out (chan)
          middleware (ws/auth-middleware {})
          [_ _ [result-in result-out]] (middleware [S nil [in out]])]

      ;; Should be the same channels (identity)
      (is (= in result-in))
      (is (= out result-out))))

  (testing "Authenticate without validate requires :permissive true"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Security.*:permissive true"
                          (ws/auth-middleware {:authenticate {:token "my-token"}}))))

  (testing "Bidirectional mode (both peers authenticate to each other)"
    ;; Wire two real middleware stacks directly together. Both initial auth
    ;; frames cross before either auth-ok; a sequential mock misses the cycle
    ;; this mode has to support.
    (let [a->b (chan 1000)
          b->a (chan 1000)
          a-authenticated (promise)
          a-validated-b (promise)
          b-authenticated (promise)
          b-validated-a (promise)
          token-a (make-test-token {:sub "peer-a" :email "a@test.com"})
          token-b (make-test-token {:sub "peer-b" :email "b@test.com"})
          middleware-a (ws/auth-middleware
                        {:authenticate {:token token-a :on-auth #(deliver a-authenticated %)}
                         :validate {:jwt {:secret test-secret :alg :HS256}
                                    :on-auth #(deliver a-validated-b %)}})
          middleware-b (ws/auth-middleware
                        {:authenticate {:token token-b :on-auth #(deliver b-authenticated %)}
                         :validate {:jwt {:secret test-secret :alg :HS256}
                                    :on-auth #(deliver b-validated-a %)}})
          [_ _ [a-in a-out]] (middleware-a [S nil [b->a a->b]])
          [_ _ [b-in _b-out]] (middleware-b [S nil [a->b b->a]])]

      (is (= "peer-a" (:sub (deref a-authenticated 1000 :timeout))))
      (is (= "peer-b" (:sub (deref a-validated-b 1000 :timeout))))
      (is (= "peer-b" (:sub (deref b-authenticated 1000 :timeout))))
      (is (= "peer-a" (:sub (deref b-validated-a 1000 :timeout))))

      ;; Normal traffic starts only after both handshakes and is attributed to
      ;; the authenticated sending peer by the receiving validator.
      (>!! a-out {:type :data-msg :value 42})
      (let [msg (alt!! (timeout 1000) :timeout b-in ([v] v))]
        (is (= 42 (:value msg)))
        (is (= "peer-a" (get-in msg [:kabel/principal :sub])))))))
