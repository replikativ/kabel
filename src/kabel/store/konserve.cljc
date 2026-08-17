(ns kabel.store.konserve
  "konserve-backed `PPeerStore`.

  Durable overlay state — the keypair, the address book, provider records —
  wherever kabel runs, since konserve reaches the JVM, Node and the browser
  (IndexedDB). A browser peer therefore keeps its identity across reloads,
  which is the difference between a peer and a visitor.

  konserve is **not** a dependency of kabel's base library; this namespace
  lives behind the `:konserve` alias, mirroring how `kabel.auth.store.datahike`
  treats datahike. See `kabel.store.protocol` for the argument.

  The peer store is deliberately a *small* namespace inside whatever konserve
  store you hand it: keys are the fixed vocabulary from the protocol, not
  arbitrary application data. Pass a `:prefix` if the store is shared with
  something else."
  (:require [kabel.store.protocol :as p :refer [PPeerStore]]
            [konserve.core :as k]
            #?(:clj [clojure.core.async :as async :refer [chan put! close! go <!]]
               :cljs [clojure.core.async :as async
                      :refer [chan put! close! <!] :refer-macros [go]])))

(defn- scoped
  "Namespace a protocol key so a shared konserve store stays legible."
  [prefix k]
  (if prefix [prefix k] [:kabel.overlay k]))

(defrecord KonservePeerStore [store prefix]
  PPeerStore

  (-load [_ k]
    (go (<! (k/get-in store (scoped prefix k) nil {:sync? false}))))

  (-store! [_ k v]
    (go
      (<! (k/assoc-in store (scoped prefix k) v {:sync? false}))
      v))

  (-remove! [_ k]
    (go
      (<! (k/dissoc store (first (scoped prefix k)) {:sync? false}))
      true))

  (-keys* [_]
    (go
      (let [root (first (scoped prefix nil))
            m (<! (k/get store root nil {:sync? false}))]
        (set (keys m))))))

(defn new-konserve-store
  "Wrap a konserve store as a `PPeerStore`.

  `:prefix` is the top-level konserve key the overlay's state lives under, so
  a peer can share a store with an application without colliding with it.
  Defaults to `:kabel.overlay`."
  ([store] (new-konserve-store store {}))
  ([store {:keys [prefix]}]
   (->KonservePeerStore store (or prefix :kabel.overlay))))
