(ns kabel.auth.store.memory
  "In-memory implementation of AuthStore for testing.

   All data is stored in atoms and lost when the JVM exits.
   Thread-safe for concurrent access."
  (:require [kabel.auth.store.protocol :as p])
  (:import [java.util UUID]))

(defrecord MemoryAuthStore [parties-by-id parties-by-email sessions-by-id sessions-by-token-hash]
  p/AuthStore

  (create-user! [_ party-data]
    (let [email (:party/email party-data)]
      (when-not email
        (throw (ex-info "Email is required" {:type :validation-error})))
      (when (get @parties-by-email email)
        (throw (ex-info "Email already exists" {:type :email-exists :email email})))
      (let [party-id (UUID/randomUUID)
            party (assoc party-data
                         :party/id party-id
                         :party/type :human
                         :party/created (p/now-instant))]
        (swap! parties-by-id assoc party-id party)
        (swap! parties-by-email assoc email party)
        party)))

  (find-user-by-email [_ email]
    (get @parties-by-email email))

  (find-user-by-id [_ party-id]
    (get @parties-by-id party-id))

  (update-user! [_ party-id updates]
    (if-let [existing (get @parties-by-id party-id)]
      (let [old-email (:party/email existing)
            new-email (:party/email updates)
            updated (merge existing updates)]
        (when (and new-email (not= old-email new-email))
          (when (get @parties-by-email new-email)
            (throw (ex-info "Email already exists" {:type :email-exists :email new-email})))
          (swap! parties-by-email dissoc old-email)
          (swap! parties-by-email assoc new-email updated))
        (swap! parties-by-id assoc party-id updated)
        (when-not (and new-email (not= old-email new-email))
          (swap! parties-by-email assoc (:party/email updated) updated))
        updated)
      (throw (ex-info "Party not found" {:type :party-not-found :party-id party-id}))))

  (create-session! [_ session-data]
    (let [session-id (UUID/randomUUID)
          token-hash (:session/refresh-token-hash session-data)
          session (assoc session-data
                         :session/id session-id
                         :session/created (p/now-instant))]
      (when-not token-hash
        (throw (ex-info "Refresh token hash is required" {:type :validation-error})))
      (swap! sessions-by-id assoc session-id session)
      (swap! sessions-by-token-hash assoc token-hash session)
      session))

  (find-session-by-token-hash [_ token-hash]
    (when-let [session (get @sessions-by-token-hash token-hash)]
      (when-not (p/expired? session)
        session)))

  (delete-session! [_ session-id]
    (if-let [session (get @sessions-by-id session-id)]
      (do
        (swap! sessions-by-id dissoc session-id)
        (swap! sessions-by-token-hash dissoc (:session/refresh-token-hash session))
        true)
      false))

  (delete-user-sessions! [_ party-id]
    (let [party-sessions (->> @sessions-by-id
                              vals
                              (filter #(= party-id (:session/party-id %))))]
      (doseq [session party-sessions]
        (swap! sessions-by-id dissoc (:session/id session))
        (swap! sessions-by-token-hash dissoc (:session/refresh-token-hash session)))
      (count party-sessions)))

  (delete-expired-sessions! [_]
    (let [expired (->> @sessions-by-id
                       vals
                       (filter p/expired?))]
      (doseq [session expired]
        (swap! sessions-by-id dissoc (:session/id session))
        (swap! sessions-by-token-hash dissoc (:session/refresh-token-hash session)))
      (count expired))))

(defn memory-auth-store
  "Create a new in-memory auth store.

   Usage:
     (def store (memory-auth-store))
     (p/create-user! store {:party/email \"test@example.com\"
                            :party/display-name \"Test\"})"
  []
  (->MemoryAuthStore (atom {}) (atom {}) (atom {}) (atom {})))
