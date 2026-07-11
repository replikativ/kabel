(ns kabel.auth.store.datahike
  "Datahike-backed implementation of AuthStore.

   Takes an existing Datahike connection (shared with system DB).
   Schema must already include the :party/* and :session/* attributes."
  (:require [kabel.auth.store.protocol :as p]
            [datahike.api :as d])
  (:import [java.util UUID]))

(defrecord DatahikeAuthStore [conn]
  p/AuthStore

  (create-user! [_ party-data]
    (let [email (:party/email party-data)]
      (when-not email
        (throw (ex-info "Email is required" {:type :validation-error})))
      (when (seq (d/q '[:find ?e :in $ ?email :where [?e :party/email ?email]]
                      @conn email))
        (throw (ex-info "Email already exists" {:type :email-exists :email email})))
      (let [party-id (UUID/randomUUID)
            party (-> party-data
                      (assoc :party/id party-id
                             :party/type :human
                             :party/created (p/now-instant))
                      (dissoc :party/auth-providers))]
        (d/transact conn [party
                          {:party/id party-id
                           :party/auth-providers (or (:party/auth-providers party-data)
                                                     #{:password})}])
        (assoc party :party/auth-providers
               (or (:party/auth-providers party-data) #{:password})))))

  (find-user-by-email [_ email]
    (when-let [eid (d/q '[:find ?e . :in $ ?email :where [?e :party/email ?email]]
                        @conn email)]
      (dissoc (d/pull @conn '[*] eid) :db/id)))

  (find-user-by-id [_ party-id]
    (when-let [eid (d/q '[:find ?e . :in $ ?pid :where [?e :party/id ?pid]]
                        @conn party-id)]
      (dissoc (d/pull @conn '[*] eid) :db/id)))

  (update-user! [_ party-id updates]
    (if-let [eid (d/q '[:find ?e . :in $ ?pid :where [?e :party/id ?pid]]
                      @conn party-id)]
      (let [new-email (:party/email updates)]
        (when new-email
          (let [existing (d/q '[:find ?e . :in $ ?email :where [?e :party/email ?email]]
                              @conn new-email)]
            (when (and existing (not= existing eid))
              (throw (ex-info "Email already exists" {:type :email-exists :email new-email})))))
        (d/transact conn [(assoc updates :party/id party-id)])
        (dissoc (d/pull @conn '[*] eid) :db/id))
      (throw (ex-info "Party not found" {:type :party-not-found :party-id party-id}))))

  (create-session! [_ session-data]
    (let [session-id (UUID/randomUUID)
          token-hash (:session/refresh-token-hash session-data)
          session (assoc session-data
                         :session/id session-id
                         :session/created (p/now-instant))]
      (when-not token-hash
        (throw (ex-info "Refresh token hash is required" {:type :validation-error})))
      (d/transact conn [session])
      session))

  (find-session-by-token-hash [_ token-hash]
    (when-let [eid (d/q '[:find ?e . :in $ ?hash
                          :where [?e :session/refresh-token-hash ?hash]]
                        @conn token-hash)]
      (let [session (dissoc (d/pull @conn '[*] eid) :db/id)]
        (when-not (p/expired? session)
          session))))

  (delete-session! [_ session-id]
    (if-let [eid (d/q '[:find ?e . :in $ ?sid :where [?e :session/id ?sid]]
                      @conn session-id)]
      (do (d/transact conn [[:db/retractEntity eid]])
          true)
      false))

  (delete-user-sessions! [_ party-id]
    (let [session-eids (d/q '[:find [?e ...] :in $ ?pid
                              :where [?e :session/party-id ?pid]]
                            @conn party-id)]
      (when (seq session-eids)
        (d/transact conn (mapv (fn [eid] [:db/retractEntity eid]) session-eids)))
      (count session-eids)))

  (delete-expired-sessions! [_]
    (let [now (p/now-instant)
          expired-eids (d/q '[:find [?e ...] :in $ ?now
                              :where
                              [?e :session/expires ?exp]
                              [(< ?exp ?now)]]
                            @conn now)]
      (when (seq expired-eids)
        (d/transact conn (mapv (fn [eid] [:db/retractEntity eid]) expired-eids)))
      (count expired-eids))))

(defn datahike-auth-store
  "Create a Datahike-backed auth store.

   Takes an existing Datahike connection. The database must already have
   the :party/* and :session/* schema attributes transacted.

   Usage:
     (def store (datahike-auth-store conn))
     (p/create-user! store {:party/email \"test@example.com\"
                            :party/display-name \"Test\"})"
  [conn]
  (->DatahikeAuthStore conn))
