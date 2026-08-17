(ns kabel.store.memory
  "In-memory `PPeerStore`. Portable — JVM, Node and browser.

  The default, and the right choice whenever a peer may legitimately be a
  different peer after a restart: an ephemeral client, a test, a browser tab.
  A peer whose identity should persist needs a durable implementation — see
  `kabel.store.konserve` behind the `:konserve` alias."
  (:require [kabel.store.protocol :as p :refer [PPeerStore]]))

(defrecord MemoryPeerStore [state]
  PPeerStore
  (-load [_ k] (p/chan-of (get @state k)))
  (-store! [_ k v] (swap! state assoc k v) (p/chan-of v))
  (-remove! [_ k] (swap! state dissoc k) (p/chan-of true))
  (-keys* [_] (p/chan-of (set (keys @state)))))

(defn new-memory-store
  "A fresh in-memory peer store.

  Takes an optional initial map, which is how a caller supplies a keypair
  loaded from somewhere else — a config file, an environment variable, a
  browser's own storage — without needing a durable store implementation."
  ([] (new-memory-store {}))
  ([initial] (->MemoryPeerStore (atom (or initial {})))))
