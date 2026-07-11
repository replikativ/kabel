(ns kabel.auth.http-kit
  "Authenticated http-kit handler that enriches inbound Kabel messages with
   :kabel/principal based on the initial Ring request (e.g., Authorization
   header or cookies). Falls back gracefully when no principal is available.

   This namespace is now a thin wrapper around kabel.http-kit, which accepts
   :on-connect and :annotate-msg hooks. Behaviour is preserved bit-for-bit;
   bug fixes in kabel.http-kit (e.g. closed-channel cleanup) propagate
   automatically."
  (:require [kabel.http-kit :as hk]
            [replikativ.logging :refer [warn]]))

(defn create-authenticated-http-kit-handler!
  "Creates an http-kit WebSocket handler that attaches a validated principal to
  every inbound message as :kabel/principal. The principal is derived once from
  the initial Ring request using the provided `validate-request-fn`, which
  should return a map (e.g., {:sub \"user\" ...}) or nil if unauthenticated.

  Returns a map compatible with kabel's server peer: {:new-conns :channel-hub
  :start-fn :url :handler}.

  Notes:
  - This wraps kabel.http-kit/create-http-kit-handler! and adds principal
    injection via the :on-connect / :annotate-msg hooks. Outbound messages
    are sent unchanged; downstream middleware like
    kabel.auth.session/session-middleware will strip :kabel/* on outbound.
  - If you want to reject unauthenticated connections up-front, have
    validate-request-fn throw; this handler will simply attach a nil principal.
    (To actively close the channel on unauth, do so in your higher-level
    middleware or in validate-request-fn before throwing.)"
  ([S url peer-id validate-request-fn]
   (create-authenticated-http-kit-handler!
    S url peer-id validate-request-fn (atom {}) (atom {})))
  ([S url peer-id validate-request-fn read-handlers write-handlers]
   (hk/create-http-kit-handler!
    S url peer-id read-handlers write-handlers
    {:on-connect
     (fn [request]
       (try (validate-request-fn request)
            (catch Exception e
              (warn {:event :auth-validation-error :error (str e)})
              nil)))
     :annotate-msg
     (fn [msg request principal]
       ;; Match the previous kabel-auth behaviour: always attach
       ;; :kabel/host (even for string-wrapped messages) and attach
       ;; :kabel/principal when one was resolved on connect.
       (let [host (:remote-addr request)]
         (cond-> msg
           (associative? msg) (assoc :kabel/host host)
           (and (associative? msg) principal) (assoc :kabel/principal principal))))})))
