(ns kabel.auth.password
  "Password hashing and validation using bcrypt.

   Uses buddy-hashers for secure password hashing with bcrypt."
  (:require [buddy.hashers :as hashers]))

(def ^:private default-min-length 8)

(defn hash-password
  "Hash a password using bcrypt.
   Returns the hashed password string."
  [password]
  (hashers/derive password {:alg :bcrypt+sha512}))

(defn verify-password
  "Verify a password against a hash.
   Returns true if the password matches, false otherwise."
  [password hash]
  (try
    (:valid (hashers/verify password hash))
    (catch Exception _
      false)))

(defn validate-password
  "Validate a password meets requirements.
   Returns {:valid true} or {:valid false :errors [...]}

   Options:
     :min-length - Minimum password length (default 8)"
  ([password]
   (validate-password password {}))
  ([password {:keys [min-length] :or {min-length default-min-length}}]
   (let [errors (cond-> []
                  (nil? password)
                  (conj "Password is required")

                  (and password (< (count password) min-length))
                  (conj (str "Password must be at least " min-length " characters")))]
     (if (empty? errors)
       {:valid true}
       {:valid false :errors errors}))))

;; Refresh token generation

(defn generate-refresh-token
  "Generate a random refresh token.
   Returns a base64-encoded random string."
  []
  (let [bytes (byte-array 32)]
    (.nextBytes (java.security.SecureRandom.) bytes)
    (.encodeToString (java.util.Base64/getUrlEncoder) bytes)))
