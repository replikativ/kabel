(ns kabel.auth.routes-test
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [kabel.auth.routes :as routes]
            [kabel.auth.store.memory :refer [memory-auth-store]]
            [kabel.auth.jwt :as jwt]))

(def test-secret "test-secret-for-testing-only")

(defn make-test-config []
  {:store (memory-auth-store)
   :dev-mode false
   :jwt {:secret test-secret
         :issuer "test-app"
         :audience "test-api"
         :access-token-expiry 3600
         :refresh-token-expiry 86400}
   :password {:enabled true
              :min-length 8}})

(defn make-request [method path body]
  {:request-method method
   :uri path
   :body body
   :headers {}
   :remote-addr "127.0.0.1"})

;; Registration tests

(deftest register-test
  (testing "Successful registration"
    (let [config (make-test-config)
          handler (routes/auth-handler config)
          response (handler (make-request :post "/register"
                                          {:email "test@example.com"
                                           :password "password123"
                                           :name "Test User"}))]
      (is (= 201 (:status response)))
      (is (= "test@example.com" (get-in response [:body :user :email])))
      (is (= "Test User" (get-in response [:body :user :name])))))

  (testing "Missing email"
    (let [config (make-test-config)
          handler (routes/auth-handler config)
          response (handler (make-request :post "/register"
                                          {:password "password123"}))]
      (is (= 400 (:status response)))
      (is (= "email-required" (get-in response [:body :error])))))

  (testing "Duplicate email"
    (let [config (make-test-config)
          handler (routes/auth-handler config)
          _ (handler (make-request :post "/register"
                                   {:email "dupe@example.com"
                                    :password "password123"}))
          response (handler (make-request :post "/register"
                                          {:email "dupe@example.com"
                                           :password "password456"}))]
      (is (= 400 (:status response)))
      (is (= "email-exists" (get-in response [:body :error])))))

  (testing "Weak password"
    (let [config (make-test-config)
          handler (routes/auth-handler config)
          response (handler (make-request :post "/register"
                                          {:email "weak@example.com"
                                           :password "short"}))]
      (is (= 400 (:status response)))
      (is (= "password-weak" (get-in response [:body :error]))))))

;; Login tests

(deftest login-test
  (testing "Successful login"
    (let [config (make-test-config)
          handler (routes/auth-handler config)
          _ (handler (make-request :post "/register"
                                   {:email "login@example.com"
                                    :password "password123"
                                    :name "Login User"}))
          response (handler (make-request :post "/login"
                                          {:email "login@example.com"
                                           :password "password123"}))]
      (is (= 200 (:status response)))
      (is (string? (get-in response [:body :access_token])))
      (is (string? (get-in response [:body :refresh_token])))
      (is (= "login@example.com" (get-in response [:body :user :email])))))

  (testing "Wrong password"
    (let [config (make-test-config)
          handler (routes/auth-handler config)
          _ (handler (make-request :post "/register"
                                   {:email "wrong@example.com"
                                    :password "password123"}))
          response (handler (make-request :post "/login"
                                          {:email "wrong@example.com"
                                           :password "wrongpassword"}))]
      (is (= 401 (:status response)))
      (is (= "invalid-credentials" (get-in response [:body :error])))))

  (testing "Non-existent user"
    (let [config (make-test-config)
          handler (routes/auth-handler config)
          response (handler (make-request :post "/login"
                                          {:email "nonexistent@example.com"
                                           :password "password123"}))]
      (is (= 401 (:status response)))
      (is (= "invalid-credentials" (get-in response [:body :error]))))))

;; Refresh tests

(deftest refresh-test
  (testing "Successful refresh"
    (let [config (make-test-config)
          handler (routes/auth-handler config)
          _ (handler (make-request :post "/register"
                                   {:email "refresh@example.com"
                                    :password "password123"}))
          login-response (handler (make-request :post "/login"
                                                {:email "refresh@example.com"
                                                 :password "password123"}))
          refresh-token (get-in login-response [:body :refresh_token])
          refresh-response (handler (make-request :post "/refresh"
                                                  {:refresh_token refresh-token}))]
      (is (= 200 (:status refresh-response)))
      (is (string? (get-in refresh-response [:body :access_token])))))

  (testing "Invalid refresh token"
    (let [config (make-test-config)
          handler (routes/auth-handler config)
          response (handler (make-request :post "/refresh"
                                          {:refresh_token "invalid-token"}))]
      (is (= 401 (:status response)))
      (is (= "invalid-refresh-token" (get-in response [:body :error]))))))

;; Logout tests

(deftest logout-test
  (testing "Logout invalidates refresh token"
    (let [config (make-test-config)
          handler (routes/auth-handler config)
          _ (handler (make-request :post "/register"
                                   {:email "logout@example.com"
                                    :password "password123"}))
          login-response (handler (make-request :post "/login"
                                                {:email "logout@example.com"
                                                 :password "password123"}))
          refresh-token (get-in login-response [:body :refresh_token])

          ;; Logout
          logout-response (handler (make-request :post "/logout"
                                                 {:refresh_token refresh-token}))
          _ (is (= 200 (:status logout-response)))

          ;; Try to use refresh token - should fail
          refresh-response (handler (make-request :post "/refresh"
                                                  {:refresh_token refresh-token}))]
      (is (= 401 (:status refresh-response))))))

;; Dev mode tests

(deftest dev-mode-test
  (testing "Dev endpoint not available in production mode"
    (let [config (make-test-config)
          handler (routes/auth-handler config)
          response (handler (make-request :post "/dev"
                                          {:email "dev@example.com"}))]
      (is (= 404 (:status response)))))

  (testing "Dev endpoint works in dev mode"
    (let [config (assoc (make-test-config) :dev-mode true)
          handler (routes/auth-handler config)
          response (handler (make-request :post "/dev"
                                          {:email "dev@example.com"
                                           :name "Dev User"}))]
      (is (= 200 (:status response)))
      (is (string? (get-in response [:body :access_token])))
      (is (= "dev@example.com" (get-in response [:body :user :email])))))

  (testing "Dev endpoint creates user if not exists"
    (let [config (assoc (make-test-config) :dev-mode true)
          handler (routes/auth-handler config)
          response1 (handler (make-request :post "/dev"
                                           {:email "newdev@example.com"}))
          response2 (handler (make-request :post "/dev"
                                           {:email "newdev@example.com"}))]
      ;; Both should succeed and return same user ID
      (is (= 200 (:status response1)))
      (is (= 200 (:status response2)))
      (is (= (get-in response1 [:body :user :id])
             (get-in response2 [:body :user :id]))))))

;; JWT validation test

(deftest access-token-validation-test
  (testing "Access token can be validated"
    (let [config (make-test-config)
          handler (routes/auth-handler config)
          _ (handler (make-request :post "/register"
                                   {:email "jwt@example.com"
                                    :password "password123"
                                    :name "JWT User"}))
          login-response (handler (make-request :post "/login"
                                                {:email "jwt@example.com"
                                                 :password "password123"}))
          access-token (get-in login-response [:body :access_token])
          validator (jwt/build-bearer-validator {:alg :HS256
                                                 :secret test-secret
                                                 :required-claims {:iss "test-app"
                                                                   :aud "test-api"}})
          principal (validator {:headers {"authorization" (str "Bearer " access-token)}})]
      (is (some? principal))
      (is (= "jwt@example.com" (:email principal)))
      (is (= "JWT User" (:name principal))))))
