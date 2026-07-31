(ns kabel.middleware.cbor
  "CBOR serialization middleware for kabel, implemented with org.replikativ/boring.

  Named for the FORMAT, like `kabel.middleware.fressian` and
  `kabel.middleware.transit`: what goes on the wire is CBOR, and which library
  writes it is an implementation detail a peer never sees.

  Frame id **14**, strictly additive to `:fressian` 13 — see
  `kabel.binary.table`.

  Shaped on `kabel.middleware.fressian`, with the same arities so a swap is
  mechanical. Three things genuinely differ, and none of them are accidents:

  **One registry, not two handler maps.** boring's registry is a single
  immutable value carrying both directions, so there is no read/write split and
  no `associative-lookup`/`inheritance-lookup` plumbing. The fressian module
  needs eight reader conditionals for its two platforms; this one needs a
  single conditional in the ns form, for `go-try`, and none in the body — that
  is the concrete form of \"one codec instead of fressian-JVM plus fress-CLJS\".

  **Write handlers are accepted and ignored.** boring emits a record's type
  name natively via CBOR tag 27 — the problem incognito exists to work around
  for fressian — so nothing needs teaching on the way out. They are accepted
  rather than rejected because konserve's clj-cbor serializer REJECTED handlers,
  and that is exactly why it was unusable for anything but plain data.

  **Incognito read handlers fold straight in.** incognito keys them by
  `(-> r type pr-str normalize-ns symbol)` — the type name with `/` → `.` and
  `-` → `_` — which is precisely boring's own record wire name. So the bridge is
  a rename, not a translation.

  ## Rollout

  A peer that does not know id 14 does not decode it as CBOR; since
  `kabel.binary.table/decoding-for` was made strict it fails loudly rather than
  handing the raw payload to application code, but it still cannot read the
  frame. Migrating a deployment that cannot upgrade both ends at once therefore
  needs the dual-format composition in `kabel.middleware.dual`.

  That composition lives in its own namespace ON PURPOSE. Putting it here made
  this namespace require `kabel.middleware.fressian`, which drags fressian --
  and on ClojureScript the whole of `fress` -- into the bundle of every consumer
  that only ever wanted CBOR. A codec namespace should cost what its codec
  costs."
  (:require [boring.core :as boring]
            [kabel.middleware.handler :refer [handler]]
            #?(:clj [superv.async :refer [go-try]]))
  #?(:cljs (:require-macros [superv.async :refer [go-try]]
                            [clojure.core.async :refer [go]])))

(def ^:const serialization-key :cbor)

(defn record-registry
  "Fold incognito-style record handlers into `registry`. Returns a NEW registry.

  Only the READ direction needs anything: boring carries the type name on the
  wire itself, and an unregistered record still decodes to an inert value with
  the same name and fields rather than being lost."
  [registry handlers]
  (if (seq handlers)
    (reduce-kv (fn [reg tag ctor] (boring/register-record reg (str tag) ctor))
               registry handlers)
    registry))

(defn- registry-cache
  "Memoise `record-registry` against the handler atom's current value.

  `TagRegistry.withRecord` copies the whole backing map, so folding N handlers
  on every frame would be N map copies per message. Handler atoms change at
  most a handful of times in a connection's life, so an `identical?` check on
  the deref'd persistent map is both exact and free."
  []
  (atom {:src ::none :reg nil}))

(defn- registry-for [cache base handlers-atom]
  (let [h (if handlers-atom @handlers-atom {})
        c @cache]
    (if (identical? (:src c) h)
      (:reg c)
      (:reg (reset! cache {:src h :reg (record-registry base h)})))))

(defn cbor
  "Serialize all incoming and outgoing values as CBOR via boring.

  Arities mirror `kabel.middleware.fressian/fressian`:

    (cbor peer-config)
    (cbor registry-atom ignored-write-handlers peer-config)
    (cbor registry-atom ignored-write-handlers
          incognito-read-atom incognito-write-atom peer-config)

  `registry-atom` holds a boring registry VALUE (see `boring.core/tag-registry`,
  `register-tag`, `register-record`) used for both directions. The
  write-handlers argument is accepted and ignored — see the ns docstring.

  `opts` may be supplied via the registry atom's metadata under `:boring/opts`;
  they are boring encode options. Deliberately NOT defaulted:

  - `:canonical` must never be used on a wire — it does not preserve float
    width.
  - `:shapes` is off, because it is a per-message table that only helps
    homogeneous ARRAYS of maps; on nested or scattered maps it measures +0.0%.
    Turning it on globally would be cargo cult."
  ([peer-config]
   (cbor (atom (boring/tag-registry)) (atom {}) (atom {}) (atom {}) peer-config))
  ([registry-atom ignored-write-handlers peer-config]
   (cbor registry-atom ignored-write-handlers (atom {}) (atom {}) peer-config))
  ([registry-atom _ignored-write-handlers
    incognito-read-handlers-atom _incognito-write-handlers-atom
    [S peer [in out]]]
   (let [cache (registry-cache)
         opts (or (:boring/opts (meta registry-atom)) {})]
     (handler
      ;; Deserialize incoming
      #(go-try S
               (let [{:keys [kabel/serialization kabel/payload]} %]
                 (if (= serialization serialization-key)
                   (let [reg (registry-for cache @registry-atom
                                           incognito-read-handlers-atom)
                         v (boring/decode payload (assoc opts :registry reg))]
                     ;; Merge message metadata (:host and friends) back on, the
                     ;; same contract the fressian middleware has.
                     (if (map? v)
                       (merge v (dissoc % :kabel/serialization :kabel/payload))
                       v))
                   %)))

      ;; Serialize outgoing
      #(go-try S
               (if (:kabel/serialization %)   ; already serialized upstream
                 %
                 {:kabel/serialization serialization-key
                  :kabel/payload (boring/encode
                                  % (assoc opts :registry @registry-atom))}))

      [S peer [in out]]))))
