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
   ;; boring (CBOR). Strictly additive: 13 keeps its slot forever. A peer that
   ;; does not know 14 will NOT error on it -- `decoding-table` returns nil and
   ;; the raw payload is passed through -- so both ends must understand 14
   ;; before either starts writing it. See kabel.middleware.boring.
   :boring          14})

(def decoding-table (into {} (map (fn [[k v]] [v k])) encoding-table))

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
