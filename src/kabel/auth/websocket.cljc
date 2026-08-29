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
            [clojure.core.async :as async :refer [chan promise-chan <! >! close! put! alts! timeout go go-loop]]
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

(def default-auth-timeout-ms 10000)
(def default-auth-pending-limit 1000)

(defn- invoke-callback!
  "Invoke a lifecycle callback without letting consumer code strand protocol
   state or channel cleanup. State transitions always happen before this call."
  [event callback value]
  (when callback
    (try
      (callback value)
      (catch #?(:clj Throwable :cljs :default) e
        (warn {:event event :error (ex-message e)})))))

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
                     (if msg
                       (do
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
                                   (invoke-callback! :validate-auth-callback-failed on-auth principal)
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
                         (recur (<? S in)))
                       (do
                         (close! new-in)
                         (close! new-out)
                         (close! out))))

        ;; Pass through outgoing messages (strip :kabel/* keys for security)
        (go-loop-try S [msg (<? S new-out)]
                     (if msg
                       (do
                         (let [clean-msg (into {} (remove (fn [[k _]]
                                                            (and (keyword? k)
                                                                 (= "kabel" (namespace k))))
                                                          msg))]
                           (>! out clean-msg))
                         (recur (<? S new-out)))
                       (close! out)))

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
     :timeout-ms - Maximum handshake duration (default: 10000)
     :pending-limit - Maximum buffered frames per direction (default: 1000)

   Usage:
     (peer/client-peer S client-id
       (comp other-middleware
             (ws-auth/authenticate-middleware {:token \"my-jwt-token\"}))
       serialization-middleware)"
  [{:keys [token on-auth on-error timeout-ms pending-limit] :as opts
    :or {token "dev-token"
         timeout-ms default-auth-timeout-ms
         pending-limit default-auth-pending-limit}}]
  (fn [[S peer [in out]]]
    (let [new-in (chan 1000)
          new-out (chan 1000)
          auth-ready (promise-chan)
          auth-deadline (timeout timeout-ms)
          bidirectional? (::bidirectional? opts)
          settlement (atom nil)
          fail! (fn [error]
                  (let [won? (compare-and-set! settlement nil :rejected)]
                    (when won?
                      (warn {:event :client-auth-failed :error (:error error)})
                      (put! auth-ready :rejected)
                      (close! new-in)
                      (close! new-out)
                      (close! in)
                      (close! out)
                      (invoke-callback! :client-auth-error-callback-failed on-error error))
                    won?))]
      ;; Send auth immediately, but make the write itself part of the bounded
      ;; handshake. A transport whose output is never consumed must not park us
      ;; before we can observe the deadline.
      (go-try S
              (let [[written? port]
                    (alts! [auth-deadline
                            [out {:type auth-msg-type :token token}]]
                           :priority true)]
                (cond
                  (= port auth-deadline)
                  (fail! {:type auth-error-msg-type
                          :error "auth-timeout"
                          :timeout-ms timeout-ms})

                  (false? written?)
                  (fail! {:type auth-error-msg-type
                          :error "transport-closed-during-auth"})

                  :else
                  (do
                    (debug {:event :auth-sent})

        ;; Other server middleware may emit initialization messages before the
        ;; auth middleware's reply. Hold those messages until the explicit auth
        ;; result arrives, then replay them in their original order. Treating a
        ;; non-auth first frame as evidence that the peer lacks auth lets an
        ;; ordinary middleware race silently downgrade the connection.
                    (loop [pending []]
                      (let [[msg port] (alts! [auth-deadline in] :priority true)]
                        (cond
                          (= port auth-deadline)
                          (fail! {:type auth-error-msg-type
                                  :error "auth-timeout"
                                  :timeout-ms timeout-ms})

                          (nil? msg)
                          (fail! {:type auth-error-msg-type
                                  :error "transport-closed-during-auth"})

                          (= (:type msg) auth-ok-msg-type)
                          (if (map? (:principal msg))
                            (when (compare-and-set! settlement nil :authenticated)
                              (info {:event :client-auth-success :email (get-in msg [:principal :email])})
                              (>! auth-ready :authenticated)
                              (invoke-callback! :client-auth-callback-failed on-auth (:principal msg))
                              (doseq [pending-msg pending]
                                (>! new-in pending-msg))
                              (loop []
                                (if-let [next-msg (<? S in)]
                                  (do
                                    (>! new-in next-msg)
                                    (recur))
                                  (do
                                    (close! new-in)
                                    (close! new-out)
                                    (close! out)))))
                            (fail! {:type auth-error-msg-type
                                    :error "auth-invalid-response"
                                    :message "Authentication response has no principal"}))

                          (= (:type msg) auth-error-msg-type)
                          (fail! msg)

                    ;; In bidirectional mode the outer validation middleware
                    ;; must see the peer's handshake while our own handshake is
                    ;; still pending. These are protocol frames, not application
                    ;; traffic, and forwarding them is what lets both sides
                    ;; authenticate concurrently.
                          (and bidirectional?
                               (contains? #{auth-msg-type auth-refresh-msg-type} (:type msg)))
                          (do
                            (>! new-in msg)
                            (recur pending))

                          (>= (count pending) pending-limit)
                          (fail! {:type auth-error-msg-type
                                  :error "auth-pending-overflow"
                                  :direction :inbound
                                  :pending-limit pending-limit})

                          :else
                          (recur (conj pending msg)))))))))

      ;; Application traffic is gated by the same handshake. Bidirectional
      ;; auth responses are the sole exception: the outer validator writes
      ;; them through `new-out`, and delaying those behind our own acceptance
      ;; would make two symmetric peers wait on each other forever.
      (go-try S
              (loop [pending []]
                (let [[value port] (alts! [auth-ready new-out])]
                  (cond
                    (= port auth-ready)
                    (when (= :authenticated value)
                      (doseq [pending-msg pending]
                        (>! out pending-msg))
                      (loop []
                        (if-let [msg (<? S new-out)]
                          (do
                            (>! out msg)
                            (recur))
                          (close! out))))

                    (nil? value)
                    (close! out)

                    (and bidirectional?
                         (contains? #{auth-ok-msg-type auth-error-msg-type} (:type value)))
                    (do
                      (>! out value)
                      (recur pending))

                    (>= (count pending) pending-limit)
                    (when-not
                     (fail! {:type auth-error-msg-type
                             :error "auth-pending-overflow"
                             :direction :outbound
                             :pending-limit pending-limit})
                      ;; Authentication may have won settlement concurrently
                      ;; just before publishing auth-ready. Preserve the frame
                      ;; we already took and enter the authenticated forwarding
                      ;; path instead of silently terminating this loop.
                      (when (= :authenticated @settlement)
                        (doseq [pending-msg pending]
                          (>! out pending-msg))
                        (>! out value)
                        (loop []
                          (if-let [msg (<? S new-out)]
                            (do
                              (>! out msg)
                              (recur))
                            (close! out)))))

                    :else
                    (recur (conj pending value))))))

      [S peer [new-in new-out]])))

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
          (authenticate-middleware (assoc authenticate ::bidirectional? true)))

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
