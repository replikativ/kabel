(ns kabel.membership
  "Peer membership: an address book, a dial policy, and peer exchange.

  This is L1 of the overlay — *which peers do I keep connections to* — and it
  is useful on its own, with no dissemination layer above it: seed a node with
  one address and the network assembles itself. That is the \"auto connect\"
  and \"backend auto-discovery\" story, and it needs no DHT
  (`.internal/DHT_DESIGN.md` §9).

  ## Shape

  A pure state machine in the `kabel.sim` handler form, so the real policy —
  not a stub — runs under seeded churn, partition and loss:

      (handler state event ctx) -> {:state state' :actions [action ...]}

  Nothing here reads a clock or draws randomness outside `state`. `ctx`
  supplies `:now`; `state` carries `:rng`.

  ## Policy, and where it comes from

  The dial policy follows hyperswarm's connection layer, which is the most
  battle-tested version of this available and is transport-agnostic enough to
  port (`.internal/reference/hyperswarm.md`). Specifically:

  - a **priority ladder** over proven/attempts, where a *single* failure is
    treated as a transient blip and retried promptly rather than demoted;
  - **asymmetric backoff** — fast for the first retries, then a long tail;
  - a connection that survives `:proven-ms` **resets** the failure count, so a
    peer that works is not punished for old failures;
  - **inbound is never refused** for capacity reasons, only outbound dialing
    is capped;
  - a **duplicate-dial tie-break**, so two peers dialing each other
    simultaneously keep exactly one connection and agree on which.

  To that we add two things hyperswarm does not have and this deployment needs
  (`.internal/DHT_DESIGN.md` §2):

  - **group diversity** — at most `:max-per-group` peers share a group, so one
    operator with many addresses cannot fill the book. The group is supplied
    by the caller (a host, subnet or ASN); the principle is that it must be
    something an attacker pays for;
  - **hard ceilings on every collection**, because unbounded state was the one
    bug every reviewed system had.

  ## What this deliberately is not

  It never answers *does this peer count*. Membership is reachability, not
  authority (`.internal/DHT_DESIGN.md` §1). A consensus layer above may use
  this to find an address for an identity it already trusts, and nothing more."
  (:require [kabel.sim.rng :as rng]
            [kabel.topics :as topics]))

;; =============================================================================
;; Options
;; =============================================================================

(def default-opts
  {;; Outbound target. Inbound may push us above this; :max-connections is the
   ;; hard stop.
   :max-peers 8
   ;; Hard ceiling on live connections, inbound included. Exists so the
   ;; connection map cannot grow without bound.
   :max-connections 32
   ;; Concurrent outbound dials.
   :max-parallel 3
   ;; Routing-table diversity: at most this many peers from any one group.
   :max-per-group 2
   ;; Hard ceiling on address-book entries.
   :max-book 256
   ;; Backoff after 1, 2, 3, 4+ consecutive failures.
   :backoff-ms [1000 5000 15000 600000]
   ;; A connection lasting this long is "proven" and resets the failure count.
   :proven-ms 15000
   ;; How often to consider dialing.
   :dial-interval-ms 1000
   ;; How long to wait for a dial to be answered.
   :dial-timeout-ms 2000
   ;; How many book entries to offer a peer on connect.
   :exchange-size 8
   ;; Topics we want. Peers whose advertised ranges cover any of them are
   ;; preferred when dialling — without this, membership picks peers blind to
   ;; what they carry, and a subscriber has no way to reach the part of the
   ;; network that serves it. That blindness is what forces a discovery layer
   ;; at scale; carrying ranges in peer exchange defers it.
   :topics #{}})

(defn make-state
  "Initial membership state.

  `seeds` is a collection of `{:peer-id … :addresses [\"ws://…\"] :group …}`
  maps — the configured peer floor. They enter the book as unproven
  candidates.

  An entry without addresses is knowledge, not a candidate: we may know a peer
  exists and still have no way to reach it. `dialable?` enforces that."
  ([id] (make-state id [] {}))
  ([id seeds] (make-state id seeds {}))
  ([id seeds opts]
   {:id id
    ;; Replaced by kabel.sim/add-node with a per-node seeded rng; the default
    ;; is here so the policy functions can be unit-tested without a simulator.
    :rng (rng/make-rng 0)
    ;; Our OWN addresses, announced on every dial and dial-ok.
    ;;
    ;; A peer that dials us does not thereby tell us how to reach it — the
    ;; connection carries no return address — so without announcing this, an
    ;; inbound peer enters the book unreachable and can never be gossiped on.
    ;; This is the same fact that forces an identity handshake at the
    ;; transport layer, since kabel connections are anonymous.
    :addresses (vec (:addresses opts))
    :opts (merge default-opts (dissoc opts :addresses))
    :book (into {} (for [{:keys [peer-id addresses group]} seeds]
                     [peer-id {:addresses (vec addresses)
                               :group group :attempts 0 :proven? false
                               :last-success nil :backoff-until 0}]))
    :connections {}
    :dialing {}
    :stats {:dials 0 :dial-ok 0 :dial-failed 0 :dial-refused 0
            :inbound 0 :duplicates-resolved 0 :learned 0}}))

;; =============================================================================
;; Policy — pure, and unit-testable without the simulator
;; =============================================================================

(defn backoff-ms
  "How long to wait after `attempts` consecutive failures."
  [opts attempts]
  (let [ladder (:backoff-ms opts)]
    (if (<= attempts 0)
      0
      (nth ladder (min (dec attempts) (dec (count ladder)))))))

(defn priority
  "Dial priority for a book entry. **Lower is better.**

  The ladder, and the reasoning for each rung:

  | rung | entry | why |
  |---|---|---|
  | 0 | proven, no failures | it worked and still works — the sticky/anchor peers of `DHT_DESIGN` §2.3 |
  | 1 | exactly one failure | a single failure is usually a blip, so retry it *before* falling back to strangers |
  | 2 | proven, but failing now | it used to work; worth more than an unknown |
  | 3+ | unproven, degrading with attempts | strangers, worst first |

  Rung 1 is the counterintuitive one and is taken from hyperswarm: promoting a
  once-failed peer above untried ones recovers from transient loss quickly,
  and the backoff ladder — not the priority — is what stops a genuinely dead
  peer from being hammered."
  [{:keys [proven? attempts]}]
  (cond
    (and proven? (zero? attempts)) 0
    (= 1 attempts) 1
    proven? 2
    :else (+ 3 (min attempts 5))))

(defn group-counts
  "How many current connections come from each group."
  [state]
  (frequencies (keep (fn [[pid _]] (get-in state [:book pid :group]))
                     (:connections state))))

(defn dialable?
  "Is `peer-id` a legitimate dial candidate right now?"
  [state now peer-id]
  (let [{:keys [max-per-group]} (:opts state)
        entry (get-in state [:book peer-id])]
    (and entry
         (not= peer-id (:id state))
         ;; Knowing a peer is not the same as being able to reach one.
         (seq (:addresses entry))
         (not (contains? (:connections state) peer-id))
         (not (contains? (:dialing state) peer-id))
         (>= now (:backoff-until entry 0))
         ;; Group diversity: refuse a candidate that would over-represent its
         ;; group. A nil group is exempt, since "unknown" must not collapse
         ;; every unlabelled peer into one bucket.
         (or (nil? (:group entry))
             (< (get (group-counts state) (:group entry) 0) max-per-group)))))

(defn candidates
  "Peers worth dialing now, best first.

  Ties within a rung are broken randomly rather than by map order: a
  deterministic tie-break would make every node with the same book dial the
  same peer, which is how a network ends up with a hub instead of a mesh."
  [state now]
  (let [ids (filter #(dialable? state now %) (keys (:book state)))
        [rng' shuffled] (rng/shuffle (:rng state) ids)
        wanted (get-in state [:opts :topics] #{})
        ;; Relevance first, then the priority ladder. A peer advertising a
        ;; range that covers something we want is worth more than an equally
        ;; ranked stranger, and this is what lets a subscriber find its slice
        ;; of the network without a discovery protocol.
        relevance (fn [pid]
                    (if (and (seq wanted)
                             (topics/overlaps? (get-in state [:book pid :carries]) wanted))
                      0 1))]
    [(assoc state :rng rng')
     (sort-by (juxt relevance #(priority (get-in state [:book %]))) shuffled)]))

(defn want-dials
  "How many new outbound dials to start now."
  [state]
  (let [{:keys [max-peers max-parallel max-connections]} (:opts state)
        live (count (:connections state))
        pending (count (:dialing state))]
    (max 0 (min (- max-parallel pending)
                (- max-peers live pending)
                (- max-connections live pending)))))

(defn keep-new?
  "Tie-break deciding whose dial wins when two peers dial each other at once.

  Note what actually prevents duplicate connections here: `:connections` is
  keyed by peer-id, so a second connection to the same peer is structurally
  impossible — unlike hyperswarm, where connections are socket objects and two
  can coexist. This function therefore does the smaller job of keeping both
  sides in agreement about whether a re-dial is honoured or refused. It is
  kept, and tested for its symmetry property, because a socket-level transport
  will need exactly this rule.

  Both sides must reach the same answer, or they end up either with two
  connections or with none.

  `(compare my-id their-id) > 0` is symmetric between the two peers — exactly
  one of them sees it as true — and XOR-ing that with `initiator?` picks a
  consistent winner. Note that hyperswarm carries two subtly *different*
  copies of this rule, which is a fair warning that it is easy to get wrong;
  hence `kabel.membership-test/tie-break-is-symmetric`, which checks the
  property rather than the transcription."
  [my-id their-id initiator? existing-outdated?]
  (boolean
   (or existing-outdated?
       (= (pos? (compare (str my-id) (str their-id))) initiator?))))

;; =============================================================================
;; Book maintenance
;; =============================================================================

(def ^:private max-addresses-per-peer
  ;; hyperdht caps a peer record at three relay addresses; the principle is
  ;; that an address list is attacker-supplied and must not be a growth
  ;; vector.
  3)

(defn- merge-addresses [existing incoming]
  (vec (take max-addresses-per-peer (distinct (concat existing incoming)))))

(defn- new-entry [addresses group carries]
  {:addresses (merge-addresses [] addresses)
   :group group
   ;; What this peer advertises it relays. Gossiped onward, so a peer learns
   ;; where a topic lives without ever connecting there.
   :carries (topics/normalise (or carries #{}))
   :attempts 0 :proven? false
   :last-success nil :backoff-until 0})

(defn- ensure-entry [state peer-id addresses group carries]
  (if (get-in state [:book peer-id])
    (cond-> state
      ;; Addresses accumulate — a peer may legitimately gain one — but only up
      ;; to the cap, and existing ones keep their position so a flood of new
      ;; addresses cannot displace one that is known to work.
      (seq addresses)
      (update-in [:book peer-id :addresses] merge-addresses addresses)
      ;; Never let a gossiped record overwrite a group we already have; that
      ;; would let a peer relabel itself out of a full bucket.
      (and group (nil? (get-in state [:book peer-id :group])))
      (assoc-in [:book peer-id :group] group)

      (seq carries)
      (assoc-in [:book peer-id :carries] (topics/normalise carries)))
    (let [{:keys [max-book]} (:opts state)]
      (if (>= (count (:book state)) max-book)
        ;; Book is full: drop the worst unconnected entry to make room, and
        ;; only if the newcomer is not itself the worst thing available.
        (let [worst (->> (keys (:book state))
                         (remove (:connections state))
                         (sort-by #(- (priority (get-in state [:book %]))))
                         first)]
          (if worst
            (-> state
                (update :book dissoc worst)
                (assoc-in [:book peer-id] (new-entry addresses group carries)))
            state))
        (assoc-in state [:book peer-id] (new-entry addresses group carries))))))

(defn learn
  "Merge gossiped peer entries into the book.

  Entries are `{:peer-id … :addresses [\"ws://…\"] :group …}`."
  [state entries]
  (reduce (fn [s {:keys [peer-id addresses group carries]}]
            (if (or (= peer-id (:id s)) (nil? peer-id))
              s
              (let [known? (contains? (:book s) peer-id)
                    s (ensure-entry s peer-id addresses group carries)]
                (cond-> s
                  (not known?) (update-in [:stats :learned] inc)))))
          state
          entries))

(defn- record-failure [state now peer-id]
  (if-not (get-in state [:book peer-id])
    state
    (let [attempts (inc (get-in state [:book peer-id :attempts] 0))]
      (-> state
          (assoc-in [:book peer-id :attempts] attempts)
          (assoc-in [:book peer-id :backoff-until]
                    (+ now (backoff-ms (:opts state) attempts)))
          (update :dialing dissoc peer-id)
          (update-in [:stats :dial-failed] inc)))))

(defn- record-connected [state now peer-id initiator? addresses carries]
  (-> state
      (ensure-entry peer-id
                    (or (seq addresses) (get-in state [:book peer-id :addresses]))
                    (get-in state [:book peer-id :group])
                    (or carries (get-in state [:book peer-id :carries])))
      (assoc-in [:connections peer-id] {:since now :initiator? initiator?})
      (assoc-in [:book peer-id :last-success] now)
      (update :dialing dissoc peer-id)))

(defn connection-count [state] (count (:connections state)))

(defn connected? [state peer-id] (contains? (:connections state) peer-id))

;; =============================================================================
;; Handler
;; =============================================================================

(defn- exchange-payload
  "A bounded, randomly chosen slice of the book to offer a peer.

  Bounded because an unbounded exchange is a trivial amplification vector, and
  random so that two peers with the same book do not teach everyone the same
  subset."
  [state]
  (let [{:keys [exchange-size]} (:opts state)
        ;; Only peers we could actually reach are worth passing on. Gossiping
        ;; address-less entries spreads knowledge nobody can act on and lets a
        ;; peer fill everyone's book with unreachable names.
        entries (for [[pid e] (:book state)
                      :when (seq (:addresses e))]
                  {:peer-id pid :addresses (:addresses e) :group (:group e)
                   :carries (vec (:carries e))})
        [rng' chosen] (rng/sample (:rng state) exchange-size entries)]
    [(assoc state :rng rng') chosen]))

(defn dial-address
  "Which address to dial for `peer-id`.

  The first known address. Addresses accumulate in arrival order and a proven
  one is never displaced (see `merge-addresses`), so \"first\" means \"the one
  that has worked longest\" rather than an arbitrary pick."
  [state peer-id]
  (first (get-in state [:book peer-id :addresses])))

(defn- start-dials [state now]
  (let [n (want-dials state)]
    (if (zero? n)
      [state []]
      (let [[state cands] (candidates state now)
            chosen (take n cands)
            {:keys [dial-timeout-ms]} (:opts state)]
        [(-> (reduce (fn [s pid] (assoc-in s [:dialing pid] now)) state chosen)
             (update-in [:stats :dials] + (count chosen)))
         (vec (concat
               (for [pid chosen]
                 [:connect pid (dial-address state pid)
                  {:type :dial
                   :addresses (:addresses state)
                   :carries (vec (get-in state [:opts :carries] []))}])
               (for [pid chosen] [:timer dial-timeout-ms [:dial-timeout pid]])))]))))

(defn handler
  "Membership state machine. See the namespace docstring for the contract."
  [state event {:keys [now] :as _ctx}]
  (case (:type event)

    :init
    {:state state
     :actions [[:timer (get-in state [:opts :dial-interval-ms]) :dial-tick]]}

    :timer
    (let [payload (:payload event)]
      (cond
        (= :dial-tick payload)
        (let [[state actions] (start-dials state now)]
          {:state state
           :actions (conj actions
                          [:timer (get-in state [:opts :dial-interval-ms]) :dial-tick])})

        (and (vector? payload) (= :dial-timeout (first payload)))
        (let [pid (second payload)]
          (if (contains? (:dialing state) pid)
            {:state (record-failure state now pid) :actions []}
            {:state state :actions []}))

        (and (vector? payload) (= :prove (first payload)))
        (let [pid (second payload)]
          ;; Only a connection that has *survived* proven-ms counts. Checking
          ;; :since guards against a reconnect having replaced it in between.
          (if (and (connected? state pid)
                   (<= (+ (get-in state [:connections pid :since])
                          (get-in state [:opts :proven-ms]))
                       now))
            {:state (-> state
                        (assoc-in [:book pid :proven?] true)
                        (assoc-in [:book pid :attempts] 0)
                        (assoc-in [:book pid :backoff-until] 0))
             :actions []}
            {:state state :actions []}))

        :else {:state state :actions []}))

    ;; A transport went away. Distinct from a dial that never completed: the
    ;; connection existed, so there is nothing outstanding to time out and
    ;; nobody will tell us again.
    :disconnected
    (let [pid (:peer event)
          since (get-in state [:connections pid :since])
          {:keys [proven-ms]} (:opts state)
          ;; A connection that never reached :proven-ms and then dropped is a
          ;; flapping peer, and is penalised so the backoff ladder throttles
          ;; it. One that lived longer is treated as a transient loss and
          ;; keeps its rung, so a proven peer is redialled promptly rather
          ;; than being punished for a network blip.
          ;;
          ;; Without the first half, a peer that accepts and immediately drops
          ;; is redialled every dial-tick forever; without the second, every
          ;; restart of a good peer costs a backoff.
          flapping? (and since (< (- now since) proven-ms))]
      {:state (cond-> (-> state
                          (update :connections dissoc pid)
                          (update :dialing dissoc pid))
                flapping?
                (as-> s
                      (let [attempts (inc (get-in s [:book pid :attempts] 0))]
                        (-> s
                            (assoc-in [:book pid :attempts] attempts)
                            (assoc-in [:book pid :backoff-until]
                                      (+ now (backoff-ms (:opts s) attempts)))))))
       :actions []})

    :message
    (let [from (:from event)
          payload (:payload event)
          {:keys [type entries]} payload]
      (case type

        :dial
        (cond
          ;; Already connected: this is a simultaneous dial. Both sides run
          ;; the same tie-break and agree on which connection survives.
          (connected? state from)
          (let [keep? (keep-new? (:id state) from false false)]
            {:state (update-in state [:stats :duplicates-resolved] inc)
             :actions [[:send from (if keep?
                                     {:type :dial-ok
                                      :addresses (:addresses state)
                                      :carries (vec (get-in state [:opts :carries] []))}
                                     ;; Benign: we are already connected. Not a
                                     ;; capacity refusal, so no backoff.
                                     {:type :dial-refused :reason :duplicate})]]})

          ;; Inbound is never refused for capacity, only at the hard ceiling —
          ;; refusing inbound is how a network fails to heal after a partition.
          ;;
          ;; A refusal MUST carry somewhere else to go. Peer exchange normally
          ;; happens over a connection, so a newcomer refused by its only seed
          ;; can never learn another address and is stranded permanently:
          ;; measured, 7 of 40 nodes with a book containing nothing but the
          ;; seed, still isolated after 900 s. This is gossipsub's peer
          ;; exchange on PRUNE, and it is not decorative — it is what stops a
          ;; saturated entry point from being a black hole.
          (>= (connection-count state) (get-in state [:opts :max-connections]))
          (let [[state offer] (exchange-payload state)]
            {:state state
             :actions [[:send from {:type :dial-refused
                                    :reason :capacity
                                    :peers offer}]]})

          :else
          (let [state (-> (record-connected state now from false (:addresses payload) (:carries payload))
                          (update-in [:stats :inbound] inc))
                [state offer] (exchange-payload state)]
            {:state state
             :actions [[:send from {:type :dial-ok
                                    :addresses (:addresses state)
                                    :carries (vec (get-in state [:opts :carries] []))}]
                       [:send from {:type :peers :entries offer}]
                       [:timer (get-in state [:opts :proven-ms]) [:prove from]]]}))

        :dial-ok
        (cond
          (connected? state from)
          {:state state :actions []}

          ;; The ceiling is a HARD bound and has to be enforced here too, not
          ;; only on inbound. `want-dials` reserves a slot when the dial is
          ;; issued, but inbound connections can fill it before the answer
          ;; arrives — measured at 10 connections against a ceiling of 8 once
          ;; referrals made dialling livelier. Enforcing on one path only makes
          ;; the bound hold in quiet networks and fail in busy ones, which is
          ;; the wrong way round.
          (>= (connection-count state) (get-in state [:opts :max-connections]))
          {:state (update state :dialing dissoc from)
           :actions [[:disconnect from]]}

          :else
          (let [state (-> (record-connected state now from true (:addresses payload) (:carries payload))
                          (update-in [:stats :dial-ok] inc))
                [state offer] (exchange-payload state)]
            {:state state
             :actions [[:send from {:type :peers :entries offer}]
                       [:timer (get-in state [:opts :proven-ms]) [:prove from]]]}))

        :dial-refused
        (let [state (-> state
                        ;; Take the referral first: it is the whole point of a
                        ;; capacity refusal.
                        (learn (:peers payload))
                        (update :dialing dissoc from)
                        (update-in [:stats :dial-refused] inc))]
          (if (= :duplicate (:reason payload))
            ;; Already connected — nothing failed.
            {:state state :actions []}
            ;; A capacity refusal IS a failure to connect, and must engage the
            ;; backoff ladder. Without this the refused peer redials every
            ;; tick forever: measured at 299 refusals in 300 s with `attempts`
            ;; still 0, which is a denial of service on the seed and a flat
            ;; battery on the client.
            (let [attempts (inc (get-in state [:book from :attempts] 0))]
              {:state (-> state
                          (assoc-in [:book from :attempts] attempts)
                          (assoc-in [:book from :backoff-until]
                                    (+ now (backoff-ms (:opts state) attempts))))
               :actions []})))

        :peers
        {:state (learn state entries) :actions []}

        :disconnect
        {:state (update state :connections dissoc from) :actions []}

        {:state state :actions []}))

    {:state state :actions []}))
