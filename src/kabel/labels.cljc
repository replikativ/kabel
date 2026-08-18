(ns kabel.labels
  "Moderation as subscription: signed assertions about subjects, honoured only
  by clients that chose to honour them.

  ## Why this shape

  An identity here costs one key generation, so bans are structurally
  unenforceable and every identity-based moderation technique fails (see
  `doc/MODERATION.md`). What remains is judgment other people can choose
  to accept.

  Taken from AT Protocol's labelers, deliberately including the part that looks
  like a weakness: **a labeler cannot take anything down.** It can only assert.
  A label becomes an action because the *receiving client* privileged that
  labeler, and only for that client. Bluesky removed 66 000 accounts in 2024 and
  no third-party labeler could have — their decentralisation of judgment is real
  while enforcement stayed central, and this is the honest version of the same
  split for a network with no centre at all.

  ## It needs almost no protocol

  A label is an ordinary signed publish on `[:labels <labeler> <subject>]`, so
  it inherits everything: origin signatures verified at every hop, interval-set
  deduplication, anti-entropy repair, and topic ranges — a peer can relay one
  labeler and not another by carrying `[:labels <labeler>]`. Subscribing to a
  labeler is subscribing to a topic.

  This is the same finding as chunking: the interesting mechanism was already
  there, and the work is a convention plus a verdict function.

  ## What a verdict is not

  `verdict` returns what the *local* policy says to do. It has no side effects,
  it cannot reach another peer, and nothing propagates from it. A peer that
  ignores it is not violating the protocol — it is exercising the only real
  authority in the system, which is over itself."
  (:require [kabel.topics :as topics]))

(def ^:const label-version "kabel/label/v1")

;; Ordered weakest to strongest. A client's trust setting caps how far a
;; labeler's assertion may be promoted, so an untrusted labeler saying
;; `:takedown` is worth no more than the cap allows.
(def actions [:none :inform :warn :hide :takedown])

(def ^:private action-rank (into {} (map-indexed (fn [i a] [a i]) actions)))

(defn make-label
  "An assertion by `labeler` about `subject`.

  `subject` is whatever is being talked about — a peer id, a content hash, a
  topic. Deliberately untyped: a labeler that wants to talk about something new
  should not need a protocol change.

  `negate?` retracts an earlier label of the same value, because a labeler that
  cannot change its mind is a labeler nobody should subscribe to."
  [{:keys [labeler subject value action negate? expires-at]}]
  {:kabel.label/kind label-version
   :kabel.label/labeler labeler
   :kabel.label/subject subject
   :kabel.label/value value
   :kabel.label/action (or action :warn)
   :kabel.label/negate? (boolean negate?)
   :kabel.label/expires-at expires-at})

(defn label?
  [l]
  (and (map? l)
       (= label-version (:kabel.label/kind l))
       (some? (:kabel.label/labeler l))
       (some? (:kabel.label/subject l))
       (some? (:kabel.label/value l))
       (contains? action-rank (:kabel.label/action l))))

(defn topic-for
  "Where a labeler publishes about a subject.

  A path, so a relay can carry `[:labels <labeler>]` — one labeler's whole
  output — without carrying every labeler in the network."
  [labeler subject]
  [:labels labeler subject])

(defn subscribed-topic
  "The range a client subscribes to in order to hear from `labeler`."
  [labeler]
  [:labels labeler])

;; =============================================================================
;; Client state
;; =============================================================================

(defn make-state
  "Client-side label state.

  `trust` maps a labeler to the strongest action it may cause — the seam. A
  labeler absent from `trust` is heard but cannot act, which is what makes
  subscribing to a labeler safe to try."
  ([] (make-state {}))
  ([opts]
   {:opts (merge {;; Labels retained per subject. Attacker-supplied, so bounded.
                  :max-per-subject 32
                  ;; Subjects retained overall.
                  :max-subjects 4096}
                 opts)
    :trust (or (:trust opts) {})
    ;; subject -> {[labeler value] label}
    :labels {}
    :stats {:accepted 0 :retracted 0 :refused 0 :unknown-labeler 0}}))

(defn trust!
  "Privilege `labeler` up to `max-action`.

  This is the whole enforcement seam. `:takedown` from a labeler trusted only to
  `:warn` yields a warning — an assertion is never stronger than the trust the
  receiver placed in its author."
  [state labeler max-action]
  (assoc-in state [:trust labeler] max-action))

(defn untrust!
  "Stop honouring `labeler`. Its labels remain — they are simply inert."
  [state labeler]
  (update state :trust dissoc labeler))

(defn- prune
  [state]
  (let [{:keys [max-subjects]} (:opts state)]
    (if (> (count (:labels state)) max-subjects)
      (update state :labels dissoc (first (sort (keys (:labels state)))))
      state)))

(defn accept
  "Record a label. Returns `[state outcome]`.

  A label from an entirely unknown labeler is still recorded — trust can be
  granted later, and re-fetching history to act on it would be worse. What
  `trust` gates is the *verdict*, not the storage."
  [state label]
  (cond
    (not (label? label))
    [(update-in state [:stats :refused] inc) :refused]

    :else
    (let [{lab :kabel.label/labeler subj :kabel.label/subject
           v :kabel.label/value neg? :kabel.label/negate?} label
          k [lab v]]
      (if neg?
        [(-> state
             (update-in [:labels subj] dissoc k)
             (update-in [:stats :retracted] inc))
         :retracted]
        (let [held (get-in state [:labels subj] {})
              {:keys [max-per-subject]} (:opts state)]
          (if (and (>= (count held) max-per-subject)
                   (not (contains? held k)))
            [(update-in state [:stats :refused] inc) :refused]
            [(-> state
                 (assoc-in [:labels subj k] label)
                 (update-in [:stats :accepted] inc)
                 prune)
             :accepted]))))))

(defn- live?
  [label now]
  (let [e (:kabel.label/expires-at label)]
    (or (nil? e) (> e now))))

(defn labels-for
  "Live labels on `subject`, whatever their author."
  [state subject now]
  (->> (get-in state [:labels subject] {})
       vals
       (filter #(live? % now))
       (sort-by (juxt :kabel.label/labeler :kabel.label/value))
       vec))

(defn verdict
  "What local policy says to do about `subject`. Returns
  `{:action a :reasons [...]}`.

  The strongest action any *trusted* labeler asserts, each capped by the trust
  placed in its author. An untrusted labeler contributes nothing — it is heard,
  recorded and ignored.

  Local by construction: nothing here propagates, and a peer that ignores the
  verdict is exercising the only authority this system actually confers, which
  is over itself."
  [state subject now]
  (let [trust (:trust state)
        considered (for [l (labels-for state subject now)
                         :let [cap (get trust (:kabel.label/labeler l))]
                         :when cap
                         :let [asserted (:kabel.label/action l)
                               effective (if (< (action-rank asserted)
                                                (action-rank cap))
                                           asserted
                                           cap)]]
                     {:labeler (:kabel.label/labeler l)
                      :value (:kabel.label/value l)
                      :asserted asserted
                      :effective effective})
        strongest (reduce (fn [a b] (if (> (action-rank (:effective b))
                                           (action-rank a))
                                      (:effective b) a))
                          :none
                          considered)]
    {:action strongest
     :reasons (vec considered)}))

(defn hidden?
  "Convenience: does policy say to withhold `subject` from a user?"
  [state subject now]
  (>= (action-rank (:action (verdict state subject now)))
      (action-rank :hide)))

(defn carries-labeler?
  "Does a peer relaying `ranges` carry this labeler's output?"
  [ranges labeler]
  (topics/covered? ranges (subscribed-topic labeler)))
