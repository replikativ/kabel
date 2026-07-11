(ns kabel.auth.jwt
  "Minimal JWT utilities to validate Bearer tokens in Ring requests.
  Focuses on HS256 (HMAC-SHA256) to keep dependencies light. For RS256/ES256,
  supply your own verifier function with a public key.

  Provides:
  - sign-hs256: helper for tests to construct tokens.
  - build-bearer-validator: returns a (fn [req] principal-or-nil) for http-kit.
  "
  (:require [jsonista.core :as j]
            [clojure.string :as str])
  (:import (java.util Base64 Date)
           (java.security KeyFactory PublicKey PrivateKey Signature)
           (java.security.spec X509EncodedKeySpec PKCS8EncodedKeySpec)
           (javax.crypto Mac)
           (javax.crypto.spec SecretKeySpec)))

(def ^:private utf8 "UTF-8")
(def ^:private json-mapper (j/object-mapper {:decode-key-fn true}))

(defn- b64url-encode-bytes [^bytes bs]
  (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) bs))

(defn- b64url-decode-bytes [^String s]
  (.decode (Base64/getUrlDecoder) s))

(defn- json->b64 [m]
  (-> (j/write-value-as-bytes m)
      (b64url-encode-bytes)))

(defn- b64->json [^String s]
  (-> s b64url-decode-bytes (j/read-value json-mapper)))

