(ns kabel.remote
  "Remote function invocation over a kabel connection.

   A peer registers functions by name. A connected peer invokes one by name
   with an argument map and receives the result, or the error, back on the
   connection the request travelled on. This is the runtime that
   `is.simm.distributed-scope` built its `defn-go-remote` macros on; it moved
   here so the wire protocol has one home and every transport concern
   (reconnection, authentication, authorization) is handled in one place.

   Three pieces:

   - `middleware` on a connection: announces this peer's id to the other side,
     records where the other side can be reached, routes inbound invokes to
     `serve` and inbound results to the waiting `invoke`.
   - `serve` on a peer: runs registered functions for inbound invokes, after
     the authorization gate.
   - `invoke` from a peer: sends one request and waits for its result.

   The frames are documented in doc/remote-invocation.md. Frames of the
   distributed-scope dialect (`:is.simm.distributed-scope/*` types) are
   accepted, and a connection that speaks it is answered in it, so peers can
   be upgraded one at a time.

   Usage:
   ```clojure
   ;; both sides compose the middleware
   (peer/server-peer S handler server-id (comp app remote/middleware) cbor)
   (peer/client-peer S client-id (comp app remote/middleware) cbor)

   ;; the serving side
   (remote/register! 'my.app/add (fn [{:keys [a b]}] (+ a b)))
   (remote/serve server {:authorize (fn [{:keys [principal fn-name]}] ...)})

   ;; the calling side
   (<? S (remote/connect S client url))
   (<? S (remote/invoke client server-id 'my.app/add {:a 1 :b 2})) ;; => 3
   ```"
  (:require [kabel.authorize :as authz]
            [kabel.peer :as peer]
            [hasch.core :refer [uuid]]
            [replikativ.logging :as log]
            #?(:clj [superv.async :as superv :refer [<? >? put? go-try go-loop-super go-super]]
               :cljs [superv.async :as superv :refer [put?]])
            #?(:clj [clojure.core.async :as async
                     :refer [chan promise-chan put! close! sub unsub timeout alts!]]
               :cljs [clojure.core.async :as async
                      :refer [chan promise-chan put! close! sub unsub timeout alts!]
                      :include-macros true])
            [clojure.core.async.impl.protocols :as async-proto])
  #?(:cljs (:require-macros [superv.async :refer [<? >? go-try go-loop-super go-super]])))

;; =============================================================================
;; Wire dialects
;; =============================================================================

(def ^:private kabel-types
  {:register :kabel.remote/register
   :invoke   :kabel.remote/invoke
   :result   :kabel.remote/result})

(def ^:private legacy-types
  {:register :is.simm.distributed-scope/register-scope
   :invoke   :is.simm.distributed-scope/invoke
   :result   :is.simm.distributed-scope/invoke-result})

(def ^:private dialects {:kabel kabel-types :legacy legacy-types})

(def ^:private type->kind
  (into {} (for [[dialect types] dialects
                 [kind type] types]
             [type [kind dialect]])))

(defn- wire-type [dialect kind]
  (get-in dialects [dialect kind]))

;; =============================================================================
;; Registry and per-peer state
;; =============================================================================

