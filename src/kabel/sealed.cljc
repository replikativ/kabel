(ns kabel.sealed
  "Content a provider can serve, verify and relay — but not read.

  ## The problem this solves

  AT Protocol has no private record store, so mutes, bookmarks and preferences
  could not live in a user's repo. They were bolted onto a separate centralised
  service (`bsync`) instead — a decentralised system with a centralised sidecar
  for exactly the state that is most personal. That is the shortcut worth not
  taking, and the reason to have this before an application needs it.

  ## The finding: no new protocol is required

  A sealed block is an **ordinary content-addressed block** whose value happens
  to be `{:children [...] :ciphertext ...}`. Everything already built works on
  it unchanged:

  - its address is `hasch/uuid` of the whole block, so `kabel.content`'s
    verification is untouched — a provider still cannot forge it;
  - its children are in the clear, so `:content/want-tree` walks and streams a
    sealed DAG exactly as it does a plain one;
  - the address is *unguessable* without the key, because the ciphertext
    depends on it. Someone who knows the plaintext still cannot compute where
    it lives.

  This is Tahoe-LAFS's **verify-cap tier**: a holder can check integrity,
  repair and re-serve content it has no ability to decrypt. Notably Tahoe
  designed a deep-verify/traversal cap for the same purpose and never shipped
  it, so the walking half is the part usually missing.

  ## What it leaks, stated plainly

  **The shape of the DAG.** Child addresses are visible by construction — that
  is what lets a provider walk and serve. So sizes, fan-out, depth and access
  patterns are all observable; only the *contents* are protected.
  `CONTENT_ADDRESSING_AND_CAPS.md` says the same: do not expect opaque storage
  indices to hide structure. If the shape itself is sensitive, this is the
  wrong tool.

  ## What is deliberately not here

  Encryption. Sealing a value is konserve's job — it owns serialisation, the
  cipher and the capability that derives the key. This namespace is only the
  *shape* that makes sealed content transferable, and it holds no key material
  and performs no cryptography beyond the content hash everything else uses."
  (:require [hasch.core :refer [uuid]]))

(def ^:const sealed-key :kabel.sealed/sealed)

(defn make-sealed
  "A sealed block: child addresses in the clear, payload opaque.

  `children` must be the addresses of the sealed block's children *as stored*,
  because a provider walks them without being able to read anything else."
  [children ciphertext]
  {sealed-key true
   :addresses (vec children)
   :ciphertext ciphertext})

(defn sealed?
  [v]
  (and (map? v) (true? (get v sealed-key))))

(defn children
  "Child addresses of a sealed block — the `:addresses-fn` view.

  Deliberately the same `:addresses` key a plain node uses, so the default
  projector in `kabel.content` walks sealed and unsealed nodes alike and no
  caller has to know which it has."
  [v]
  (when (sealed? v) (vec (:addresses v))))

(defn ciphertext
  "The opaque payload. Meaningless without the key, which is the point."
  [v]
  (when (sealed? v) (:ciphertext v)))

(defn address
  "Where this block lives.

  The content address of the whole block, ciphertext included — so it is
  verifiable by anyone and computable only by someone who already holds the
  block or the key that produced it. Knowing the plaintext does not reveal it,
  which is what closes the confirmation-of-file leak that a
  `hash(plaintext)` index would open."
  [v]
  (uuid v))
