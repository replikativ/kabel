(ns kabel.auth.websocket-test
  (:require [clojure.test :refer [deftest testing is]]
            [kabel.auth.websocket :as ws]
            [kabel.auth.jwt :as jwt]
            [clojure.core.async :refer [chan go <! >! <!! >!! timeout alt!!]]
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
                       new-in ([v] v))))))

  (testing "Passes through non-auth first message (graceful degradation)"
    (let [in (chan)
          out (chan)
          middleware (ws/authenticate-middleware {:token "test-token"})
          [_ _ [new-in _new-out]] (middleware [S nil [in out]])]

      ;; Consume the auth message
      (<!! out)

      ;; Simulate peer that doesn't support auth (sends regular message)
      (>!! in {:type :regular-message :data "hello"})

      ;; Should pass through
      (let [msg (alt!! (timeout 1000) :timeout
                       new-in ([v] v))]
        (is (= :regular-message (:type msg)))
        (is (= "hello" (:data msg)))))))

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
    ;; This tests that both middlewares are composed correctly
    (let [in (chan)
          out (chan)
          my-auth-accepted (promise)
          remote-auth-validated (promise)
          ;; Bidirectional: I authenticate to remote, and validate auth from remote
          middleware (ws/auth-middleware
                      {:authenticate {:token "my-token"
                                      :on-auth (fn [principal]
                                                 (deliver my-auth-accepted principal))}
                       :validate {:dev-mode true
                                  :dev-principal {:sub "dev" :email "dev@test.com"}
                                  :on-auth (fn [principal]
                                             (deliver remote-auth-validated principal))}})
          [_ _ [new-in _new-out]] (middleware [S nil [in out]])]

      ;; I should have sent auth first (innermost middleware)
      (let [auth-msg (alt!! (timeout 1000) :timeout
                            out ([v] v))]
        (is (= :kabel/auth (:type auth-msg)))
        (is (= "my-token" (:token auth-msg))))

      ;; Simulate remote accepting my auth
      (>!! in {:type :kabel/auth-ok :principal {:sub "remote-user" :email "remote@test.com"}})

      ;; My on-auth callback should have been called
      (is (= "remote@test.com" (:email (deref my-auth-accepted 1000 :timeout))))

      ;; Now simulate remote authenticating to me
      (>!! in {:type :kabel/auth :token "remote-peer-token"})

      ;; I should respond with auth-ok
      (let [response (alt!! (timeout 1000) :timeout
                            out ([v] v))]
        (is (= :kabel/auth-ok (:type response)))
        (is (= "dev@test.com" (get-in response [:principal :email]))))

      ;; My validate on-auth callback should have been called
      (is (= "dev@test.com" (:email (deref remote-auth-validated 1000 :timeout)))))))
