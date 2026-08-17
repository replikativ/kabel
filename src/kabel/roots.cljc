(ns kabel.roots
  "Database roots as a verifiable chain, not a snapshot.

  ## Why a signature is not enough

  A signed root proves *who said it*. It does not prove *that it is current* —
  a signature is valid forever, so an old signed root is a perfectly valid
  signed root. A peer serving you version 5 when version 9 exists is not
  forging anything, and no amount of signature checking detects it.

  Tahoe-LAFS is the worked example: `best_recoverable_version()` takes the
  maximum sequence number over *observed* versions and its own stop condition
  is commented `\"Good enough.\"` Rollback there needs only a few colluding
  servers — or one that ran out of disk. A quorum heuristic buys nothing at all
  when there may be a single provider, which is our normal case.

  ## What this adds

  Three rules, all local, none needing a clock, a quorum or a serialiser:

  1. **Monotone pinning.** We remember the highest version we have accepted and
     refuse anything at or below it. Rollback becomes detectable *by the
     victim*, without anyone else's cooperation.
  2. **Hash chaining.** Each record names its predecessor's root, so a served
     history is either continuous or visibly broken.
  3. **Equivocation is evidence, not a tie to break.** Two different roots at
     the same version from the same publisher are a non-repudiable proof of
     compromise. did:plc resolves this case with a Postgres row lock; we have
     no serialiser and should not pretend otherwise, so we keep both records and
     report it.

  ## Inductive verification, and what it actually saves

  This is AT Protocol's arrangement, and the saving is **storage, not crypto**:
  their relay went from 16 TB to ~21 GB by holding one hash per producer instead
  of archiving repositories.

  The verifier keeps exactly one entry per database — version, root, publisher —
  and accepts the next record because it is a valid successor to *that*, not
  because it re-examined any history. Trust in version n comes from having
  accepted n-1 plus the transition; the base case is the first record ever
  accepted.

  Note what it costs, because atproto paid it: verification this cheap makes
  archival somebody else's job. A peer that has been away long enough to have a
  **gap** cannot verify inductively across it — it can only fetch the missing
  records from someone who kept them, or accept a new base case and lose the
  guarantee. `:gap` is reported rather than papered over for exactly this
  reason.

  ## What this does NOT do

  It does not verify the contents at the root. That happens on fetch, where
  every node is checked against its own content address (`kabel.content`).
  Accepting a root says \"this is the publisher's current version\", not
  \"I have it\" and not \"it is well-formed\"."
  (:require [kabel.topics :as topics]))

(def ^:const record-version "kabel/root/v1")

(defn make-record
  "A root announcement.

  `prev` is the root this one replaces, or nil for the first. `version` must
  increase by exactly one per publication — gaps are what stop a verifier
  reasoning inductively, so a publisher that skips numbers is making its own
  history unverifiable."
  [{:keys [database version root prev publisher]}]
  {:kabel/kind record-version
   :kabel/database database
   :kabel/version version
   :kabel/root root
   :kabel/prev prev
   :kabel/publisher publisher})

(defn root-record?
  [r]
  (and (map? r)
       (= record-version (:kabel/kind r))
       (some? (:kabel/database r))
       (integer? (:kabel/version r))
       (nat-int? (:kabel/version r))
       (some? (:kabel/root r))
       (some? (:kabel/publisher r))))

(defn make-state
  "Verifier state: one entry per database, plus retained equivocation proofs."
  ([] (make-state {}))
  ([opts]
   {:opts (merge {;; Accept an unknown database's first record on trust. The
                  ;; alternative is refusing to bootstrap; what makes it safe
                  ;; enough is that everything AFTER it is checked, and that a
                  ;; caller who knows the publisher can pin it in advance.
                  :trust-on-first-use? true
                  ;; Retained forever: an equivocation proof that expires is
                  ;; not a proof. Bounded because it is attacker-triggerable.
                  :max-equivocations 64}
                 opts)
    ;; database -> {:version n :root r :publisher p}
    :heads {}
    ;; database -> [record record] — two roots at one version, kept as evidence
    :equivocations {}
    :stats {:accepted 0 :stale 0 :forks 0 :gaps 0
            :wrong-publisher 0 :malformed 0}}))

