(ns kabel.middleware.dual
  "Dual-format composition: read fressian AND CBOR, write one of them.

  The mechanism for migrating a deployment that cannot upgrade both ends at
  once. It lives here rather than in `kabel.middleware.cbor` because requiring
  it from there made every CBOR-only consumer pull in fressian — and on
  ClojureScript the whole of `fress` — for a rollout helper they never call.
  Requiring this namespace is an explicit statement that you want both codecs.

  ## Rollout

  A peer that does not know frame 14 cannot read it. So:

    1. deploy EVERY peer on `dual-read-fressian-write` — understands 14, still
       writes 13, so the wire does not change;
    2. once no peer predates step 1, switch writers to `dual-read-cbor-write`;
    3. optionally, much later, drop to plain `kabel.middleware.cbor/cbor`.

  There is no way to skip step 1."
  (:require [kabel.middleware.cbor :refer [cbor]]
            [kabel.middleware.fressian :refer [fressian]]))
;;
;; Both middlewares guard their in-branch on the frame's serialization and pass
;; anything else through, and both short-circuit their out-branch when
;; :kabel/serialization is already set. So stacking them yields a peer that
;; READS both formats and WRITES whichever is outermost. No new code needed;
;; the cost is two extra channels and two go-loops per connection.
;; ---------------------------------------------------------------------------

(defn dual-read-cbor-write
  "Reads frames 13 and 14; writes 14. **Step 2** of the rollout — deploy only
  once no peer predates `dual-read-fressian-write`."
  [peer-config]
  (cbor (fressian peer-config)))

(defn dual-read-fressian-write
  "Reads frames 13 and 14; writes 13. **Step 1** of the rollout — safe to
  deploy anywhere, because it does not change the wire."
  [peer-config]
  (fressian (cbor peer-config)))
