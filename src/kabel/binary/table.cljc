(ns kabel.binary.table
  "The frame encoding table, shared by the JVM and ClojureScript sides of
  `kabel.binary`.

  This lives in its own `.cljc` because `kabel.binary` is a platform-split ns
  (`binary.clj` + `binary.cljs`) and the table was previously DUPLICATED in
  both, with nothing keeping them in step. Two codecs disagreeing about what an
  id means is a silent wire corruption, so the table has exactly one home.

  **This is durable wire state.** The id is written into every frame and peers
  do not negotiate, so:

  - an id's meaning must never change;
  - a new codec takes the next free id, never a recycled one;
  - a peer that only knows the older ids must keep working.")

(def encoding-table
  "Serialization keyword -> the int written into every frame header."
  {:binary          0
   :string          1
   :pr-str          2
   :transit-json    11
   :transit-msgpack 12
   :fressian        13
   ;; CBOR. Named for the FORMAT, like every other entry here -- a frame id
   ;; declares what the bytes ARE, not which library produced them. (The
   ;; implementation is org.replikativ/boring.) Strictly additive: 13 keeps its
   ;; slot forever, and `decoding-for` makes an unknown id a loud error rather
   ;; than a silent pass-through. See kabel.middleware.cbor.
   :cbor            14})

(def decoding-table (into {} (map (fn [[k v]] [v k])) encoding-table))

(defn decoding-for
  "The serialization keyword for a frame header's `id`, or throw.

  An unknown id used to yield nil, and nil then flowed onward: every
  serialization middleware guards its in-branch on a match, so the frame fell
  through all of them and the RAW payload map reached application code as if it
  were a decoded value. That is silent corruption, and it is the failure a peer
  hits when it meets a codec added after it was built.

  Failing loudly costs a dead connection instead. That is strictly better: a
  connection that stops with a typed error naming the id is diagnosable, and a
  peer quietly acting on undecoded bytes is not.

  Note this does NOT remove the need to deploy read support before anyone
  writes a new id -- it only converts the symptom from silence to an error.
  Removing the need is what capability negotiation would do."
  [id]
  (or (get decoding-table id)
      (throw (ex-info "kabel: unknown serialization id in frame header"
                      {:type :kabel/unknown-serialization-id
                       :id id
                       :known (set (keys decoding-table))}))))

(defn encoding-for
  "The int for `serialization`, or throw.

  Previously an unknown keyword diverged by platform: the JVM threw an NPE from
  `(int nil)`, while ClojureScript silently wrote 0 -- which is `:binary` --
  because `Uint8Array` coerces nil to 0. Producing a valid frame for the wrong
  codec is the worse of the two failures, so both platforms now fail the same
  way, loudly."
  [serialization]
  (or (get encoding-table serialization)
      (throw (ex-info "kabel: unknown serialization"
                      {:type :kabel/unknown-serialization
                       :serialization serialization
                       :known (set (keys encoding-table))}))))