;; --- RSA helpers (PEM <-> keys) ---
(defn- strip-pem ^String [^String pem]
  (-> pem
      (str/replace #"-----BEGIN [^-]+-----" "")
      (str/replace #"-----END [^-]+-----" "")
      (str/replace #"\s" "")))

(defn- public-key-from-pem ^PublicKey [^String pem]
  (let [kf (KeyFactory/getInstance "RSA")
        bytes (.decode (Base64/getDecoder) (strip-pem pem))
        spec (X509EncodedKeySpec. bytes)]
    (.generatePublic kf spec)))

(defn- private-key-from-pem ^PrivateKey [^String pem]
  (let [kf (KeyFactory/getInstance "RSA")
        bytes (.decode (Base64/getDecoder) (strip-pem pem))
        spec (PKCS8EncodedKeySpec. bytes)]
    (.generatePrivate kf spec)))

(defn- hmac-sha256 [^bytes key-bytes ^bytes data]
  (let [algo "HmacSHA256"
        mac (Mac/getInstance algo)]
    (.init mac (SecretKeySpec. key-bytes algo))
    (.doFinal mac data)))

(defn sign-hs256
  "Create an HS256 JWT for testing.
  claims map may include :exp and :nbf as epoch seconds."
  [^String secret claims]
  (let [header {:alg "HS256" :typ "JWT"}
        header-b64 (json->b64 header)
        payload-b64 (json->b64 claims)
        signing-input (.getBytes (str header-b64 "." payload-b64) utf8)
        sig-bytes (hmac-sha256 (.getBytes secret utf8) signing-input)
        sig-b64 (b64url-encode-bytes sig-bytes)]
    (str header-b64 "." payload-b64 "." sig-b64)))

(defn sign-rs256
  "Create an RS256 JWT for testing from a PrivateKey or PEM string."
  [priv-key-or-pem claims]
  (let [^PrivateKey priv-key (if (instance? PrivateKey priv-key-or-pem)
                               priv-key-or-pem
                               (private-key-from-pem priv-key-or-pem))
        header {:alg "RS256" :typ "JWT"}
        header-b64 (json->b64 header)
        payload-b64 (json->b64 claims)
        signing-input (.getBytes (str header-b64 "." payload-b64) utf8)
        ^Signature sig (Signature/getInstance "SHA256withRSA")]
    (.initSign sig priv-key)
    (.update sig signing-input)
    (let [sig-bytes (.sign sig)
          sig-b64 (b64url-encode-bytes sig-bytes)]
      (str header-b64 "." payload-b64 "." sig-b64))))

(defn- parse-token [^String token]
  (let [[h p s :as parts] (when token (str/split token #"\."))]
    (when (not= 3 (count parts))
      (throw (ex-info "Malformed JWT (expected 3 parts)" {:token token :parts parts})))
    (let [header (b64->json h)
          payload (b64->json p)
          sig-bytes (b64url-decode-bytes s)
          signing-input (.getBytes (str h "." p) utf8)]
      {:header header :payload payload :sig sig-bytes :signing-input signing-input})))

(defn- now-epoch [] (long (/ (.getTime (Date.)) 1000)))

(defn- check-time-claims! [claims leeway]
  (let [now (now-epoch)
        exp (:exp claims)
        nbf (:nbf claims)
        iat (:iat claims)]
    (when (and exp (> (- now leeway) exp))
      (throw (ex-info "JWT expired" {:now now :exp exp :leeway leeway})))
    (when (and nbf (< (+ now leeway) nbf))
      (throw (ex-info "JWT not yet valid" {:now now :nbf nbf :leeway leeway})))
    (when (and iat (< (+ now leeway) iat))
      (throw (ex-info "JWT issued in the future" {:now now :iat iat :leeway leeway})))
    true))

(defn- check-required-claims! [claims {:keys [iss aud]}]
  (when (and iss (not= iss (:iss claims)))
    (throw (ex-info "Invalid issuer" {:expected iss :actual (:iss claims)})))
  (when (and aud (not= aud (:aud claims)))
    (throw (ex-info "Invalid audience" {:expected aud :actual (:aud claims)})))
  true)

(defn- verify-signature!
  "Verify the signature over `signing-input` against `key` using `alg`. Throws
  on failure, returns true on success.

  `alg` comes from trusted CONFIG, never from the token header. Trusting the
  header's alg is the classic JWT downgrade (alg:none, or RS256→HS256 so the
  public key is used as an HMAC secret) — the caller pins it."
  [alg key ^bytes signing-input ^bytes sig]
  (case alg
    :HS256 (let [secret-bytes (if (bytes? key) key (.getBytes ^String (or key "") utf8))
                 expected (hmac-sha256 secret-bytes signing-input)]
             ;; constant-time compare (MessageDigest/isEqual, CT since JDK 6u17) —
             ;; java.util.Arrays/equals short-circuits and leaks the HMAC via timing.
             (when-not (java.security.MessageDigest/isEqual ^bytes expected sig)
               (throw (ex-info "Invalid signature" {}))))
    :RS256 (let [^PublicKey pub (cond
                                  (instance? PublicKey key) key
                                  (string? key) (public-key-from-pem key)
                                  :else (throw (ex-info ":public-key must be a PublicKey or PEM string" {:got (type key)})))
                 ^Signature v (Signature/getInstance "SHA256withRSA")]
             (.initVerify v pub)
             (.update v signing-input)
             (when-not (.verify v sig)
               (throw (ex-info "Invalid signature" {}))))
    (throw (ex-info "Unsupported alg" {:alg alg})))
  true)

(defn- resolve-issuer-key
  "Resolve the verification key for an issuer config + token `kid`.

  Issuer config forms:
    {:alg :HS256 :secret \"...\"}
    {:alg :RS256 :public-key <PEM|PublicKey>}      ; one pinned key
    {:alg :RS256 :keys {\"kid1\" <PEM|PublicKey>}}  ; static JWKS (by kid)
    {:alg :RS256 :jwks-url \"...\"}                 ; dynamic — needs key-resolver

  `key-resolver` (optional, top-level) is (fn [{:keys [iss kid alg jwks-url]}] key)
  — the injection point for live JWKS fetch/cache (WorkOS/Clerk key rotation),
  kept out of core to stay dependency-light."
  [iss {:keys [alg secret public-key keys jwks-url]} kid key-resolver]
  (or (case alg
        :HS256 secret
        :RS256 (or (when (and keys kid) (get keys kid)) public-key)
        nil)
      (when (and key-resolver (or jwks-url keys))
        (key-resolver {:iss iss :kid kid :alg alg :jwks-url jwks-url}))
      (throw (ex-info "No verification key for issuer" {:iss iss :kid kid}))))

(defn build-bearer-validator
  "Return (fn [ring-req] principal-map-or-nil) validating a Bearer JWT.

  LEGACY single-issuer (backward compatible):
    {:alg :HS256 :secret \"...\"}
    {:alg :RS256 :public-key <PEM|PublicKey>}

  TRUSTED-ISSUER REGISTRY (self-issued, WorkOS/Clerk, peer servers — one path):
    {:issuers {\"https://simmis/\"       {:alg :RS256 :public-key <PEM>}
               \"https://…workos.com\"   {:alg :RS256 :jwks-url \"…\"}
               \"peer:server-b\"          {:alg :RS256 :public-key <PEM>}}
     :key-resolver (fn [{:keys [iss kid jwks-url]}] key)  ; optional (JWKS)
     :required-claims {:aud \"simmis\"}
     :leeway-seconds 60}

  The token's `iss` selects the issuer; an unlisted `iss` is rejected. The
  verification alg is taken from the issuer config, never from the token
  header, and a token whose header alg disagrees with the pinned alg is
  rejected outright."
  [{:keys [alg secret public-key required-claims leeway-seconds issuers key-resolver]
    :or {leeway-seconds 60}}]
  (fn [req]
    (try
      (when-let [auth (get-in req [:headers "authorization"])]
        (when-let [[_ token] (re-matches #"(?i)^Bearer\s+(.+)$" auth)]
          (let [{:keys [header payload sig signing-input]} (parse-token token)
                iss (:iss payload)
                kid (:kid header)
                header-alg (some-> (:alg header) str/upper-case keyword)
                [valg vkey extra-required]
                (if issuers
                  (let [icfg (or (get issuers iss)
                                 (throw (ex-info "Untrusted issuer" {:iss iss})))]
                    [(:alg icfg)
                     (resolve-issuer-key iss icfg kid key-resolver)
                     ;; iss already validated by the registry lookup; keep aud.
                     (dissoc required-claims :iss)])
                  [(or alg :HS256)
                   (case (or alg :HS256) :HS256 secret :RS256 public-key)
                   required-claims])]
            ;; Defense in depth against a downgraded header alg.
            (when (and header-alg (not= header-alg valg))
              (throw (ex-info "Token alg does not match trusted alg"
                              {:token-alg header-alg :trusted-alg valg})))
            (verify-signature! valg vkey signing-input sig)
            (check-time-claims! payload leeway-seconds)
            (when extra-required
              (check-required-claims! payload extra-required))
            payload)))
      (catch Exception _
        ;; Be conservative: return nil on any error.
        nil))))
