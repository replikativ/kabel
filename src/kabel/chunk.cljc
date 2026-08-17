(ns kabel.chunk
  "Large values as a manifest plus content-addressed pieces.

  ## Why this needs no new protocol

  A chunked value is a **manifest node** naming **piece nodes**, and both are
  ordinary content-addressed blocks. So `kabel.content` transfers them with the
  machinery it already has: fetch the manifest, walk to the pieces, verify each
  against its own hash, assemble. `:content/want-tree` streams the whole thing
  in one exchange because the manifest lists its pieces under `:addresses`,
  which is exactly what the tree walk already follows.

  That is deliberate, and it is how BitTorrent v2 and IPFS's UnixFS work too: a
  large file is a DAG whose leaves are chunks, not a second transfer protocol
  bolted alongside the first. Adding a parallel `:content/piece` message family
  would have doubled the surface — and the bounds, and the verification paths —
  for no gain.

  ## What chunking buys

  A value moves whole today, so a multi-megabyte blob is one frame. kabel's own
  client raises `incomingBufferSize` to 100 MB and then guards a
  `*max-buffer-size*` on top of it, which is the shape of a limit nobody wants
  to be near. Chunking turns one unbounded frame into many bounded ones, and —
  because pieces are content-addressed — makes a partially transferred value
  *resumable* and *deduplicated*: two values sharing a run of bytes share the
  pieces.

  ## What it does not do yet

  Rarest-first piece selection, endgame mode and per-piece choking are
  BitTorrent's answers to fetching one file from *many* peers at once. They
  need a swarm to be worth anything, and the swarm is what this network does not
  have yet. The pieces are ordinary blocks, so those policies can be added in
  `kabel.content` later without touching this representation."
  (:require [hasch.core :refer [uuid]]
            [kabel.identity :as id]))

(def ^:const chunked-key :kabel.chunk/chunked)

(def default-opts
  {;; 64 KiB. Small enough that a frame is unremarkable, large enough that the
   ;; per-piece hash and manifest entry are noise against the payload.
   :chunk-size 65536
   ;; A manifest is itself a block, and an attacker-supplied one must not be
   ;; able to name unbounded work. 16 384 pieces at 64 KiB is 1 GiB.
   :max-pieces 16384})

(defn chunked?
  "Is `v` a chunk manifest?"
  [v]
  (and (map? v) (true? (get v chunked-key))))

(defn piece-keys
  "Ordered piece addresses of a manifest. Also its `:addresses`, which is what
  makes the existing tree walk stream a chunked value without knowing it is
  one."
  [manifest]
  (vec (:addresses manifest)))

(defn split
  "Split `bytes` into a manifest and its pieces.

  Returns `{:key <manifest key> :manifest m :pieces {key bytes}}`.

  Identical pieces collapse to one entry — the addresses are content hashes, so
  a repeated run of bytes is stored and transferred once while still appearing
  at every position it occupies in `:addresses`."
  ([bytes] (split bytes {}))
  ([bytes opts]
   (let [{:keys [chunk-size max-pieces]} (merge default-opts opts)
         total (id/buf-length bytes)
         ;; Integer ceiling division. `Math/ceil` over doubles would work on
         ;; the JVM and needs `js/Math` in ClojureScript, and floating point has
         ;; no business deciding how many pieces a value has.
         n (quot (+ total chunk-size -1) chunk-size)]
     (when (> n max-pieces)
       (throw (ex-info "value would exceed :max-pieces"
                       {:type :kabel.chunk/too-many-pieces
                        :pieces n :max max-pieces :size total})))
     (let [parts (for [i (range n)]
                   (id/sub-buf bytes (* i chunk-size)
                               (min total (* (inc i) chunk-size))))
           keyed (mapv (fn [p] [(uuid p) p]) parts)
           manifest {chunked-key true
                     :size total
                     :chunk-size chunk-size
                     :addresses (mapv first keyed)}]
       {:key (uuid manifest)
        :manifest manifest
        :pieces (into {} keyed)}))))

(defn missing-pieces
  "Piece addresses named by `manifest` that `held` does not contain.

  `held` is any predicate or set. This is what turns a partial transfer into a
  resumable one: the manifest is small and arrives first, so a fetcher knows
  exactly what it still needs."
  [manifest held]
  (vec (distinct (remove held (piece-keys manifest)))))

(defn assemble
  "Reassemble the bytes a manifest describes from `pieces`.

  Returns the byte buffer, or throws. Every failure mode is checked rather than
  producing a plausible-looking wrong answer:

  - a missing piece;
  - a piece whose bytes do not hash to the address the manifest gave, because
    the pieces come from strangers;
  - a total length that disagrees with the manifest, which catches a truncated
    or padded final piece that individually verified."
  [manifest pieces]
  (when-not (chunked? manifest)
    (throw (ex-info "not a chunk manifest"
                    {:type :kabel.chunk/not-a-manifest})))
  (let [ks (piece-keys manifest)
        parts (mapv (fn [k]
                      (let [p (get pieces k)]
                        (when (nil? p)
                          (throw (ex-info "missing piece"
                                          {:type :kabel.chunk/missing-piece :key k})))
                        (when-not (= k (uuid p))
                          (throw (ex-info "piece does not match its address"
                                          {:type :kabel.chunk/piece-mismatch :key k})))
                        p))
                    ks)
        out (apply id/concat-bufs parts)]
    (when-not (= (id/buf-length out) (:size manifest))
      (throw (ex-info "assembled size disagrees with the manifest"
                      {:type :kabel.chunk/size-mismatch
                       :expected (:size manifest)
                       :actual (id/buf-length out)})))
    out))

(defn blocks
  "Manifest and pieces as one `{key value}` map, ready to hand to
  `kabel.content` as ordinary content-addressed blocks."
  [{:keys [key manifest pieces]}]
  (assoc pieces key manifest))
