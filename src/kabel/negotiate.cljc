(ns kabel.negotiate
  "Capability negotiation: agree on a codec and a feature set before either end
  needs the other to understand it.

  ## The hole this fills

  kabel's own source has been naming this gap for a while.
  `kabel.binary.table`: *\"This is durable wire state. The id is written into
  every frame and peers do not negotiate\"* … *\"Removing the need is what
  capability negotiation would do.\"* `kabel.middleware.dual` implements the
  workaround — a three-step deployment dance ending *\"There is no way to skip
  step 1.\"*

  Inbound is already self-describing: every frame carries its codec id, so a
  peer can *read* anything it has support for. What is missing is the other
  half — knowing what the peer can read **before you write**. That is the whole
  of what this namespace adds.

  ## The chicken-and-egg, and the way out

  You cannot negotiate a codec inside a frame that requires the codec being
  negotiated. But `kabel.binary/to-binary` falls back to `pr-str` (frame id 2)
  when `:kabel/serialization` is unset, and **every** codec middleware guards
  its in-branch on a serialization it recognises and passes anything else
  through untouched. So frame id 2 is a universal channel, readable by every
  kabel peer ever built. A full hello costs 118 bytes.

  This is why the middleware must sit **below** the codec and above the
  transport: it has to write a frame the codec layer would otherwise claim.
  Compose it into the serialization slot, innermost:

      (peer/client-peer S id middleware (comp transit #(negotiate opts %)))

  ## Agreement without a tie-break

  Both ends must independently arrive at the *same* codec, and \"first of my
  preferences that appears in yours\" does not do that: given `[:cbor
  :fressian]` and `[:fressian :cbor]`, each end picks its own favourite and
  they disagree. So the choice is the intersection ranked by a **canonical**
  order both sides already share — the frame id, where ids are assigned
  strictly increasing and never recycled, so highest means newest. No tie-break
  is needed and the outcome does not depend on who dialled.

  ## The agreement flows THROUGH the stack, parametrically

  The result is not a private fact this namespace keeps. It is emitted inband as

      {:type :kabel/negotiated :caps {…}}

  and travels up the ordinary in-channel, so **every middleware above sees it**.
  A middleware that cares keeps a per-connection atom, updates it when the
  message passes, and lets its out-branch consult it — which is how a handshake
  parameterises a stack it knows nothing about. The codec layer can learn which
  codec to write, a compression middleware can learn whether `:deflate` was
  agreed, a chunker can learn `:max-frame`.

  This works because kabel's middlewares already ignore what they do not
  recognise: `kabel.pubsub` dispatches unknown types to `:unrelated` and passes
  them through, the codec middlewares guard on their own serialization, and the
  overlay passes non-overlay frames on. So a new capability needs no
  cooperation from the middlewares that do not care about it, and adding one
  later cannot break them.

  `:on-negotiated` exists too, but it is the convenience, not the mechanism.

  ## A peer that says nothing is not a failure

  Silence means a peer built before this existed. After `:timeout-ms` we
  proceed with `nil` capabilities, which means exactly today's behaviour. That
  is what makes this deployable without a flag day — the failure this whole
  namespace exists to remove."
  (:require [kabel.binary.table :as table]
            [replikativ.logging :as log]
            [clojure.set :as set]
            #?(:clj [superv.async :refer [go-try <? >? go-loop-try]])
            [clojure.core.async :as async :refer [chan close! timeout alts!]])
  #?(:cljs (:require-macros [superv.async :refer [go-try <? >? go-loop-try]])))

(def ^:const hello-type :kabel/hello)

(def default-opts
  {;; What we can READ. Order is documentation only — agreement uses the
   ;; canonical ranking below, so a peer cannot skew the outcome by reordering.
   :codecs [:transit-json :fressian :cbor]
   ;; Optional behaviours, not codecs. Intersected, so a feature is on only if
   ;; both ends have it.
   :features #{}
   ;; Largest frame we will accept. The agreed value is the MINIMUM, so neither
   ;; end can talk the other into buffering more than it chose to.
   :max-frame (* 1024 1024)
   ;; Can this transport carry bytes at all? False for a text transport such as
   ;; SSE. Not a preference — a fact, and the reason a codec choice has to be
   ;; able to hear about the transport.
   :binary? true
   ;; How long to wait for a peer's hello before concluding it has none.
   :timeout-ms 5000})

(defn make-hello
  [{:keys [codecs features max-frame binary?]}]
  {:type hello-type
   :kabel/protocol 1
   :kabel/codecs (vec codecs)
   :kabel/features (set features)
   :kabel/max-frame max-frame
   :kabel/binary? (boolean binary?)})

(defn hello?
  [m]
  (and (map? m) (= hello-type (:type m))))

(defn- rank
  "Canonical rank of a codec: its frame id. Unknown codecs rank below
  everything, so a peer advertising a name we have never heard of cannot win."
  [c]
  (get table/encoding-table c -1))

(defn text-codecs
  "Codecs whose payload is text and therefore survives a transport that cannot
  carry bytes."
  []
  #{:transit-json :string :pr-str})

(defn agree
  "Agreed capabilities from our options and a peer's hello, or `nil` if there
  is no common ground.

  Deterministic and symmetric: both ends compute the same map from the same two
  inputs, which is the property that makes this safe to run on both sides at
  once."
  [opts their-hello]
  (let [ours (set (:codecs opts))
        theirs (set (:kabel/codecs their-hello))
        ;; Binary is a veto, not a vote: if EITHER transport cannot carry
        ;; bytes, a binary codec is not an option however much both ends like
        ;; it.
        binary? (and (boolean (:binary? opts))
                     (boolean (:kabel/binary? their-hello)))
        common (cond-> (set/intersection ours theirs)
                 (not binary?) (set/intersection (text-codecs)))]
    (when (seq common)
      {:codec (apply max-key rank (sort common))
       :codecs common
       :features (set/intersection (set (:features opts))
                                   (set (:kabel/features their-hello)))
       :max-frame (min (:max-frame opts)
                       (or (:kabel/max-frame their-hello) (:max-frame opts)))
       :binary? binary?
       :protocol (or (:kabel/protocol their-hello) 1)})))

(defn negotiate
  "Middleware that exchanges a hello and reports the agreement.

  Belongs in the serialization slot, innermost, so its own frame is written
  before any codec can claim it.

  `opts` are `default-opts` plus:

  - `:on-negotiated` — `(fn [peer caps])`, once, with `nil` for a peer that
    sent no hello.

  The agreement is delivered INBAND as `{:type :kabel/negotiated :caps …}` so
  every middleware above can pick up parameters from it; `:on-negotiated` is a
  convenience on top. A connection with no
  common codec is CLOSED rather than left to fail later on an unreadable
  frame — `kabel.binary.table/decoding-for` already argues that case: a
  connection that stops with a typed error is diagnosable, one that quietly
  acts on undecoded bytes is not."
  ([[S peer [in out]]] (negotiate {} [S peer [in out]]))
  ([opts [S peer [in out]]]
   (let [opts (merge default-opts opts)
         {:keys [on-negotiated timeout-ms]} opts
         new-in (chan)
         new-out (chan)
         done (atom false)]

     ;; Announce unconditionally, exactly as the overlay's identity hello does.
     ;; Both ends do it, so nothing depends on who dialled.
     (go-try S (>? S out (make-hello opts)))

     ;; A peer that predates this sends nothing. Time-box the wait and carry on
     ;; with today's behaviour rather than hanging the connection.
     (go-try S
             (<? S (timeout timeout-ms))
             (when (compare-and-set! done false :legacy)
               (log/debug :negotiate/no-hello {:timeout-ms timeout-ms})
               (when on-negotiated (on-negotiated peer nil))
               (>? S new-in {:type :kabel/negotiated :caps nil})))

     (go-loop-try S [m (<? S in)]
                  (if m
                    (do
                      (if (hello? m)
                        (let [caps (agree opts m)]
                          (when (compare-and-set! done false :negotiated)
                            (if caps
                              (do (log/debug :negotiate/agreed caps)
                                  (when on-negotiated (on-negotiated peer caps))
                                  (>? S new-in {:type :kabel/negotiated
                                                :caps caps}))
                              (do (log/error :negotiate/no-common-codec
                                             {:ours (:codecs opts)
                                              :theirs (:kabel/codecs m)
                                              :binary? (:binary? opts)})
                                  (close! out)))))
                        (>? S new-in m))
                      (recur (<? S in)))
                    (close! new-in)))

     (go-loop-try S [o (<? S new-out)]
                  (if o
                    (do (>? S out o) (recur (<? S new-out)))
                    (close! out)))

     [S peer [new-in new-out]])))
