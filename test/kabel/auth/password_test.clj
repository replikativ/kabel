(ns kabel.auth.password-test
  (:require [clojure.test :refer [deftest testing is]]
            [kabel.auth.password :as pwd]))

(deftest hash-and-verify-test
  (testing "Hash and verify correct password"
    (let [password "my-secret-password"
          hashed (pwd/hash-password password)]
      (is (string? hashed))
      (is (not= password hashed))
      (is (pwd/verify-password password hashed))))

  (testing "Wrong password returns false"
    (let [hashed (pwd/hash-password "correct-password")]
      (is (not (pwd/verify-password "wrong-password" hashed)))))

  (testing "Invalid hash returns false"
    (is (not (pwd/verify-password "any-password" "invalid-hash"))))

  (testing "Same password produces different hashes (salted)"
    (let [password "same-password"
          hash1 (pwd/hash-password password)
          hash2 (pwd/hash-password password)]
      (is (not= hash1 hash2))
      (is (pwd/verify-password password hash1))
      (is (pwd/verify-password password hash2)))))

(deftest validate-password-test
  (testing "Valid password"
    (is (= {:valid true} (pwd/validate-password "password123"))))

  (testing "Password too short"
    (let [result (pwd/validate-password "short")]
      (is (not (:valid result)))
      (is (some #(re-find #"at least 8" %) (:errors result)))))

  (testing "Custom minimum length"
    (is (= {:valid true} (pwd/validate-password "abc" {:min-length 3})))
    (is (not (:valid (pwd/validate-password "ab" {:min-length 3})))))

  (testing "Nil password"
    (let [result (pwd/validate-password nil)]
      (is (not (:valid result)))
      (is (some #(= "Password is required" %) (:errors result))))))

(deftest generate-refresh-token-test
  (testing "Generates unique tokens"
    (let [token1 (pwd/generate-refresh-token)
          token2 (pwd/generate-refresh-token)]
      (is (string? token1))
      (is (string? token2))
      (is (not= token1 token2))))

  (testing "Token is base64 encoded"
    (let [token (pwd/generate-refresh-token)]
      ;; URL-safe base64 contains alphanumeric, -, _, and optional = padding
      (is (re-matches #"[A-Za-z0-9_=-]+" token))
      (is (>= (count token) 40))))) ;; 32 bytes -> ~43 chars in base64