(defn head
  "What we currently believe about `database`, or nil."
  [state database]
  (get-in state [:heads database]))

(defn pin
  "Declare in advance who may publish roots for `database`.

  Removes the trust-on-first-use window for that database: a caller who already
  knows the owner should say so, and then no first record can establish a
  different one."
  [state database publisher]
  (assoc-in state [:heads database] {:version -1 :root nil :publisher publisher}))

(defn compromised?
  "Has `database` produced an equivocation proof?

  Absorbing: once true, always true. A publisher that has signed two different
  roots at one version has demonstrated key compromise or duplicity, and no
  later record un-demonstrates it."
  [state database]
  (boolean (seq (get-in state [:equivocations database]))))

(defn- record-equivocation
  [state database a b]
  (let [{:keys [max-equivocations]} (:opts state)]
    (cond-> (update-in state [:equivocations database] (fnil conj []) [a b])
      (> (count (:equivocations state)) max-equivocations)
      (update :equivocations dissoc (first (sort (keys (:equivocations state))))))))

(defn accept
  "Consider `record`. Returns `[state outcome]`.

  Outcomes:

  - `:accepted`        — a valid successor; the head advanced
  - `:first`           — accepted as a base case (trust on first use)
  - `:stale`           — at or below what we hold; a rollback attempt
  - `:fork`            — a DIFFERENT root at a version we already hold
  - `:gap`             — too far ahead to verify inductively
  - `:not-successor`   — right version, but `prev` does not match our root
  - `:wrong-publisher` — a different key than the one that owns this database
  - `:malformed`       — not a root record

  Nothing here consults a clock. All three defences are local, which is what
  makes them work for a peer that has been offline for a month."
  [state record]
  (if-not (root-record? record)
    [(update-in state [:stats :malformed] inc) :malformed]
    (let [{db :kabel/database v :kabel/version r :kabel/root
           prev :kabel/prev pub :kabel/publisher} record
          cur (head state db)]
      (cond
        ;; A database already proven compromised accepts nothing further. This
        ;; is the absorbing rule: a highest-version-wins policy would let an
        ;; attacker who holds the key simply keep incrementing.
        (compromised? state db)
        [(update-in state [:stats :forks] inc) :fork]

        (nil? cur)
        (if (get-in state [:opts :trust-on-first-use?])
          [(-> state
               (assoc-in [:heads db] {:version v :root r :publisher pub})
               (update-in [:stats :accepted] inc))
           :first]
          [(update-in state [:stats :wrong-publisher] inc) :wrong-publisher])

        (not= pub (:publisher cur))
        [(update-in state [:stats :wrong-publisher] inc) :wrong-publisher]

        ;; Same version, different root: the publisher signed two histories.
        (and (= v (:version cur)) (not= r (:root cur)))
        [(-> state
             (record-equivocation db {:version (:version cur) :root (:root cur)} record)
             (update-in [:stats :forks] inc))
         :fork]

        (<= v (:version cur))
        [(update-in state [:stats :stale] inc) :stale]

        (> v (inc (:version cur)))
        [(update-in state [:stats :gaps] inc) :gap]

        ;; Exactly the next version — the chain must join up.
        (not= prev (:root cur))
        [(update-in state [:stats :gaps] inc) :not-successor]

        :else
        [(-> state
             (assoc-in [:heads db] {:version v :root r :publisher pub})
             (update-in [:stats :accepted] inc))
         :accepted]))))

(defn missing-versions
  "Which versions we would need to close a `:gap` for `database` up to
  `version`.

  A gap is not fatal — it is a fetch. But it has to be *named*, because a
  verifier that quietly accepted across one would have given up the very
  property the chain exists to provide."
  [state database version]
  (let [cur (head state database)]
    (when (and cur (> version (inc (:version cur))))
      {:database database :from (inc (:version cur)) :to (dec version)})))

(defn topic-for
  "The topic a database's roots are published on.

  A path, so relays can carry `[:kabel/roots \"alice\"]` — every database of
  one publisher — without carrying the whole network."
  [publisher database]
  [:kabel/roots publisher database])

(defn covers-database?
  "Does a peer carrying `ranges` relay this database's roots?"
  [ranges publisher database]
  (topics/covered? ranges (topic-for publisher database)))
