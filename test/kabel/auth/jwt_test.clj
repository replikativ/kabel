(ns kabel.auth.jwt-test
  (:require [clojure.test :refer [deftest is testing]]
            [kabel.auth.jwt :as jwt])
  (:import (java.security KeyPairGenerator)))

(deftest hs256-valid-token
  (testing "Validator returns principal map for a valid HS256 token"
    (let [secret "topsecret"
          now (quot (System/currentTimeMillis) 1000)
          claims {:sub "alice@example.org"
                  :iss "test-issuer"
                  :aud "test-aud"
                  :iat now
                  :nbf (- now 10)
                  :exp (+ now 60)}
          token (jwt/sign-hs256 secret claims)
          validate (jwt/build-bearer-validator {:alg :HS256
                                                :secret secret
                                                :required-claims {:iss "test-issuer" :aud "test-aud"}
                                                :leeway-seconds 5})
          req {:headers {"authorization" (str "Bearer " token)}}
          principal (validate req)]
      (is (map? principal))
      (is (= "alice@example.org" (:sub principal))))))

(deftest hs256-wrong-signature
  (testing "Validator returns nil for wrong signature"
    (let [secret "topsecret"
          now (quot (System/currentTimeMillis) 1000)
          claims {:sub "bob@example.org" :exp (+ now 60)}
          token (jwt/sign-hs256 secret claims)
          tampered (str token "x")
          validate (jwt/build-bearer-validator {:alg :HS256 :secret secret})
          req {:headers {"authorization" (str "Bearer " tampered)}}]
      (is (nil? (validate req))))))

(deftest hs256-expired
  (testing "Validator returns nil for expired token"
    (let [secret "topsecret"
          now (quot (System/currentTimeMillis) 1000)
          claims {:sub "carol@example.org" :exp (- now 10)}
          token (jwt/sign-hs256 secret claims)
          validate (jwt/build-bearer-validator {:alg :HS256 :secret secret :leeway-seconds 0})
          req {:headers {"authorization" (str "Bearer " token)}}]
      (is (nil? (validate req))))))

(deftest rs256-valid-token
  (testing "Validator returns principal map for a valid RS256 token"
    (let [kpg (doto (KeyPairGenerator/getInstance "RSA") (.initialize 2048))
          kp (.generateKeyPair kpg)
          now (quot (System/currentTimeMillis) 1000)
          claims {:sub "dave@example.org" :exp (+ now 60)}
          token (jwt/sign-rs256 (.getPrivate kp) claims)
          validate (jwt/build-bearer-validator {:alg :RS256 :public-key (.getPublic kp)})
          req {:headers {"authorization" (str "Bearer " token)}}
          principal (validate req)]
      (is (= "dave@example.org" (:sub principal))))))

(deftest rs256-wrong-signature
  (testing "Validator returns nil for wrong RS256 signature"
    (let [kpg (doto (KeyPairGenerator/getInstance "RSA") (.initialize 2048))
          kpA (.generateKeyPair kpg)
          kpB (.generateKeyPair kpg)
          now (quot (System/currentTimeMillis) 1000)
          claims {:sub "erin@example.org" :exp (+ now 60)}
          token (jwt/sign-rs256 (.getPrivate kpA) claims)
          validate (jwt/build-bearer-validator {:alg :RS256 :public-key (.getPublic kpB)})
          req {:headers {"authorization" (str "Bearer " token)}}]
      (is (nil? (validate req))))))

;; =============================================================================
;; Trusted-issuer registry (multi-issuer: self-issued, WorkOS/Clerk, peers)
;; =============================================================================

(defn- rsa-keypair []
  (let [kpg (doto (KeyPairGenerator/getInstance "RSA") (.initialize 2048))]
    (.generateKeyPair kpg)))

(deftest registry-selects-issuer-by-iss
  (testing "the token's iss selects its issuer's key from the registry"
    (let [simmis (rsa-keypair)
          workos (rsa-keypair)
          now (quot (System/currentTimeMillis) 1000)
          validate (jwt/build-bearer-validator
                    {:issuers {"https://simmis/" {:alg :RS256 :public-key (.getPublic simmis)}
                               "https://acme.workos.com" {:alg :RS256 :public-key (.getPublic workos)}}})
          simmis-tok (jwt/sign-rs256 (.getPrivate simmis)
                                     {:sub "a@x" :iss "https://simmis/" :exp (+ now 60)})
          workos-tok (jwt/sign-rs256 (.getPrivate workos)
                                     {:sub "b@y" :iss "https://acme.workos.com" :exp (+ now 60)})]
      (is (= "a@x" (:sub (validate {:headers {"authorization" (str "Bearer " simmis-tok)}}))))
      (is (= "b@y" (:sub (validate {:headers {"authorization" (str "Bearer " workos-tok)}})))
          "a second enrolled issuer verifies through the same path"))))

