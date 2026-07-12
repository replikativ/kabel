(ns kabel.auth.jwt-xplatform-test
  "HS256 sign+verify works identically on JVM and CLJS (browser/Node) — the
   cross-platform path that lets a ClojureScript peer verify Bearer tokens."
  (:require #?(:clj [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer-macros [deftest is testing]])
            [kabel.auth.jwt :as jwt]))

(defn- now [] (long (/ #?(:clj (System/currentTimeMillis) :cljs (js/Date.now)) 1000)))
(defn- req [t] {:headers {"authorization" (str "Bearer " t)}})

(deftest hs256-verify-cross-platform
  (let [secret "test-secret-key-0123456789"
        token  (jwt/sign-hs256 secret {:sub "u1" :role "admin" :exp (+ (now) 3600)})
        validate (jwt/build-bearer-validator {:alg :HS256 :secret secret})]
    (testing "a valid token verifies and returns its claims"
      (let [p (validate (req token))]
        (is (= "u1" (:sub p)))
        (is (= "admin" (:role p)))))
    (testing "a token signed with a different secret is rejected"
      (is (nil? ((jwt/build-bearer-validator {:alg :HS256 :secret "wrong-secret"})
                 (req token)))))
    (testing "an expired token (beyond the 60s leeway) is rejected"
      (is (nil? (validate (req (jwt/sign-hs256 secret {:sub "u1" :exp (- (now) 120)}))))))
    (testing "a tampered token is rejected"
      (is (nil? (validate (req (str token "TAMPER")))))))

  (testing "trusted-issuer registry (HS256) selects by iss and rejects unlisted"
    (let [secret "issuer-secret"
          token (jwt/sign-hs256 secret {:sub "u2" :iss "peer-a" :exp (+ (now) 3600)})
          validate (jwt/build-bearer-validator
                    {:issuers {"peer-a" {:alg :HS256 :secret secret}}})]
      (is (= "u2" (:sub (validate (req token)))))
      (is (nil? (validate (req (jwt/sign-hs256 secret {:sub "x" :iss "peer-b"
                                                       :exp (+ (now) 3600)}))))))))
