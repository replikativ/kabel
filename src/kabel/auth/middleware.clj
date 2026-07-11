(ns kabel.auth.middleware
  "Ring middleware for JWT authentication.

   Provides:
   - wrap-auth - Extract and validate JWT from Authorization header
   - require-auth - Reject requests without valid authentication"
  (:require [kabel.auth.jwt :as jwt]
            [clojure.string :as str]))

(defn- extract-bearer-token
  "Extract Bearer token from Authorization header."
  [request]
  (when-let [auth (get-in request [:headers "authorization"])]
    (when-let [[_ token] (re-matches #"(?i)^Bearer\s+(.+)$" auth)]
      token)))

(defn wrap-auth
  "Ring middleware that extracts and validates JWT from Authorization header.

   When valid, adds :kabel/principal to the request with the JWT claims.
   When invalid or missing, passes through without principal (for public endpoints).

   Options:
     :jwt - JWT configuration (required unless dev-mode)
            {:secret \"...\" :alg :HS256} or {:public-key \"...\" :alg :RS256}
     :dev-mode - When true, skip validation and use default principal
     :dev-principal - Principal to use in dev mode (default: {:sub \"dev-user\"})

   Usage:
     (-> handler
         (wrap-auth {:jwt {:secret \"...\" :alg :HS256}}))"
  [handler {:keys [jwt dev-mode dev-principal]}]
  (let [validator (when-not dev-mode
                    (jwt/build-bearer-validator jwt))
        default-dev-principal {:sub "dev-user"
                               :email "dev@localhost"
                               :name "Developer"}]
    (fn [request]
      (let [token (extract-bearer-token request)
            principal (cond
                        ;; Dev mode - use dev principal
                        dev-mode
                        (or dev-principal default-dev-principal)

                        ;; No token - pass through
                        (nil? token)
                        nil

                        ;; Validate token
                        :else
                        (validator {:headers {"authorization" (str "Bearer " token)}}))]
        (handler (cond-> request
                   principal (assoc :kabel/principal principal)))))))

(defn require-auth
  "Ring middleware that rejects requests without valid authentication.

   Must be used after wrap-auth. Returns 401 if :kabel/principal is missing.

   Options:
     :on-unauthorized - Custom handler for unauthorized requests
                        (fn [request] response)

   Usage:
     (-> handler
         require-auth
         (wrap-auth config))"
  ([]
   (require-auth {}))
  ([{:keys [on-unauthorized]}]
   (let [default-response {:status 401
                           :headers {"Content-Type" "application/json"}
                           :body {:error "unauthorized"
                                  :message "Authentication required"}}]
     (fn [handler]
       (fn [request]
         (if (:kabel/principal request)
           (handler request)
           (if on-unauthorized
             (on-unauthorized request)
             default-response)))))))
