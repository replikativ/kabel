(ns kabel.store.protocol
  "Durable state for the overlay, behind a protocol kabel defines and the
  consumer implements.

  ## Why a protocol rather than a konserve dependency

  kabel's base library deliberately pulls no storage dependency — the same
  reasoning that keeps JWT and crypto behind the `:auth` alias. The surface the
  overlay needs is tiny (get, put, remove, list over a handful of keys), so
  defining it costs almost nothing, while depending on konserve would put a
  storage engine in the graph of every kabel user, including those who never
  start an overlay.

  This mirrors `kabel.auth.store.protocol`, which already does exactly this
  with a portable in-memory implementation and a consumer-supplied durable one.
  One storage story in the repository, not two.

  A konserve implementation lives in `kabel.store.konserve` behind the
  `:konserve` alias — konserve runs on the JVM, Node and the browser
  (IndexedDB), so a browser peer gets durable overlay state for free.

  ## What actually needs to survive a restart

  Less than it looks:

  | key | why |
  |---|---|
  | `:identity` | **essential** — the keypair *is* the peer's name; losing it means becoming a different peer |
  | `:book`     | the sticky/anchor-peer mitigation (`.internal/DHT_DESIGN.md` §2.3) is worthless without it |
  | `:epoch`    | must never go backwards, or replayed sequence numbers are suppressed as duplicates |
  | `:records`  | L3 provider records, when content routing lands |

  Seen sets are deliberately **not** on the list: a fresh epoch is a fresh
  namespace, so a restarted peer's numbering cannot collide with its own past.
  The repair store is a cache and is not persisted either.

  ## The epoch, and how to avoid needing to store it

  `:epoch` is the one entry with a correctness requirement rather than a
  convenience one. A monotone wall clock — `max(now, last-stamp)` — satisfies
  it without any storage at all, which is the same trick konserve uses for its
  `:last-write` stamps. `kabel.store.memory` therefore remains usable in
  production for peers that accept a regenerated identity, and
  `monotonic-epoch` below is the helper.

  ## Contract

  Every method returns a **channel** yielding the result, so an implementation
  may be asynchronous — which the browser ones must be. Implementations must
  not block the calling thread."
  (:require #?(:clj [clojure.core.async :as async :refer [chan put! close!]]
               :cljs [clojure.core.async :as async :refer [chan put! close!]])))

(defprotocol PPeerStore
  "Durable key-value state for one peer's overlay.

  Keys are keywords from a small, fixed vocabulary (see the namespace
  docstring); values are EDN. This is not a general-purpose store and should
  not become one — anything larger belongs in konserve directly."

  (-load [store k]
    "Return a channel yielding the value at `k`, or nil.")

  (-store! [store k v]
    "Persist `v` at `k`. Returns a channel yielding `v`.")

  (-remove! [store k]
    "Delete `k`. Returns a channel yielding true.")

  (-keys* [store]
    "Return a channel yielding the set of keys present."))

;; =============================================================================
;; Helpers
;; =============================================================================

(defn chan-of
  "A closed channel carrying `v` — the trivial way to satisfy the contract from
  a synchronous implementation."
  [v]
  (let [ch (chan 1)]
    (when (some? v) (put! ch v))
    (close! ch)
    ch))

(defn monotonic-epoch
  "An epoch that never goes backwards, from a wall clock that might.

  `previous` is the last epoch this peer used, or nil. The result is
  `max(now, previous + 1)`, so an NTP step back, a VM suspend or a clock reset
  cannot produce an epoch a peer has already published under — which would let
  its fresh sequence numbers be suppressed as duplicates by peers that
  remember the old run.

  Passing `nil` for `previous` is exactly the no-persistence case, and is why
  a peer can run without a durable store at all."
  ([] (monotonic-epoch nil))
  ([previous]
   (let [now #?(:clj (System/currentTimeMillis)
                :cljs (.getTime (js/Date.)))]
     (if (and previous (>= previous now))
       (inc previous)
       now))))
