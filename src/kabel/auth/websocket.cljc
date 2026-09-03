(ns kabel.auth.websocket
  "Bidirectional authentication protocol for kabel.

   NOTE: Despite the 'websocket' namespace name, this is transport-agnostic.
   It works with any kabel transport (WebSocket, in-memory, etc.).

   Provides a single middleware that handles both client and server auth:
   - Client role: send :kabel/auth, wait for response
   - Server role: receive :kabel/auth, validate, respond

   Both roles can be enabled independently for true P2P auth.

   Tokens expire. The client side reads its token from a function or an atom
   at every connection, so a reconnection carries a fresh one, refreshes it on
   the live connection before a JWT's `exp` (`refresh-token!`), and the
   server side ends, or downgrades, a connection whose token expired without
   a refresh.

   Usage:
     (require '[kabel.auth.websocket :as auth])

     ;; Server-only (validates incoming, doesn't auth outgoing)
     (auth/auth-middleware {:validate {:jwt {...}}})

     ;; Client-only (authenticates to remote, permits all incoming)
     (auth/auth-middleware {:authenticate {:token (fn [] (current-token))}
                            :permissive true})

     ;; Bidirectional (both sides authenticate)
     (auth/auth-middleware
       {:authenticate {:token \"my-token\"}
        :validate {:jwt {...}}})"
  (:require #?(:clj [kabel.auth.jwt :as jwt]
               :cljs [kabel.auth.jwt :as jwt])
            [kabel.peer :as peer]
            [clojure.core.async :as async :refer [chan promise-chan <! >! close! put! alts! timeout go go-loop]]
            [clojure.core.async.impl.protocols :as async-proto]
            [replikativ.logging :refer [warn info debug]]
            [superv.async :refer [go-try go-loop-try <? put? S]])
  #?(:cljs (:require-macros [clojure.core.async :refer [go go-loop]]
                            [superv.async :refer [go-try go-loop-try <? put? S]])))

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
(def default-refresh-before-ms 60000)
(def default-leeway-seconds 60)

(defn- now-ms []
  #?(:clj (System/currentTimeMillis)
     :cljs (.now js/Date)))

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

;; =============================================================================
;; Inbound validation (verify the REMOTE peer)
;; =============================================================================

