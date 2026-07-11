(ns kabel.auth.jwt
  "JWT utilities for Bearer-token auth.

  HS256 (HMAC-SHA256) verification is CROSS-PLATFORM (JVM + browser/Node) via
  geheimnis — so a ClojureScript peer can verify tokens without a JVM. RS256
  verification and all token *signing* (issuing) are JVM-only for now; RS256 on
  CLJS lands with the async Web Crypto tier.

  Security: the verification alg is always pinned from TRUSTED CONFIG, never read
  from the token header — that defeats the classic downgrade (alg:none, or
  RS256->HS256 so a public key is used as an HMAC secret). The HMAC compare is
  constant-time (geheimnis `ct-equal?`)."
  (:require [clojure.string :as str]
            [org.replikativ.geheimnis.core :as gc]
            [org.replikativ.geheimnis.hash :as gh]
            [org.replikativ.geheimnis.codec :as codec]
            #?(:clj [jsonista.core :as j]))
  #?(:clj (:import (java.util Date Base64)
                   (java.security KeyFactory PublicKey PrivateKey Signature)
                   (java.security.spec X509EncodedKeySpec PKCS8EncodedKeySpec))))

#?(:clj (def ^:private json-mapper (j/object-mapper {:decode-key-fn true})))

;; --- JSON <-> base64url (cross-platform) ---

(defn- json->bytes [m]
  #?(:clj  (j/write-value-as-bytes m)
     :cljs (codec/str->bytes (js/JSON.stringify (clj->js m)))))

(defn- bytes->json [bs]
  #?(:clj  (j/read-value bs json-mapper)
     :cljs (js->clj (js/JSON.parse (codec/bytes->str bs)) :keywordize-keys true)))

(defn- json->b64 [m] (codec/bytes->b64url (json->bytes m)))
(defn- b64->json [s] (bytes->json (codec/b64url->bytes s)))

(defn- ->key-bytes
  "Coerce an HMAC key (string secret or raw bytes) to bytes."
  [k]
  (cond
    (string? k) (codec/str->bytes k)
    #?(:clj (bytes? k) :cljs (instance? js/Uint8Array k)) k
    :else (codec/str->bytes (str k))))

;; --- RSA PEM helpers (JVM only) ---

#?(:clj
   (defn- strip-pem ^String [^String pem]
     (-> pem
         (str/replace #"-----BEGIN [^-]+-----" "")
         (str/replace #"-----END [^-]+-----" "")
         (str/replace #"\s" ""))))

#?(:clj
   (defn- public-key-from-pem ^PublicKey [^String pem]
     (.generatePublic (KeyFactory/getInstance "RSA")
                      (X509EncodedKeySpec. (.decode (Base64/getDecoder) (strip-pem pem))))))

#?(:clj
   (defn- private-key-from-pem ^PrivateKey [^String pem]
     (.generatePrivate (KeyFactory/getInstance "RSA")
                       (PKCS8EncodedKeySpec. (.decode (Base64/getDecoder) (strip-pem pem))))))

;; --- signing (token issuing) ---

(defn sign-hs256
  "Create an HS256 JWT (helper / tests). Cross-platform.
  claims map may include :exp and :nbf as epoch seconds."
  [secret claims]
  (let [header-b64 (json->b64 {:alg "HS256" :typ "JWT"})
        payload-b64 (json->b64 claims)
        signing-input (codec/str->bytes (str header-b64 "." payload-b64))
        sig (gh/hmac-sha256 (->key-bytes secret) signing-input)]
    (str header-b64 "." payload-b64 "." (codec/bytes->b64url sig))))

#?(:clj
   (defn sign-rs256
     "Create an RS256 JWT (JVM only) from a PrivateKey or PEM string."
     [priv-key-or-pem claims]
     (let [^PrivateKey priv (if (instance? PrivateKey priv-key-or-pem)
                              priv-key-or-pem
                              (private-key-from-pem priv-key-or-pem))
           header-b64 (json->b64 {:alg "RS256" :typ "JWT"})
           payload-b64 (json->b64 claims)
           signing-input (codec/str->bytes (str header-b64 "." payload-b64))
           ^Signature s (Signature/getInstance "SHA256withRSA")]
       (.initSign s priv)
       (.update s signing-input)
       (str header-b64 "." payload-b64 "." (codec/bytes->b64url (.sign s))))))

;; --- parsing / claim checks ---

(defn- parse-token [token]
  (let [parts (when token (str/split token #"\."))]
    (when (not= 3 (count parts))
      (throw (ex-info "Malformed JWT (expected 3 parts)" {:parts (count parts)})))
    (let [[h p s] parts]
      {:header (b64->json h)
       :payload (b64->json p)
       :sig (codec/b64url->bytes s)
       :signing-input (codec/str->bytes (str h "." p))})))

(defn- now-epoch []
  #?(:clj  (long (/ (.getTime (Date.)) 1000))
     :cljs (long (/ (.now js/Date) 1000))))

(defn- check-time-claims! [claims leeway]
  (let [now (now-epoch)
        {:keys [exp nbf iat]} claims]
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

;; --- verification ---

(defn- verify-signature!
  "Verify the signature over `signing-input` against `key` using `alg` (pinned by
  the caller from trusted config, NOT the token header). HS256 is cross-platform;
  RS256 is JVM-only for now."
  [alg key ^bytes signing-input sig]
  (case alg
    :HS256 (when-not (gc/ct-equal? (gh/hmac-sha256 (->key-bytes key) signing-input) sig)
             (throw (ex-info "Invalid signature" {})))
    :RS256 #?(:clj (let [^PublicKey pub (cond
                                          (instance? PublicKey key) key
                                          (string? key) (public-key-from-pem key)
                                          :else (throw (ex-info ":public-key must be a PublicKey or PEM string" {:got (type key)})))
                         ^Signature v (Signature/getInstance "SHA256withRSA")]
                     (.initVerify v pub)
                     (.update v signing-input)
                     (when-not (.verify v sig)
                       (throw (ex-info "Invalid signature" {}))))
              :cljs (throw (ex-info "RS256 verification is not yet supported on CLJS (pending the async Web Crypto tier)"
                                    {:alg :RS256})))
    (throw (ex-info "Unsupported alg" {:alg alg})))
  true)

(defn- resolve-issuer-key
  "Resolve the verification key for an issuer config + token `kid`.
  Forms: {:alg :HS256 :secret ..} | {:alg :RS256 :public-key <PEM|PublicKey>} |
  {:alg :RS256 :keys {kid -> key}} | {:alg :RS256 :jwks-url ..}.
  `key-resolver` (optional) is the injection point for live JWKS fetch."
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

  LEGACY single-issuer: {:alg :HS256 :secret ..} | {:alg :RS256 :public-key ..}
  TRUSTED-ISSUER REGISTRY:
    {:issuers {\"iss\" {:alg :RS256 :public-key <PEM>} …}
     :key-resolver (fn [{:keys [iss kid jwks-url]}] key)  ; optional (JWKS)
     :required-claims {:aud \"…\"} :leeway-seconds 60}

  The token's `iss` selects the issuer; an unlisted `iss` is rejected. The alg is
  taken from the issuer config, never the header, and a header alg that disagrees
  with the pinned alg is rejected."
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
      (catch #?(:clj Exception :cljs :default) _
        ;; Be conservative: return nil on any error.
        nil))))
