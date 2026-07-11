(ns kabel.auth.store.protocol
  "Protocol for party and session storage in kabel.auth.

   A party is an authenticated principal — in the broader application this may
   be a human user. All attributes use the :party/* namespace so that simmis
   (and other apps) can build a unified party/agent identity model on top.

   Implementations:
   - kabel.auth.store.memory   - In-memory store for testing
   - kabel.auth.store.datahike - Datahike-backed store")

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
  "Get current time as java.util.Date (compatible with Datahike :db.type/instant)."
  []
  (java.util.Date.))

(defn expired?
  "Check if a session is expired."
  [session]
  (when-let [expires (:session/expires session)]
    (neg? (compare expires (now-instant)))))

(defn hash-token
  "SHA-256 hash a token string for storage."
  [token]
  (let [md (java.security.MessageDigest/getInstance "SHA-256")
        bytes (.digest md (.getBytes token "UTF-8"))]
    (apply str (map #(format "%02x" %) bytes))))