(defonce ^{:doc "Registered functions: `{fn-name (fn [arg-map])}`. Process-wide, as
                 functions are registered at load time before any peer exists."}
  functions
  (atom {}))

(defn register!
  "Register `f` under `fn-name`, a namespaced symbol or a string. `f` receives
   the argument map, with the caller's principal under `:kabel/principal` when
   the connection is authenticated, and returns a value or a channel yielding
   one. An exception, thrown or yielded, is reported to the caller as an error.

   `f` runs inside a go block and must not block: a synchronous database
   call, a socket read or a `<!!` there holds one of the dispatch pool's few
   threads, and a handful of them in flight deadlock the process. Work that
   has to block goes on `clojure.core.async/thread`, whose channel `f`
   returns."
  [fn-name f]
  (swap! functions assoc fn-name f)
  fn-name)

(defn unregister! [fn-name]
  (swap! functions dissoc fn-name)
  nil)

(defonce ^{:doc "Which local peer reaches a remote peer id: `{remote-id peer}`.
                 Lets `invoke` be called with a remote id alone."}
  routes
  (atom {}))

(defonce ^:private route-waiters (atom {}))

(defn- state [peer] (::state @peer))

(defn- update-state! [peer f & args]
  (apply swap! peer update ::state f args))

(defn- channel? [x]
  (satisfies? async-proto/ReadPort x))

(defn- throwable? [x]
  (instance? #?(:clj Throwable :cljs js/Error) x))

(defn- error-info
  "What travels for a failed invocation. `ex-data` is not sent as data:
   it may hold anything, and a value the codec cannot encode would take the
   connection down with it."
  [e fn-name]
  (let [data (ex-data e)]
    (cond-> {:message (or (ex-message e) (str e))
             :fn-name fn-name}
      (:type data) (assoc :type (:type data))
      data (assoc :data (pr-str (dissoc data :type))))))

(defn- error->exception [error fn-name]
  (if (map? error)
    (ex-info (or (:message error) "Remote invocation failed")
             (assoc error :type (or (:type error) ::remote-error) :fn-name fn-name))
    (ex-info "Remote invocation failed" {:type ::remote-error :fn-name fn-name :error error})))

;; =============================================================================
;; Connection middleware
;; =============================================================================

(defn- connected!
  "Record that `remote` is reachable through `peer` over `out`."
  [peer remote out dialect]
  (update-state! peer assoc-in [:connections remote] {:out out :dialect dialect})
  (swap! routes assoc remote peer)
  (doseq [waiters [(get-in (state peer) [:waiters remote]) (get @route-waiters remote)]
          w waiters]
    (put! w :ready))
  (update-state! peer update :waiters dissoc remote)
  (swap! route-waiters dissoc remote))

(defn- disconnected!
  "Forget `remote` and fail every request still waiting on this connection."
  [peer remote out]
  (let [pending (filter (fn [[_ p]] (= out (:out p))) (:pending (state peer)))]
    (doseq [[rid {:keys [ch fn-name]}] pending]
      (put! ch (error->exception {:type ::disconnected
                                  :message "Connection closed before the result arrived"}
                                 fn-name))
      (update-state! peer update :pending dissoc rid)))
  (when (= out (get-in (state peer) [:connections remote :out]))
    (update-state! peer update :connections dissoc remote)
    (when (= peer (get @routes remote))
      (swap! routes dissoc remote))))

(defn connected?
  "Is `remote` reachable through `peer` right now?"
  [peer remote]
  (some? (get-in (state peer) [:connections remote])))

(defn- deliver-result! [peer {:keys [request-id result error]}]
  (if-let [{:keys [ch fn-name]} (get-in (state peer) [:pending request-id])]
    (do (update-state! peer update :pending dissoc request-id)
        (put! ch (if error (error->exception error fn-name) (if (nil? result) ::nil result))))
    (log/debug :kabel.remote/unexpected-result {:request-id request-id})))

(defn- connection-middleware
  [{:keys [dialect] :or {dialect :kabel}}]
  (fn [[S peer [in out]]]
    (let [new-in (chan 1000)
          peer-id (:id @peer)
          [bus-in _] (get-in @peer [:volatile :chans])
          dialect (atom dialect)
          remote (atom nil)]
      (go-loop-super S [msg (<? S in)]
                     (if msg
                       (do
                         (if-let [[kind msg-dialect] (get type->kind (:type msg))]
                           (case kind
                             :register
                             (let [id (:scope msg)]
                               (when (and (= msg-dialect :legacy) (not= @dialect :legacy))
                                  ;; An old peer never saw our announcement as one.
                                 (reset! dialect :legacy)
                                 (put? S out {:type (wire-type :legacy :register) :scope peer-id}))
                               (reset! remote id)
                               (connected! peer id out @dialect)
                               (log/debug :kabel.remote/registered {:peer peer-id :remote id})
                               (put? S bus-in {:type ::ready :remote id}))

                             :invoke
                             (if (:serving? (state peer))
                               (put? S bus-in (assoc msg :type ::invoke ::reply-out out ::dialect @dialect))
                               (put? S out (cond-> {:type (wire-type @dialect :result)
                                                    :scope (:request-scope msg)
                                                    :request-id (:request-id msg)}
                                             (= @dialect :legacy)
                                             (assoc :error "Peer is not serving remote functions")
                                             (= @dialect :kabel)
                                             (assoc :error {:type ::not-serving
                                                            :message "Peer is not serving remote functions"
                                                            :fn-name (:fn-name msg)}))))

                             :result
                             (deliver-result! peer msg))
                           (>? S new-in msg))
                         (recur (<? S in)))
                       (do
                         (when-let [id @remote]
                           (disconnected! peer id out)
                           (put? S bus-in {:type ::closed :remote id}))
                         (close! new-in))))
      (put? S out {:type (wire-type @dialect :register) :scope peer-id})
      [S peer [new-in out]])))

(defn middleware
  "Kabel middleware carrying remote invocations on one connection. Compose it
   on both peers, inside the codec. Unrelated messages pass through.

   Called with a connection it is the middleware; called with an options map
   it returns one. With `{:dialect :legacy}` the peer announces itself in the
   distributed-scope dialect, for talking to peers that predate this
   namespace. Otherwise the dialect is detected from the other side's
   announcement."
  [conn-or-opts]
  (if (vector? conn-or-opts)
    ((connection-middleware {}) conn-or-opts)
    (connection-middleware conn-or-opts)))

;; =============================================================================
;; Serving
;; =============================================================================

(defn- legacy-gate [f]
  (fn [{:keys [principal fn-name arg-map]}] (f principal fn-name arg-map)))

(defn- lookup
  "The function registered under `fn-name`, spelled as a symbol or a string."
  [fn-name]
  (or (get @functions fn-name)
      (cond (symbol? fn-name) (get @functions (str fn-name))
            (string? fn-name) (get @functions (symbol fn-name)))))

(defn- call
  "Run the registered function, returning a channel with its value or an
   exception."
  [S fn-name arg-map]
  (go-try S
          (if-let [f (lookup fn-name)]
            (let [v (f arg-map)]
              (if (channel? v) (<? S v) v))
            (throw (ex-info "Remote function not found"
                            {:type ::unknown-function :fn-name fn-name})))))

(defn serve
  "Run registered functions for invokes arriving at `peer`.

   `opts`:
     :authorize    (fn [{:keys [op principal fn-name arg-map remote]}] -> truthy),
                   consulted before every inbound invocation. `:op` is
                   `:invoke`. Default permits everything, so kabel stays a
                   plain transport and the application supplies policy.
     :authorize-fn the positional `(fn [principal fn-name arg-map])`
                   distributed-scope accepted; `:authorize` wins when both
                   are given.

   A denied call without a principal fails with `:kabel.remote/authentication-required`,
   one with a principal with `:kabel.remote/not-authorized`. The handler never runs.

   Serving ends with `:stop!` or when the peer's supervisor aborts; neither is
   an error. Returns `{:stop! (fn []) :done ch}`, `:done` closing once serving
   has ended."
  ([peer] (serve peer {}))
  ([peer opts]
   (let [S (get-in @peer [:volatile :supervisor])
         gate (authz/gate opts {:op :invoke
                                :legacy-keys [:authorize-fn]
                                :legacy-adapter legacy-gate})
         [_ bus-out] (get-in @peer [:volatile :chans])
         invoke-ch (chan 1000)
         done (promise-chan)
         stop! (fn []
                 (update-state! peer assoc :serving? false)
                 (unsub bus-out ::invoke invoke-ch)
                 (close! invoke-ch)
                 (close! done))]
     (update-state! peer assoc :serving? true)
     (sub bus-out ::invoke invoke-ch)
     ;; Not a supervised loop: an abort is the signal to stop, not a failure to
     ;; report. Each invocation is supervised on its own below.
     (async/go (async/<! (superv/-abort S)) (stop!))
     (async/go-loop [{:keys [fn-name arg-map request-id request-scope scope] :as msg} (async/<! invoke-ch)]
       (when msg
         (let [principal (:kabel/principal msg)
               reply-out (::reply-out msg)
               dialect (or (::dialect msg) :kabel)]
           (let [args (cond-> arg-map principal (assoc :kabel/principal principal))
                 respond (fn [res]
                           (when (throwable? res)
                             (log/debug :kabel.remote/invocation-failed
                                        {:fn-name fn-name :type (:type (ex-data res))}))
                           (cond-> {:type (wire-type dialect :result)
                                    :scope request-scope
                                    :request-id request-id}
                             (and (throwable? res) (= dialect :legacy))
                             (assoc :error (pr-str res))
                             (and (throwable? res) (= dialect :kabel))
                             (assoc :error (error-info res fn-name))
                             (not (throwable? res))
                             (assoc :result res)))]
             ;; A go block, as the contract on `register!` says: a function
             ;; must not block here; one that has to offloads to a thread and
             ;; returns its channel. The gate may do the same.
             (async/go
               (let [allowed? (async/<! (authz/decision
                                         (gate {:principal principal
                                                :fn-name fn-name
                                                :arg-map arg-map
                                                :remote request-scope})))
                     decision (cond
                                (not= scope (:id @peer))
                                (ex-info "Invoke addressed to another peer"
                                         {:type ::wrong-peer :fn-name fn-name :scope scope})

                                (not allowed?)
                                (if principal
                                  (ex-info "Not authorized"
                                           {:type ::not-authorized :fn-name fn-name})
                                  (ex-info "Authentication required"
                                           {:type ::authentication-required :fn-name fn-name}))

                                :else ::call)
                     res (if (= decision ::call)
                           (try (<? S (call S fn-name args))
                                (catch #?(:clj Throwable :cljs :default) e e))
                           decision)]
                 (async/>! reply-out (respond res))))))
         (recur (async/<! invoke-ch))))
     {:stop! stop! :done done})))

;; =============================================================================
;; Invoking
;; =============================================================================

(defn- wait-for
  "A channel that yields once `remote` is reachable through `peer`, or through
   any peer when `peer` is nil; an exception after `timeout-ms`."
  [S peer remote timeout-ms]
  (go-try S
          (when-not (if peer
                      (get-in (state peer) [:connections remote])
                      (get @routes remote))
            (let [w (promise-chan)]
              (if peer
                (update-state! peer update-in [:waiters remote] (fnil conj []) w)
                (swap! route-waiters update remote (fnil conj []) w))
              (let [[_ port] (alts! (cond-> [w] timeout-ms (conj (timeout timeout-ms))))]
                (when (not= port w)
                  (throw (ex-info "Not connected to remote peer"
                                  {:type ::not-connected :remote remote :timeout-ms timeout-ms}))))))
          :ready))

(defn invoke
  "Invoke `fn-name` with `arg-map` on `remote` and return a channel yielding the
   result, or an exception.

   `(invoke remote fn-name arg-map)` finds the local peer connected to `remote`
   through `routes`; `(invoke peer remote fn-name arg-map)` names it. When
   `remote` is `peer` itself the function runs locally, ungated.

   While `remote` is not connected the call waits for the connection, without
   limit unless `opts` carries `:timeout-ms`. A connection that closes before
   the result arrives fails the call with `:kabel.remote/disconnected`; the
   function may or may not have run, so a caller that retries needs its own
   idempotency key in `arg-map`."
  ([remote fn-name arg-map]
   (invoke nil remote fn-name arg-map {}))
  ([peer remote fn-name arg-map]
   (invoke peer remote fn-name arg-map {}))
  ([peer remote fn-name arg-map {:keys [timeout-ms]}]
   (let [S (if peer (get-in @peer [:volatile :supervisor]) superv/S)]
     (go-try S
             (if (and peer (= remote (:id @peer)))
               (<? S (call S fn-name arg-map))
               (let [_ (<? S (wait-for S peer remote timeout-ms))
                     peer (or peer (get @routes remote))
                     {:keys [out dialect]} (get-in (state peer) [:connections remote])
                     rid (uuid)
                     ch (promise-chan)]
                 (update-state! peer assoc-in [:pending rid] {:ch ch :out out :fn-name fn-name})
                 (put? S out {:type (wire-type dialect :invoke)
                              :scope remote
                              :request-scope (:id @peer)
                              :fn-name fn-name
                              :arg-map arg-map
                              :request-id rid})
                 (let [[v port] (alts! (cond-> [ch] timeout-ms (conj (timeout timeout-ms))))]
                   (when (not= port ch)
                     (update-state! peer update :pending dissoc rid)
                     (throw (ex-info "Remote invocation timed out"
                                     {:type ::timeout :fn-name fn-name :timeout-ms timeout-ms})))
                   (cond (= v ::nil) nil
                         (throwable? v) (throw v)
                         :else v))))))))

(defn connect
  "Connect `peer` to `url` and wait until the other side has announced itself,
   so `invoke` works right away. Yields the remote peer id."
  [S peer url]
  (go-try S
          (let [[_ bus-out] (get-in @peer [:volatile :chans])
                ready (chan)]
            (sub bus-out ::ready ready)
            (try
              (<? S (peer/connect S peer url))
              (:remote (<? S ready))
              (finally
                (unsub bus-out ::ready ready)
                (close! ready))))))
