(ns kabel.transport
  "Connection-local state and the transport middleware boundary.

  Kabel middleware has always accepted and returned
  `[supervisor peer [in out]]`. That shape remains unchanged. A connection
  context is carried as metadata on the channel pair and `apply-middleware`
  restores that metadata after old middleware returns a fresh pair.

  Transport middleware is the first middleware above a socket and therefore
  sits outside serialization. It sees Kabel's encoded transport envelopes; an
  authenticated transport can consume handshake envelopes, protect encoded
  frames, and publish the authenticated remote identity into the shared
  context before serialization or application middleware observes data."
  (:refer-clojure :exclude [update]))

(def ^:private context-key ::context)

(defn new-context
  "Create the mutable state for one physical connection.

  `role` is `:initiator` or `:responder`. `attributes` may add transport facts
  such as `::expected-target` or `::remote-address`. A security middleware may
  later associate `::authenticated-authority` and
  `::negotiated-capabilities`.
  Context ids may be supplied for deterministic tests; normal connections get
  a fresh UUID."
  ([role]
   (new-context role {}))
  ([role attributes]
   {:pre [(#{:initiator :responder} role) (map? attributes)]}
   (atom (merge {::id (random-uuid)
                 ::role role
                 ::initiator? (= role :initiator)
                 ::transport :websocket
                 ::dial-address nil
                 ::expected-target nil
                 ::authenticated-authority nil
                 ::negotiated-capabilities #{}}
                attributes
                ;; Role is a property of the socket endpoint, not peer input.
                {::role role
                 ::initiator? (= role :initiator)}))))

(defn with-context
  "Attach `context`, an atom returned by `new-context`, to a middleware value."
  [[S peer channels] context]
  [S peer (with-meta channels (assoc (meta channels) context-key context))])

(defn connection-context
  "Return the connection context atom attached to a middleware value or its
  `[in out]` channel pair. Returns nil for a legacy value outside a peer
  connection pipeline."
  [connection-or-channels]
  (let [channels (if (and (vector? connection-or-channels)
                          (= 3 (count connection-or-channels)))
                   (nth connection-or-channels 2)
                   connection-or-channels)]
    (-> channels meta context-key)))

(defn apply-middleware
  "Apply middleware while preserving a connection context across legacy
  middleware that returns an unannotated channel pair."
  [middleware connection]
  (let [context (connection-context connection)
        result ((or middleware identity) connection)]
    (if context
      (with-context result context)
      result)))

(defn update!
  "Merge authenticated or negotiated facts into this connection's context.
  Accepts either the context atom itself or an annotated middleware value."
  [context-or-connection attributes]
  (let [context (if #?(:clj (instance? clojure.lang.IAtom context-or-connection)
                       :cljs (satisfies? IAtom context-or-connection))
                  context-or-connection
                  (connection-context context-or-connection))]
    (when-not context
      (throw (ex-info "Kabel connection has no context"
                      {:type :kabel.transport/missing-context})))
    (swap! context merge attributes)))

(defn register!
  "Expose an active context on `peer`, keyed by its connection id."
  [peer context]
  (swap! peer assoc-in [:volatile ::connections (::id @context)] context)
  context)

(defn unregister!
  "Remove an active context from `peer`."
  [peer context]
  (swap! peer update-in [:volatile ::connections] dissoc (::id @context))
  nil)

(defn connections
  "Snapshot of active connection-id -> context-atom for `peer`."
  [peer]
  (get-in @peer [:volatile ::connections] {}))