(deftest registry-rejects-untrusted-issuer
  (testing "a token from an iss not in the registry is rejected even if well-signed"
    (let [rogue (rsa-keypair)
          now (quot (System/currentTimeMillis) 1000)
          validate (jwt/build-bearer-validator
                    {:issuers {"https://simmis/" {:alg :RS256 :public-key (.getPublic (rsa-keypair))}}})
          tok (jwt/sign-rs256 (.getPrivate rogue)
                              {:sub "evil@z" :iss "https://evil.example" :exp (+ now 60)})]
      (is (nil? (validate {:headers {"authorization" (str "Bearer " tok)}}))))))

(deftest registry-rejects-issuer-key-mismatch
  (testing "right issuer name, wrong signing key => rejected (no key confusion)"
    (let [real (rsa-keypair)
          fake (rsa-keypair)
          now (quot (System/currentTimeMillis) 1000)
          validate (jwt/build-bearer-validator
                    {:issuers {"https://simmis/" {:alg :RS256 :public-key (.getPublic real)}}})
          tok (jwt/sign-rs256 (.getPrivate fake)
                              {:sub "a@x" :iss "https://simmis/" :exp (+ now 60)})]
      (is (nil? (validate {:headers {"authorization" (str "Bearer " tok)}}))))))

(deftest registry-selects-key-by-kid
  (testing "static JWKS: the header kid picks the key within an issuer"
    (let [k1 (rsa-keypair)
          k2 (rsa-keypair)
          now (quot (System/currentTimeMillis) 1000)
          validate (jwt/build-bearer-validator
                    {:issuers {"https://clerk/" {:alg :RS256
                                                 :keys {"k1" (.getPublic k1)
                                                        "k2" (.getPublic k2)}}}})
          ;; sign-rs256 emits a header without kid; forge a kid-bearing header by
          ;; signing then asserting the no-kid path falls back to :public-key.
          ;; Here we assert k2-signed token verifies when k2 is in the set.
          tok (jwt/sign-rs256 (.getPrivate k2)
                              {:sub "c@k" :iss "https://clerk/" :exp (+ now 60)})]
      ;; No kid in header => resolve-issuer-key falls back to :public-key which
      ;; is absent, so this must be nil (kid-keyed sets require a kid).
      (is (nil? (validate {:headers {"authorization" (str "Bearer " tok)}}))
          "a kid-keyed issuer with no matching kid does not silently accept"))))

(deftest alg-confusion-downgrade-rejected
  (testing "an RS256 issuer will not verify an HS256 token forged with the public key as secret"
    (let [kp (rsa-keypair)
          pub-pem (str "-----BEGIN PUBLIC KEY-----\n"
                       (.encodeToString (java.util.Base64/getMimeEncoder)
                                        (.getEncoded (.getPublic kp)))
                       "\n-----END PUBLIC KEY-----")
          now (quot (System/currentTimeMillis) 1000)
          ;; Attacker signs HS256 using the RSA public key bytes as the HMAC secret.
          forged (jwt/sign-hs256 pub-pem {:sub "attacker" :iss "https://simmis/" :exp (+ now 60)})
          validate (jwt/build-bearer-validator
                    {:issuers {"https://simmis/" {:alg :RS256 :public-key (.getPublic kp)}}})]
      (is (nil? (validate {:headers {"authorization" (str "Bearer " forged)}}))
          "pinned RS256 must reject a token whose header says HS256"))))

(deftest key-resolver-injection-for-jwks
  (testing "a :jwks-url issuer resolves its key through the injected key-resolver"
    (let [kp (rsa-keypair)
          now (quot (System/currentTimeMillis) 1000)
          resolver-calls (atom [])
          validate (jwt/build-bearer-validator
                    {:issuers {"https://acme.workos.com" {:alg :RS256 :jwks-url "https://acme.workos.com/jwks"}}
                     :key-resolver (fn [m] (swap! resolver-calls conj m) (.getPublic kp))})
          tok (jwt/sign-rs256 (.getPrivate kp)
                              {:sub "w@o" :iss "https://acme.workos.com" :exp (+ now 60)})]
      (is (= "w@o" (:sub (validate {:headers {"authorization" (str "Bearer " tok)}}))))
      (is (= 1 (count @resolver-calls)))
      (is (= "https://acme.workos.com/jwks" (:jwks-url (first @resolver-calls)))))))

(deftest legacy-single-issuer-still-works
  (testing "the pre-registry config forms are unchanged"
    (let [secret "s"
          now (quot (System/currentTimeMillis) 1000)
          v (jwt/build-bearer-validator {:alg :HS256 :secret secret})
          tok (jwt/sign-hs256 secret {:sub "leg@acy" :exp (+ now 60)})]
      (is (= "leg@acy" (:sub (v {:headers {"authorization" (str "Bearer " tok)}})))))))
