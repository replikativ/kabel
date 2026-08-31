# Kabel binary carrier profile 1

Status: draft. This document specifies the part of Kabel that a non-Clojure
implementation needs. Pub/sub, authentication, and synchronization are
protocols above this carrier.

Each ordered WebSocket binary message contains exactly one Kabel frame:

```text
0                   1                   2                   3
0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                 serializer id (uint32be)                      |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                         payload ...                           |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
```

There is no inner length prefix: the WebSocket message boundary is the frame
boundary. A byte-stream carrier would need its own outer framing. Frames are at
most 5 MiB including the four-byte header. Receivers reject unknown serializer
ids and malformed payloads by closing the connection; they never pass undecoded
bytes to application code.

The durable serializer registry is:

| id | payload |
|---:|---|
| 0 | opaque binary/container |
| 1 | UTF-8 string |
| 2 | Clojure EDN (`pr-str`) |
| 11 | Transit JSON |
| 12 | Transit MessagePack |
| 13 | Fressian |
| 14 | CBOR profile below |

An assigned id never changes meaning or gets reused. New ids require a registry
update and a read-before-write deployment unless a higher protocol negotiates
capabilities.

## Serializer 14: CBOR

Serializer 14 is RFC 8949 CBOR. Indefinite values may be read, but writers emit
ordinary definite values. Boring string-reference tags 25/256 are not emitted.
The profile uses the standard registered tags already used by Boring:

- tag 39: a Clojure keyword represented by a text string including `:`;
- tag 258: a set represented by an array;
- tag 27: a language-neutral record/type representation, whose semantics are
  application registered.

Unknown CBOR tags are preserved for the application rather than discarded.
Signed protocol objects define their own deterministic encoding; ordinary
Kabel application frames do not require canonical CBOR.

The known-answer frame for `{"n": 7}` is:

```text
0000000e a1616e07
```

where `0000000e` is serializer id 14 and `a1616e07` is the CBOR payload.
