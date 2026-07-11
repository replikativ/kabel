(ns kabel.auth.store.protocol
  "Protocol for party and session storage in kabel.auth.

   A party is an authenticated principal — in the broader application this may
   be a human user. All attributes use the :party/* namespace so that simmis
   (and other apps) can build a unified party/agent identity model on top.

   This namespace is `.cljc` so a ClojureScript peer (browser or Node) can
   *implement* AuthStore and act as an identity/session holder — the precondition
   for peers authenticating against each other. The datahike-backed store stays
   JVM-only (datahike is a JVM database); the memory store (and any future
   konserve-backed store) is portable. The helpers below reuse geheimnis so the
   token digest is byte-identical on every platform.

   Implementations:
   - kabel.auth.store.memory   - In-memory store, portable (browser/Node/JVM)
   - kabel.auth.store.datahike - Datahike-backed store (JVM only)"
  (:require [org.replikativ.geheimnis.hash :as gh]
            [org.replikativ.geheimnis.codec :as gc]))

(defprotocol AuthStore
  "Abstraction over party and session storage."

  (create-user! [store party-data]
    "Create a new party. party-data should contain at minimum :party/email.
     Returns the created party map with :party/id (UUID) added.
     Throws if email already exists.")

  (find-user-by-email [store email]
    "Find party by email address.
     Returns party map or nil if not found.")

  (find-user-by-id [store party-id]
    "Find party by UUID.
     Returns party map or nil if not found.")

  (update-user! [store party-id updates]
    "Update party attributes. updates is a map of attributes to change.
     Returns updated party map.
     Throws if party not found.")

  (create-session! [store session-data]
    "Create a new session. session-data should contain:
     - :session/party-id           - UUID of the party
     - :session/refresh-token-hash - SHA-256 hash of refresh token
     - :session/expires            - Instant when session expires
     Optional:
     - :session/user-agent
     - :session/ip
     Returns session map with :session/id added.")

  (find-session-by-token-hash [store token-hash]
    "Find session by hashed refresh token.
     Returns session map or nil if not found or expired.")

  (delete-session! [store session-id]
    "Delete a session by ID. Returns true if deleted, false if not found.")

  (delete-user-sessions! [store party-id]
    "Delete all sessions for a party. Returns count of deleted sessions.")

  (delete-expired-sessions! [store]
    "Clean up expired sessions. Returns count of deleted sessions."))

(defn now-instant
  "Current time as a platform-native date: java.util.Date on the JVM (compatible
   with Datahike :db.type/instant), js/Date on ClojureScript. Only ever compared
   against another value from the same platform, via `expired?`."
  []
  #?(:clj (java.util.Date.) :cljs (js/Date.)))

(defn gen-uuid
  "Fresh random UUID: java.util.UUID on the JVM, cljs.core/UUID on ClojureScript."
  []
  #?(:clj (java.util.UUID/randomUUID) :cljs (random-uuid)))

(defn- millis
  "Epoch milliseconds of a platform-native date. `.getTime` exists on both
   java.util.Date and js/Date, so the comparison in `expired?` is portable."
  [d]
  #?(:clj (.getTime ^java.util.Date d) :cljs (.getTime d)))

(defn expired?
  "Check if a session is expired."
  [session]
  (when-let [expires (:session/expires session)]
    (< (millis expires) (millis (now-instant)))))

(defn hash-token
  "SHA-256 hash a token string to a lowercase hex digest, for storage. Portable
   via geheimnis — goog.crypt on ClojureScript, MessageDigest on the JVM — so the
   digest is byte-identical across peers."
  [token]
  (gc/bytes->hex (gh/sha256 (gc/str->bytes token))))
