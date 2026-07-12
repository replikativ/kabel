(ns kabel.auth.jwks-test
  "Network-free JWKS tests: generate an RSA keypair, publish its public half as a
   JWK, and prove a token signed by the private half verifies through the issuer
   registry's :key-resolver — the WorkOS/Clerk path, minus the HTTP hop (which is
   injected via :fetch-fn)."
  (:require [clojure.test :refer [deftest is testing]]
            [jsonista.core :as j]
            [kabel.auth.jwks :as jwks]
            [kabel.auth.jwt :as jwt])
  (:import (java.security KeyPairGenerator)
           (java.util Base64)))

(defn- gen-keypair []
  (let [g (doto (KeyPairGenerator/getInstance "RSA") (.initialize 2048))]
    (.generateKeyPair g)))

(defn- b64url [^bytes bs]
  (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) bs))

(defn- bigint->b64url
  "Minimal-length unsigned big-endian base64url, as JWK n/e require."
  [^java.math.BigInteger bi]
  (let [bs (.toByteArray bi)                       ; two's-complement; may carry a leading 0x00
        bs (if (and (> (alength bs) 1) (zero? (aget bs 0)))
             (java.util.Arrays/copyOfRange bs 1 (alength bs))
             bs)]
    (b64url bs)))

(defn- pubkey->jwk [^java.security.interfaces.RSAPublicKey pk kid]
  {:kty "RSA" :alg "RS256" :use "sig" :kid kid
   :n (bigint->b64url (.getModulus pk))
   :e (bigint->b64url (.getPublicExponent pk))})

(deftest jwk-roundtrip-verifies-a-real-token
  (let [kp (gen-keypair)
        pub (.getPublic kp)
        priv (.getPrivate kp)
        jwk (pubkey->jwk pub "key-1")
        rebuilt (jwks/jwk->public-key jwk)
        token (jwt/sign-rs256 priv {:sub "u1" :iss "https://idp.example/" :aud "simmis"})
        validate (jwt/build-bearer-validator
                  {:issuers {"https://idp.example/" {:alg :RS256 :jwks-url "https://idp.example/jwks"}}
                   :key-resolver (jwks/make-key-resolver {:fetch-fn (fn [_] {"key-1" rebuilt})})
                   :required-claims {:aud "simmis"}})
        principal (validate {:headers {"authorization" (str "Bearer " token)}})]
    (testing "jwk->public-key rebuilds a usable key"
      (is (= "RSA" (.getAlgorithm rebuilt))))
    (testing "a token signed by the private half verifies via the resolver"
      (is (= "u1" (:sub principal)))
      (is (= "https://idp.example/" (:iss principal))))
    (testing "a token from the wrong key is rejected"
      (let [other (jwt/sign-rs256 (.getPrivate (gen-keypair))
                                  {:sub "evil" :iss "https://idp.example/" :aud "simmis"})]
        (is (nil? (validate {:headers {"authorization" (str "Bearer " other)}})))))))

(deftest resolver-caches-and-refetches-on-kid-miss
  (let [calls (atom 0)
        kp1 (gen-keypair) kp2 (gen-keypair)
        pk1 (jwks/jwk->public-key (pubkey->jwk (.getPublic kp1) "k1"))
        pk2 (jwks/jwk->public-key (pubkey->jwk (.getPublic kp2) "k2"))
        ;; first fetch publishes only k1; after "rotation" both are published
        fetch (fn [_] (swap! calls inc) (if (< @calls 2) {"k1" pk1} {"k1" pk1 "k2" pk2}))
        resolve (jwks/make-key-resolver {:fetch-fn fetch})]
    (testing "first lookup fetches"
      (is (= pk1 (resolve {:jwks-url "u" :kid "k1"})))
      (is (= 1 @calls)))
    (testing "cached kid does not refetch"
      (is (= pk1 (resolve {:jwks-url "u" :kid "k1"})))
      (is (= 1 @calls)))
    (testing "an unknown kid triggers exactly one refetch, then resolves"
      (is (= pk2 (resolve {:jwks-url "u" :kid "k2"})))
      (is (= 2 @calls)))
    (testing "nil jwks-url resolves to nil without fetching"
      (is (nil? (resolve {:jwks-url nil :kid "k1"})))
      (is (= 2 @calls)))))

(deftest parse-jwks-skips-non-rsa
  (let [pk (pubkey->jwk (.getPublic (gen-keypair)) "rsa-1")
        body (str "{\"keys\":[" (j/write-value-as-string pk)
                  ",{\"kty\":\"EC\",\"kid\":\"ec-1\",\"crv\":\"P-256\"}]}")
        parsed (jwks/parse-jwks body)]
    (is (contains? parsed "rsa-1"))
    (is (not (contains? parsed "ec-1")))))
