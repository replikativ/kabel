(ns kabel.auth.routes
  "Ring routes for authentication endpoints.

   Provides:
   - POST /register - Email + password registration
   - POST /login    - Email + password login
   - POST /refresh  - Refresh access token
   - POST /logout   - Invalidate session
   - POST /dev      - Dev mode: authenticate as any party

   Usage with reitit (optional dependency):
     (require '[reitit.ring :as ring])
     (def app (ring/ring-handler
                (ring/router
                  [[\"/auth\" (auth-routes config)]])))

   Usage with Ring directly:
     (def handler (auth-handler config))"
  (:require [kabel.auth.store.protocol :as store]
            [kabel.auth.password :as pwd]
            [kabel.auth.jwt :as jwt]
            [kabel.auth.config :as cfg]
            [clojure.string :as str]))

(defn- json-response [status body]
  {:status status
   :headers {"Content-Type" "application/json"}
   :body body})

(defn- error-response [status error message]
  (json-response status {:error error :message message}))

(defn- generate-tokens
  "Generate access and refresh tokens for a party."
  [config party]
  (let [{:keys [secret private-key issuer audience access-token-expiry refresh-token-expiry]}
        (:jwt config)
        now (quot (System/currentTimeMillis) 1000)
        extra-claims (when-let [f (:extra-claims-fn config)]
                       (f party))
        access-claims (merge {:sub (str (:party/id party))
                              :email (:party/email party)
                              :name (:party/display-name party)
                              :iat now
                              :exp (+ now access-token-expiry)
                              :iss issuer
                              :aud audience}
                             extra-claims)
        access-token (if secret
                       (jwt/sign-hs256 secret access-claims)
                       (jwt/sign-rs256 private-key access-claims))
        refresh-token (pwd/generate-refresh-token)]
    {:access_token access-token
     :refresh_token refresh-token
     :expires_in access-token-expiry
     :refresh_expires_in refresh-token-expiry}))

(defn- create-session!
  [config party refresh-token request]
  (let [store (:store config)
        refresh-expiry (get-in config [:jwt :refresh-token-expiry])
        expires (java.util.Date. (+ (System/currentTimeMillis) (* refresh-expiry 1000)))
        token-hash (store/hash-token refresh-token)]
    (store/create-session! store
                           {:session/party-id (:party/id party)
                            :session/refresh-token-hash token-hash
                            :session/expires expires
                            :session/user-agent (get-in request [:headers "user-agent"])
                            :session/ip (:remote-addr request)})))

(defn register-handler
  [config request]
  (let [{:keys [email password name]} (:body request)
        store (:store config)
        password-config (:password config)]
    (cond
      (not (:enabled password-config))
      (error-response 400 "password-disabled" "Password authentication is disabled")

      (str/blank? email)
      (error-response 400 "email-required" "Email is required")

      (store/find-user-by-email store email)
      (error-response 400 "email-exists" "An account with this email already exists")

      :else
      (let [validation (pwd/validate-password password password-config)]
        (if-not (:valid validation)
          (error-response 400 "password-weak" (first (:errors validation)))
          (let [hash (pwd/hash-password password)
                party (store/create-user! store
                                          {:party/email email
                                           :party/display-name name
                                           :party/password-hash hash
                                           :party/auth-providers #{:password}
                                           :party/email-verified false})]
            (json-response 201 {:user {:id (str (:party/id party))
                                       :email (:party/email party)
                                       :name (:party/display-name party)}})))))))

(defn login-handler
  [config request]
  (let [{:keys [email password]} (:body request)
        store (:store config)]
    (cond
      (not (get-in config [:password :enabled]))
      (error-response 400 "password-disabled" "Password authentication is disabled")

      (or (str/blank? email) (str/blank? password))
      (error-response 400 "credentials-required" "Email and password are required")

      :else
      (if-let [party (store/find-user-by-email store email)]
        (if (pwd/verify-password password (:party/password-hash party))
          (let [tokens (generate-tokens config party)
                _ (create-session! config party (:refresh_token tokens) request)
                _ (try (store/update-user! store (:party/id party)
                                           {:party/last-login (java.util.Date.)})
                       (catch Exception _ nil))]
            (json-response 200 (assoc tokens
                                      :user (cond-> {:id (str (:party/id party))
                                                     :email (:party/email party)
                                                     :name (:party/display-name party)}
                                              (:party/role party) (assoc :role (name (:party/role party)))))))
          (error-response 401 "invalid-credentials" "Invalid email or password"))
        (error-response 401 "invalid-credentials" "Invalid email or password")))))

(defn refresh-handler
  [config request]
  (let [refresh-token (or (get-in request [:body :refresh_token])
                          (get-in request [:body :refresh-token]))
        store (:store config)]
    (if (str/blank? refresh-token)
      (error-response 400 "token-required" "Refresh token is required")
      (let [token-hash (store/hash-token refresh-token)
            session (store/find-session-by-token-hash store token-hash)]
        (if-not session
          (error-response 401 "invalid-refresh-token" "Refresh token is invalid or expired")
          (if-let [party (store/find-user-by-id store (:session/party-id session))]
            (let [{:keys [secret private-key issuer audience access-token-expiry]}
                  (:jwt config)
                  now (quot (System/currentTimeMillis) 1000)
                  extra-claims (when-let [f (:extra-claims-fn config)]
                                 (f party))
                  access-claims (merge {:sub (str (:party/id party))
                                        :email (:party/email party)
                                        :name (:party/display-name party)
                                        :iat now
                                        :exp (+ now access-token-expiry)
                                        :iss issuer
                                        :aud audience}
                                       extra-claims)
                  access-token (if secret
                                 (jwt/sign-hs256 secret access-claims)
                                 (jwt/sign-rs256 private-key access-claims))]
              (json-response 200 {:access_token access-token
                                  :expires_in access-token-expiry}))
            (error-response 401 "user-not-found" "Party no longer exists")))))))

(defn logout-handler
  [config request]
  (let [refresh-token (or (get-in request [:body :refresh_token])
                          (get-in request [:body :refresh-token]))
        store (:store config)]
    (if (str/blank? refresh-token)
      (json-response 200 {:success true})
      (let [token-hash (store/hash-token refresh-token)
            session (store/find-session-by-token-hash store token-hash)]
        (when session
          (store/delete-session! store (:session/id session)))
        (json-response 200 {:success true})))))

(defn dev-handler
  [config request]
  (if-not (:dev-mode config)
    (error-response 404 "not-found" "Not found")
    (let [{:keys [email name]} (:body request)
          store (:store config)]
      (if (str/blank? email)
        (error-response 400 "email-required" "Email is required")
        (let [party (or (store/find-user-by-email store email)
                        (store/create-user! store
                                            {:party/email email
                                             :party/display-name (or name email)
                                             :party/auth-providers #{:dev}
                                             :party/email-verified true}))
              tokens (generate-tokens config party)
              _ (create-session! config party (:refresh_token tokens) request)]
          (json-response 200 (assoc tokens
                                    :user {:id (str (:party/id party))
                                           :email (:party/email party)
                                           :name (:party/display-name party)})))))))

(defn auth-routes
  [config]
  (let [config (cfg/merge-config config)]
    [["" {:post {:handler (fn [_] (json-response 200 {:endpoints ["/register" "/login" "/refresh" "/logout"]}))}}]
     ["/register" {:post {:handler (fn [req] (register-handler config req))}}]
     ["/login"    {:post {:handler (fn [req] (login-handler config req))}}]
     ["/refresh"  {:post {:handler (fn [req] (refresh-handler config req))}}]
     ["/logout"   {:post {:handler (fn [req] (logout-handler config req))}}]
     ["/dev"      {:post {:handler (fn [req] (dev-handler config req))}}]]))

(defn auth-handler
  [config]
  (let [config (cfg/merge-config config)]
    (fn [request]
      (let [method (:request-method request)
            path (:uri request)]
        (if (= method :post)
          (case path
            "/register" (register-handler config request)
            "/login"    (login-handler config request)
            "/refresh"  (refresh-handler config request)
            "/logout"   (logout-handler config request)
            "/dev"      (dev-handler config request)
            (error-response 404 "not-found" "Not found"))
          (error-response 405 "method-not-allowed" "Method not allowed"))))))
