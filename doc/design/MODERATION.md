# Moderation: what this system can and cannot do

**Read this before deploying anything on the overlay to people.** It is not a
feature list. Most of it is a list of things that are impossible here, and the
impossibility is structural rather than unfinished.

Written after reading Mastodon's and Synapse's moderation code, AT Protocol's
labeler design, and the documented failures of all three
(`.internal/reference/moderation.md`).

---

## 1. The one fact everything follows from

**An identity costs one key generation.**

A peer id is the hash of a genesis record that anyone can produce, offline, in
microseconds, without asking anybody. There is no account, no issuer, no
registrar, no domain, no email, no payment, no invitation. That is the point of
a self-certifying identity, and it is also the end of every moderation
technique that depends on identity being scarce.

Mastodon can suspend an account because a server issued it. Matrix can deny a
server because DNS names cost money and reputation. Bluesky can take down 66 000
accounts because it operates the AppView. **We can do none of these**, and no
amount of protocol work changes it.

---

## 2. What is foreclosed

Stated plainly, so nobody discovers it during an incident:

| capability | status | why |
|---|---|---|
| **Suspension** | impossible | no issuer, so nothing to revoke |
| **Ban-evasion resistance** | impossible | a new identity costs a keygen |
| **Deletion** | impossible | content is addressed by hash and held by whoever fetched it |
| **Network-wide block** | impossible | no global view, and no one may compel a peer |
| **Appeals** | out of protocol | there is no authority to appeal *to* |
| **Shadowbanning** | ineffective | the target can run a peer and observe directly |
| **Reliable reporting** | partial | you can publish a report; nobody must read it |
| **CSAM hash matching** | not provided | see §6 — and note Mastodon ships none either |

A system that needs any of these needs an authority, and an authority needs
somewhere to stand. If that is a requirement, the honest answer is a server —
not this overlay with a moderation layer bolted on.

---

## 3. What actually works: two surfaces

### Grant side — real prevention, where a range has an owner

`:authorize` runs at **every hop** and receives the verified origin peer id, the
topic, and the payload. A publish into a range whose owner refuses it does not
propagate: it is dropped at the first honest peer that sees it, not merely at
its destination.

This is genuine enforcement, and it is stronger than the equivalent hook in any
of the three systems reviewed — Mastodon's inbox check is one hop, Synapse's
server ACL is enforced on inbound and *not* outbound, and atproto has no
per-hop check at all because there are no hops.

It works **only where a range has an owner**. A room, a knowledge base, a
database: someone decides who may write, and the answer is enforceable.

### Receiver side — advisory labels, everywhere else

For open ranges there is no authority, so there is no prevention. What remains
is judgment that other people can choose to honour:

- anyone may publish a signed **label** about a subject;
- a client **subscribes** to the labelers it trusts;
- a label becomes an action **only because the receiving client decided it
  should**.

Copied deliberately from AT Protocol, including the part that looks like a
weakness: **a labeler cannot take anything down.** Only a client that has
privileged a labeler promotes a label into enforcement, and only for itself.

---

## 4. The division that matters

> **Owned range → grants → prevention.**
> **Open range → labels → advice.**

Choose this per range, deliberately, at design time. The failure mode is
building an open range and later wishing it had an owner, because retrofitting
an authority onto content that has already propagated is not possible.

For simmis: rooms, KBs and databases have owners, so they get real moderation. A
public discovery layer, if one ever exists, would not.

---

## 5. Rate limiting, and why it is keyed on the connection

Token buckets keyed on identity are theatre when an identity is free. Synapse's
`rc_federation` has the right shape — **sleep, then queue, then reject**, keyed
on the *connection* — because a connection costs a socket, a handshake and an
address, and those are not free.

This bounds resource abuse. It does not bound *speech*: a determined party with
many addresses gets many connections. Group-diversity caps in the address book
raise that cost, and do not eliminate it.

---

## 6. What we do not provide, and will not pretend to

**No CSAM detection.** Mastodon ships none either — no perceptual hashing, no
matching, no classifier, verified by reading the tree. IFTAS, the fediverse's
only shared hash-matching service, **shut down in 2025 with zero committed
funding**, and Stanford's Internet Observatory found 112 known-CSAM matches in a
two-day scan of 25 instances.

An operator running a relay with a broad `:carries` range is caching and
serving other people's content. That is a legal position worth understanding
before taking it, and narrowing the range is the mechanism that limits it.

**No trust or reputation scoring.** Deliberately: a formal analysis of
gossipsub's scoring (IEEE S&P 2024) synthesised an attack in which misbehaving
peers never forward messages yet keep positive scores and are never pruned —
under Eth2.0's parameters, for any topology and size. The mechanism is not the
security property; the parameters are, and they are adversarially fragile. A
subscribed labeler is a policy hook with the same purpose and none of that
fragility.

---

## 7. Operational reality

The three systems reviewed all fail here, and none of it is a protocol problem:

- volunteer admins carry the burden and burn out;
- block lists get used as weapons — The Bad Space erroneously listed
  `tech.lgbt` and `girlcock.club`, had no appeals path, and its maintainer was
  harassed off the project;
- `#fediblock` is documented as "misused for personal disputes";
- 800+ servers pre-emptively defederated Threads on announcement.

Labels here will be used the same way. The design's answer is that **labels are
subscriptions**: a bad labeler loses its audience rather than its power, because
it never had power. That is weaker than a ban and more robust than one.

---

## 8. If you need more than this

Then you need a server, and that is a legitimate answer. The overlay is
designed so a peer with a broad `:carries` range and real hardware is an
ordinary participant — which means a moderated, curated, centrally operated
service can exist *inside* this network without the network pretending to
provide what only that service can.
