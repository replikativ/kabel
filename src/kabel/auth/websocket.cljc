(ns kabel.auth.websocket
  "Bidirectional authentication protocol for kabel.

   NOTE: Despite the 'websocket' namespace name, this is transport-agnostic.
   It works with any kabel transport (WebSocket, in-memory, etc.).

   Provides a single middleware that handles both client and server auth:
   - Client role: send :kabel/auth, wait for response
   - Server role: receive :kabel/auth, validate, respond

   Both roles can be enabled independently for true P2P auth.

   Usage:
     (require '[kabel.auth.websocket :as auth])

     ;; Server-only (validates incoming, doesn't auth outgoing)
     (auth/auth-middleware {:server {:jwt {...}}})

     ;; Client-only (authenticates to remote, permits all incoming)
     (auth/auth-middleware {:client {:token \"...\"}})

     ;; Bidirectional (both sides authenticate)
     (auth/auth-middleware
       {:client {:token \"my-token\"}
        :server {:jwt {...}}})"
  (:require #?(:clj [kabel.auth.jwt :as jwt])
            [clojure.core.async :as async :refer [chan <! >! close! put! alts! timeout go go-loop]]
            [replikativ.logging :refer [warn info debug]]
            [superv.async :refer [go-try go-loop-try <? S]])
  #?(:cljs (:require-macros [clojure.core.async :refer [go go-loop]]
                            [superv.async :refer [go-try go-loop-try <? S]])))

;; Connection state

(def ^:dynamic *principal*
  "Dynamic binding for the current authenticated principal.
   Set by the WebSocket auth middleware when processing authenticated messages."
  nil)

;; Auth message types
(def auth-msg-type :kabel/auth)
(def auth-refresh-msg-type :kabel/auth-refresh)
(def auth-ok-msg-type :kabel/auth-ok)
(def auth-error-msg-type :kabel/auth-error)

#?(:clj
   (defn- validate-token
     "Validate a JWT token and return the claims or nil."
     [jwt-config token]
     (when token
       (try
         (let [validator (jwt/build-bearer-validator jwt-config)
               req {:headers {"authorization" (str "Bearer " token)}}]
           (validator req))
         (catch Exception e
           (warn {:event :token-validation-failed :error (.getMessage e)})
           nil)))))

(defn validate-middleware
  "Kabel middleware that validates auth FROM a remote peer.

   Handles :kabel/auth and :kabel/auth-refresh messages.
   Adds :kabel/principal to all authenticated messages.

   This verifies the REMOTE peer's identity.
   Use `authenticate-middleware` to prove MY identity to the remote.

   Options:
     :jwt - JWT configuration for token validation
            {:secret \"...\" :alg :HS256} or {:public-key \"...\" :alg :RS256}
     :dev-mode - When true, skip token validation
     :dev-principal - Principal to use in dev mode
     :on-auth - Optional callback (fn [principal]) called on successful auth

   Messages:
     Remote -> {:type :kabel/auth :token \"access_token\"}
     Me     -> {:type :kabel/auth-ok :principal {...}}
            or {:type :kabel/auth-error :error \"message\"}

     Remote -> {:type :kabel/auth-refresh :token \"new_access_token\"}
     Me     -> {:type :kabel/auth-ok}"
  [{:keys [jwt dev-mode dev-principal on-auth]}]
  (let [default-dev-principal {:sub "dev-user"
                               :email "dev@localhost"
                               :name "Developer"}]
    (fn [[S peer [in out]]]
      (let [new-in (chan)
            new-out (chan)
            ;; Per-connection principal state
            principal-atom (atom nil)]

        ;; Process incoming messages
        (go-loop-try S [msg (<? S in)]
                     (when msg
                       (let [msg-type (:type msg)]
                         (cond
                ;; Initial authentication
                           (= msg-type auth-msg-type)
                           (let [token (:token msg)
                                 principal #?(:clj (if dev-mode
                                                     (or dev-principal default-dev-principal)
                                                     (validate-token jwt token))
                                              :cljs (or dev-principal default-dev-principal))]
                             (if principal
                               (do
                                 (reset! principal-atom principal)
                                 (when on-auth (on-auth principal))
                                 (info {:event :auth-success :email (:email principal)})
                                 (>! out {:type auth-ok-msg-type :principal principal}))
                               (do
                                 (warn {:event :auth-failed})
                                 (>! out {:type auth-error-msg-type
                                          :error "invalid-token"
                                          :message "Token is invalid or expired"}))))

                ;; Refresh authentication
                           (= msg-type auth-refresh-msg-type)
                           (let [token (:token msg)
                                 principal #?(:clj (if dev-mode
                                                     (or dev-principal default-dev-principal)
                                                     (validate-token jwt token))
                                              :cljs (or dev-principal default-dev-principal))]
                             (if principal
                               (do
                                 (reset! principal-atom principal)
                                 (info {:event :auth-refresh-success :email (:email principal)})
                                 (>! out {:type auth-ok-msg-type}))
                               (do
                                 (warn {:event :auth-refresh-failed})
                                 (>! out {:type auth-error-msg-type
                                          :error "invalid-token"
                                          :message "Token is invalid or expired"}))))

                ;; Regular message - add principal if authenticated
                           :else
                           (let [current-principal @principal-atom]
                             (>! new-in (if current-principal
                                          (assoc msg :kabel/principal current-principal)
                                          msg)))))
                       (recur (<? S in))))

        ;; Pass through outgoing messages (strip :kabel/* keys for security)
        (go-loop-try S [msg (<? S new-out)]
                     (when msg
                       (let [clean-msg (into {} (remove (fn [[k _]]
                                                          (and (keyword? k)
                                                               (= "kabel" (namespace k))))
                                                        msg))]
                         (>! out clean-msg))
                       (recur (<? S new-out))))

        [S peer [new-in new-out]]))))

;; =============================================================================
;; Outbound Authentication (authenticate TO peer)
;; =============================================================================

(defn authenticate-middleware
  "Kabel middleware that authenticates TO a remote peer.

   Sends :kabel/auth message immediately when connection is established,
   waits for :kabel/auth-ok or :kabel/auth-error response, then proceeds.

   This proves MY identity to the remote peer.
   Use `validate-middleware` to verify the remote peer's identity.

   Uses lexical scope for in/out channels - no global state needed.

   Options:
     :token - Authentication token (required in production, default: \"dev-token\")
     :on-auth - Optional callback (fn [principal]) on successful auth
     :on-error - Optional callback (fn [error]) on auth failure

   Usage:
     (peer/client-peer S client-id
       (comp other-middleware
             (ws-auth/authenticate-middleware {:token \"my-jwt-token\"}))
       serialization-middleware)"
  [{:keys [token on-auth on-error] :or {token "dev-token"}}]
  (fn [[S peer [in out]]]
    (let [new-in (chan 1000)]
      ;; Send auth message immediately using lexical `out`
      (go-try S
              (>! out {:type auth-msg-type :token token})
              (debug {:event :auth-sent})

        ;; Wait for auth response
              (let [first-msg (<? S in)]
                (cond
                  (= (:type first-msg) auth-ok-msg-type)
                  (do
                    (info {:event :client-auth-success :email (get-in first-msg [:principal :email])})
                    (when on-auth (on-auth (:principal first-msg)))
              ;; Continue processing remaining messages
                    (loop []
                      (when-let [msg (<? S in)]
                        (>! new-in msg)
                        (recur))))

                  (= (:type first-msg) auth-error-msg-type)
                  (do
                    (warn {:event :client-auth-failed :error (:error first-msg)})
                    (when on-error (on-error first-msg))
              ;; Don't proceed - auth failed
                    (close! new-in))

                  :else
            ;; Not an auth response - server might not have auth middleware
            ;; Pass through and continue normally
                  (do
                    (debug {:event :auth-no-response :first-msg-type (:type first-msg)})
                    (>! new-in first-msg)
                    (loop []
                      (when-let [msg (<? S in)]
                        (>! new-in msg)
                        (recur)))))))

      [S peer [new-in out]])))

;; =============================================================================
;; Unified Bidirectional Middleware
;; =============================================================================

(defn auth-middleware
  "Unified bidirectional authentication middleware for kabel.

   Handles both directions of authentication independently, enabling
   true P2P authentication where both peers can prove their identity.

   SECURITY NOTE: If you only use :authenticate without :validate, you must
   explicitly set :permissive true to acknowledge that incoming messages
   will NOT be validated. This prevents accidental security holes.

   Options:
     :authenticate - Prove MY identity to the remote peer (send auth)
       :token - JWT or dev token to send
       :on-auth - Callback (fn [principal]) when remote accepts my auth
       :on-error - Callback (fn [error]) on auth failure

     :validate - Verify REMOTE peer's identity (receive and validate auth)
       :jwt - JWT validation config {:secret ... :alg ...}
       :dev-mode - Skip token validation (default false)
       :dev-principal - Principal for dev mode
       :on-auth - Callback (fn [principal]) on successful validation

     :permissive - When true, explicitly allow not validating incoming messages.
                   Required when using :authenticate without :validate.

   Examples:
     ;; Authenticate to remote, explicitly permissive for incoming
     (auth-middleware {:authenticate {:token \"my-token\"} :permissive true})

     ;; Validate remote peer (verify their identity, dev mode)
     (auth-middleware {:validate {:dev-mode true}})

     ;; Bidirectional P2P auth (both peers authenticate)
     (auth-middleware
       {:authenticate {:token \"my-token\"}
        :validate {:dev-mode true}})"
  [{:keys [authenticate validate permissive]}]
  (cond
    ;; Both directions - compose the middlewares
    (and authenticate validate)
    (comp (validate-middleware validate)
          (authenticate-middleware authenticate))

    ;; Outbound only - must explicitly acknowledge permissive inbound
    (and authenticate (not validate))
    (if permissive
      (authenticate-middleware authenticate)
      (throw (ex-info "Security: :authenticate without :validate requires :permissive true"
                      {:type :security-configuration-error
                       :hint "Add :permissive true to explicitly allow unauthenticated incoming messages"})))

    ;; Inbound only - verify remote's identity
    validate
    (validate-middleware validate)

    ;; Neither - pass through (no auth)
    :else
    identity))

;; =============================================================================
;; Helper Functions
;; =============================================================================

(defn with-principal
  "Execute body with *principal* bound to the given principal.
   For use in distributed-scope remote function invocation."
  [principal f]
  (binding [*principal* principal]
    (f)))

(defn current-principal
  "Get the current principal from dynamic binding.
   Returns nil if not authenticated."
  []
  *principal*)

(defn require-principal
  "Get the current principal or throw if not authenticated."
  []
  (or *principal*
      (throw (ex-info "Authentication required" {:type :authentication-required}))))