(defn validate-middleware
  "Kabel middleware that validates auth FROM a remote peer.

   Handles :kabel/auth and :kabel/auth-refresh messages.
   Adds :kabel/principal to all authenticated messages.

   This verifies the REMOTE peer's identity.
   Use `authenticate-middleware` to prove MY identity to the remote.

   Options:
     :jwt - JWT configuration for token validation
            {:secret \"...\" :alg :HS256} or {:public-key \"...\" :alg :RS256};
            its :leeway-seconds (default 60) also pads token expiry below
     :dev-mode - When true, skip token validation
     :dev-principal - Principal to use in dev mode
     :on-auth - Optional callback (fn [principal]) called on successful auth
     :on-refresh - Optional callback (fn [principal]) called on a successful refresh
     :on-expiry - What happens when the accepted token's `exp` passes without
                  a refresh (default :close):
                    :close     send :kabel/auth-error \"token-expired\" and close
                               the connection
                    :anonymous send the same error and keep the connection,
                               without a principal from then on
                    :ignore    the pre-0.3.130 behaviour, expiry is not watched

   Messages:
     Remote -> {:type :kabel/auth :token \"access_token\"}
     Me     -> {:type :kabel/auth-ok :principal {...}}
            or {:type :kabel/auth-error :error \"message\"}

     Remote -> {:type :kabel/auth-refresh :token \"new_access_token\"}
     Me     -> {:type :kabel/auth-ok :principal {...}}
            or {:type :kabel/auth-error :error \"message\"}

     Me     -> {:type :kabel/auth-error :error \"token-expired\"} when the
               token expired without a refresh"
  [{:keys [jwt dev-mode dev-principal on-auth on-refresh on-expiry]
    :or {on-expiry :close}}]
  (let [default-dev-principal {:sub "dev-user"
                               :email "dev@localhost"
                               :name "Developer"}
        leeway-ms (* 1000 (get jwt :leeway-seconds default-leeway-seconds))]
    (fn [[S peer [in out]]]
      (let [new-in (chan)
            new-out (chan)
            ;; Per-connection principal state
            principal-atom (atom nil)
            ;; The watch on the accepted token's expiry; closed when replaced
            expiry-cancel (atom nil)
            validate (fn [token]
                       #?(:clj (if dev-mode
                                 (or dev-principal default-dev-principal)
                                 (validate-token jwt token))
                          :cljs (or dev-principal default-dev-principal)))
            expired! (fn []
                       (warn {:event :token-expired :on-expiry on-expiry})
                       (put! out {:type auth-error-msg-type
                                  :error "token-expired"
                                  :message "Token expired without a refresh"})
                       (case on-expiry
                         :close (close! out)
                         :anonymous (reset! principal-atom nil)
                         nil))
            watch-expiry! (fn [principal]
                            (when-let [old @expiry-cancel]
                              (close! old))
                            (when-let [exp (and (not= on-expiry :ignore) (:exp principal))]
                              (let [cancel (promise-chan)
                                    delay (max 0 (- (+ (* 1000 exp) leeway-ms) (now-ms)))]
                                (reset! expiry-cancel cancel)
                                (go (let [[_ port] (alts! [cancel (timeout delay)])]
                                      (when (not= port cancel)
                                        (expired!)))))))]

        ;; Process incoming messages
        (go-loop-try S [msg (<? S in)]
                     (if msg
                       (do
                         (let [msg-type (:type msg)]
                           (cond
                ;; Initial authentication
                             (= msg-type auth-msg-type)
                             (let [principal (validate (:token msg))]
                               (if principal
                                 (do
                                   (reset! principal-atom principal)
                                   (watch-expiry! principal)
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
                             (let [principal (validate (:token msg))]
                               (if principal
                                 (do
                                   (reset! principal-atom principal)
                                   (watch-expiry! principal)
                                   (invoke-callback! :validate-refresh-callback-failed on-refresh principal)
                                   (info {:event :auth-refresh-success :email (:email principal)})
                                   (>! out {:type auth-ok-msg-type :principal principal}))
                                 (do
                                   (warn {:event :auth-refresh-failed})
                                   (>! out {:type auth-error-msg-type
                                            :error "invalid-token"
                                            :message "Token is invalid or expired"}))))

                ;; Regular message - the principal is ours to say, never the
                ;; remote's: stamp it when authenticated, strip it otherwise.
                             :else
                             (let [current-principal @principal-atom]
                               (>! new-in (if current-principal
                                            (assoc msg :kabel/principal current-principal)
                                            (dissoc msg :kabel/principal))))))
                         (recur (<? S in)))
                       (do
                         (when-let [cancel @expiry-cancel]
                           (close! cancel))
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

#?(:cljs
   (defn- promise->chan [p]
     (let [ch (promise-chan)]
       (.then p
              (fn [v] (put! ch (if (nil? v) ::nil v)))
              (fn [e] (put! ch (if (instance? js/Error e) e (ex-info (str e) {:error e})))))
       ch)))

(defn resolve-token
  "The token to send now. `token` is a string, an atom (or anything
   dereferenceable), or a function of no arguments returning a string, a
   channel yielding one, or in ClojureScript a promise of one. Yields the
   string, or an exception."
  [S token]
  (go-try S
          (let [t (cond
                    (string? token) token
                    (nil? token) nil
                    (fn? token) (token)
                    :else @token)
                t #?(:cljs (if (instance? js/Promise t) (promise->chan t) t)
                     :clj t)
                t (if (satisfies? async-proto/ReadPort t) (<? S t) t)]
            (if (= t ::nil) nil t))))

(defn- refresh-in-ms
  "How long until `token` should be refreshed, or nil when it carries no `exp`."
  [token before-ms]
  (when-let [exp (:exp (jwt/claims token))]
    (max 1000 (- (* 1000 exp) before-ms (now-ms)))))

(defn refresh-token!
  "Send a new token on `peer`'s current connection and yield the server's
   answer: the principal it accepted, or an exception. Without `token` the
   configured token source is read again.

   The server's validator replaces the connection's principal; nothing else
   about the connection changes."
  ([peer] (refresh-token! peer nil))
  ([peer token]
   (let [{:keys [out source timeout-ms pending S]} (::client @peer)]
     (go-try S
             (when-not out
               (throw (ex-info "Peer has no authenticated connection to refresh"
                               {:type :kabel.auth/not-connected})))
             (let [t (<? S (resolve-token S (or token source)))
                   reply (promise-chan)]
               (reset! pending reply)
               (put? S out {:type auth-refresh-msg-type :token t})
               (let [[v port] (alts! [reply (timeout timeout-ms)])]
                 (cond
                   (not= port reply)
                   (throw (ex-info "Token refresh timed out"
                                   {:type :kabel.auth/refresh-timeout :timeout-ms timeout-ms}))
                   (= auth-error-msg-type (:type v))
                   (throw (ex-info (or (:message v) "Token refresh rejected")
                                   (assoc v :type :kabel.auth/refresh-rejected)))
                   :else (:principal v))))))))

(defn authenticate-middleware
  "Kabel middleware that authenticates TO a remote peer.

   Sends :kabel/auth message immediately when connection is established,
   waits for :kabel/auth-ok or :kabel/auth-error response, then proceeds.

   This proves MY identity to the remote peer.
   Use `validate-middleware` to verify the remote peer's identity.

   Uses lexical scope for in/out channels - no global state needed.

   Options:
     :token - The token to send: a string, an atom, or a function returning a
              string, a channel or (ClojureScript) a promise. A function or
              atom is read at every connection, so a reconnection carries the
              current token. Required in production, default: \"dev-token\".
     :on-auth - Optional callback (fn [principal]) on successful auth or refresh
     :on-error - Optional callback (fn [error]) on auth failure, a rejected
                 refresh, or the server reporting the token expired
     :timeout-ms - Maximum handshake duration (default: 10000)
     :pending-limit - Maximum buffered frames per direction (default: 1000)
     :auto-refresh? - When the token is a JWT with `exp` and comes from a
                      function or atom, read it again and refresh on the live
                      connection `:refresh-before-ms` (default 60000) before
                      expiry. Default true.

   Usage:
     (peer/client-peer S client-id
       (comp other-middleware
             (ws-auth/authenticate-middleware {:token (fn [] (session-token))}))
       serialization-middleware)"
  [{:keys [token on-auth on-error timeout-ms pending-limit auto-refresh? refresh-before-ms] :as opts
    :or {token "dev-token"
         timeout-ms default-auth-timeout-ms
         pending-limit default-auth-pending-limit
         auto-refresh? true
         refresh-before-ms default-refresh-before-ms}}]
  (fn [[S peer [in out]]]
    (let [new-in (chan 1000)
          new-out (chan 1000)
          auth-ready (promise-chan)
          auth-deadline (timeout timeout-ms)
          bidirectional? (::bidirectional? opts)
          settlement (atom nil)
          pending-refresh (atom nil)
          closed (promise-chan)
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
                    won?))
          answer-refresh! (fn [msg]
                            (if-let [reply @pending-refresh]
                              (do (reset! pending-refresh nil)
                                  (put! reply msg))
                              (when (= auth-error-msg-type (:type msg))
                                (warn {:event :client-auth-error :error (:error msg)})))
                            (when (= auth-error-msg-type (:type msg))
                              (invoke-callback! :client-auth-error-callback-failed on-error msg))
                            (when (and (= auth-ok-msg-type (:type msg)) (:principal msg))
                              (invoke-callback! :client-auth-callback-failed on-auth (:principal msg))))
          auto-refresh! (fn auto-refresh! [current]
                          (when-let [wait (and auto-refresh?
                                               (not (string? token))
                                               (refresh-in-ms current refresh-before-ms))]
                            (go (let [[_ port] (alts! [closed (timeout wait)])]
                                  (when (not= port closed)
                                    (let [next (try (<? S (resolve-token S token))
                                                    (catch #?(:clj Throwable :cljs :default) e e))]
                                      (if (string? next)
                                        (let [r (try (<? S (refresh-token! peer next))
                                                     (catch #?(:clj Throwable :cljs :default) e e))]
                                          (if (instance? #?(:clj Throwable :cljs js/Error) r)
                                            (warn {:event :auto-refresh-failed :error (ex-message r)})
                                            (auto-refresh! next)))
                                        (warn {:event :auto-refresh-no-token
                                               :error (some-> next ex-message)}))))))))]
      (when peer
        (swap! peer assoc ::client {:out out :source token :timeout-ms timeout-ms
                                    :pending pending-refresh :S S}))
      ;; Send auth immediately, but make the write itself part of the bounded
      ;; handshake. A transport whose output is never consumed must not park us
      ;; before we can observe the deadline.
      (go-try S
              (let [current (try (<? S (resolve-token S token))
                                 (catch #?(:clj Throwable :cljs :default) e e))
                    [written? port]
                    (if (string? current)
                      (alts! [auth-deadline
                              [out {:type auth-msg-type :token current}]]
                             :priority true)
                      [::no-token nil])]
                (cond
                  (= written? ::no-token)
                  (fail! {:type auth-error-msg-type
                          :error "no-token"
                          :message (or (some-> current ex-message) "Token source returned no token")})

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
                              (when peer
                                (peer/status! peer :authenticated :principal (:principal msg)))
                              (auto-refresh! current)
                              (doseq [pending-msg pending]
                                (>! new-in pending-msg))
                              (loop []
                                (if-let [next-msg (<? S in)]
                                  (do
                                    ;; Auth answers after the handshake are
                                    ;; replies to a refresh, or the server
                                    ;; reporting expiry: protocol, not traffic.
                                    (if (contains? #{auth-ok-msg-type auth-error-msg-type} (:type next-msg))
                                      (answer-refresh! next-msg)
                                      (>! new-in next-msg))
                                    (recur))
                                  (do
                                    (close! closed)
                                    (when peer
                                      (swap! peer update ::client #(when (not= out (:out %)) %)))
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
       :token - JWT or dev token to send: a string, an atom or a function,
                see `authenticate-middleware`
       :on-auth - Callback (fn [principal]) when remote accepts my auth
       :on-error - Callback (fn [error]) on auth failure

     :validate - Verify REMOTE peer's identity (receive and validate auth)
       :jwt - JWT validation config {:secret ... :alg ...}
       :dev-mode - Skip token validation (default false)
       :dev-principal - Principal for dev mode
       :on-auth - Callback (fn [principal]) on successful validation
       :on-expiry - :close (default), :anonymous or :ignore, see `validate-middleware`

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
