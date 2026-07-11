(ns kabel.auth.jwks
  "JWKS resolver — fetch an issuer's JSON Web Key Set and produce the
  `:key-resolver` that `jwt/build-bearer-validator` calls for a `:jwks-url`
  issuer. This is what makes WorkOS / Clerk (rotating RS256 keys published at a
  `jwks_uri`, selected by the token header `kid`) verify out of the box.

  Zero extra dependencies: `java.net.http` for the fetch (JDK 11+),
  `jsonista` (already a dep) for the parse, `java.security` for the key."
  (:require [jsonista.core :as j])
  (:import (java.net URI)
           (java.net.http HttpClient HttpRequest HttpResponse$BodyHandlers)
           (java.time Duration)
           (java.util Base64)
           (java.math BigInteger)
           (java.security KeyFactory PublicKey)
           (java.security.spec RSAPublicKeySpec)))

(def ^:private json-mapper (j/object-mapper {:decode-key-fn true}))

(defn- b64url->bigint [^String s]
  (BigInteger. 1 ^bytes (.decode (Base64/getUrlDecoder) s)))

(defn jwk->public-key
  "Build an RSA `PublicKey` from a JWK map {:kty \"RSA\" :n <b64url> :e <b64url>}.
  Returns nil for non-RSA keys (kabel-auth verifies RS256)."
  ^PublicKey [{:keys [kty n e]}]
  (when (and (= "RSA" kty) n e)
    (.generatePublic (KeyFactory/getInstance "RSA")
                     (RSAPublicKeySpec. (b64url->bigint n) (b64url->bigint e)))))

(defn parse-jwks
  "Parse a JWKS JSON body into {kid -> PublicKey} (RSA keys only)."
  [^String body]
  (->> (:keys (j/read-value body json-mapper))
       (keep (fn [{:keys [kid] :as jwk}]
               (when-let [pk (jwk->public-key jwk)] [kid pk])))
       (into {})))

(defn fetch-jwks
  "GET `jwks-url` and return {kid -> PublicKey}. Throws on a non-2xx response."
  [^String jwks-url]
  (let [client (-> (HttpClient/newBuilder)
                   (.connectTimeout (Duration/ofSeconds 10))
                   (.build))
        req (-> (HttpRequest/newBuilder (URI/create jwks-url))
                (.timeout (Duration/ofSeconds 10))
                (.GET)
                (.build))
        resp (.send client req (HttpResponse$BodyHandlers/ofString))]
    (when-not (<= 200 (.statusCode resp) 299)
      (throw (ex-info "JWKS fetch failed" {:url jwks-url :status (.statusCode resp)})))
    (parse-jwks (.body resp))))

(defn make-key-resolver
  "Return a `:key-resolver` (fn [{:keys [jwks-url kid]}] -> PublicKey) with a
  per-url cache. A `kid` that isn't cached (a rotated or newly-published key)
  triggers ONE refetch; `ttl-ms` bounds staleness otherwise (default 1h). This
  is the shape kabel-auth's issuer registry expects for a `:jwks-url` issuer.

  `opts`:
    :ttl-ms   — cache lifetime per url (default 3600000)
    :fetch-fn — (fn [url] -> {kid -> PublicKey}); default `fetch-jwks`
                (injectable for tests / custom transports)."
  ([] (make-key-resolver {}))
  ([{:keys [ttl-ms fetch-fn] :or {ttl-ms 3600000 fetch-fn fetch-jwks}}]
   (let [cache (atom {})]                                   ; url -> {:at ms :keys {kid -> pk}}
     (fn [{:keys [jwks-url kid]}]
       (when jwks-url
         (let [now (System/currentTimeMillis)
               entry (get @cache jwks-url)
               fresh? (and entry (< (- now (:at entry)) ttl-ms))
               ks (if (and fresh? (or (nil? kid) (contains? (:keys entry) kid)))
                    (:keys entry)
                    (let [ks (fetch-fn jwks-url)]
                      (swap! cache assoc jwks-url {:at now :keys ks})
                      ks))]
           (if kid (get ks kid) (first (vals ks)))))))))
