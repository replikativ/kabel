"""The durable Kabel frame header and interoperable Boring/CBOR subset."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any

import cbor2

BINARY_SERIALIZER_ID = 0
CBOR_SERIALIZER_ID = 14
HEADER_BYTES = 4
MAX_FRAME_BYTES = 5 * 1024 * 1024
KEYWORD_TAG = 39
SET_TAG = 258
KNOWN_SERIALIZER_IDS = frozenset({0, 1, 2, 11, 12, 13, 14})


class KabelProtocolError(ValueError):
    """A frame or CBOR value violates the portable Kabel boundary."""


@dataclass(frozen=True, order=True)
class Keyword:
    """A Clojure keyword carried with IANA CBOR tag 39.

    ``text`` includes the leading colon, for example ``":netz/message"``.
    """

    text: str

    def __post_init__(self) -> None:
        if (not isinstance(self.text, str) or len(self.text) < 2
                or not self.text.startswith(":")):
            raise KabelProtocolError("keyword text must start with ':'")

    def __str__(self) -> str:
        return self.text


@dataclass(frozen=True)
class Envelope:
    serializer_id: int
    payload: bytes


def _encode_default(encoder: cbor2.CBOREncoder, value: object) -> None:
    if isinstance(value, Keyword):
        encoder.encode(cbor2.CBORTag(KEYWORD_TAG, value.text))
        return
    raise cbor2.CBOREncodeTypeError(
        f"cannot encode {type(value).__name__} in the Kabel CBOR profile"
    )


def _tag_hook(_decoder: cbor2.CBORDecoder, tag: cbor2.CBORTag) -> object:
    if tag.tag == KEYWORD_TAG:
        if not isinstance(tag.value, str) or not tag.value.startswith(":"):
            raise KabelProtocolError("malformed CBOR keyword tag")
        return Keyword(tag.value)
    # cbor2 natively decodes standard set tag 258. Preserve every other tag so
    # applications can register semantics above this generic carrier.
    return tag


def dumps(value: object) -> bytes:
    """Encode one Kabel CBOR value.

    String references are intentionally absent. They are a Boring extension,
    not part of the interoperable Kabel profile.
    """
    try:
        return cbor2.dumps(value, default=_encode_default)
    except (KabelProtocolError, cbor2.CBORError):
        raise
    except Exception as error:
        raise KabelProtocolError("cannot encode Kabel CBOR value") from error


def loads(payload: bytes) -> Any:
    if not isinstance(payload, bytes):
        raise KabelProtocolError("CBOR payload must be bytes")
    try:
        return cbor2.loads(payload, tag_hook=_tag_hook)
    except KabelProtocolError:
        raise
    except Exception as error:
        raise KabelProtocolError("cannot decode Kabel CBOR value") from error


def encode_envelope(serializer_id: int, payload: bytes) -> bytes:
    if (type(serializer_id) is not int
            or not 0 <= serializer_id <= 0xFFFFFFFF):
        raise KabelProtocolError("serializer id must be an unsigned 32-bit integer")
    if serializer_id not in KNOWN_SERIALIZER_IDS:
        raise KabelProtocolError(f"unknown Kabel serializer id {serializer_id}")
    if not isinstance(payload, bytes):
        raise KabelProtocolError("Kabel payload must be bytes")
    frame = serializer_id.to_bytes(HEADER_BYTES, "big") + payload
    if len(frame) > MAX_FRAME_BYTES:
        raise KabelProtocolError("Kabel frame exceeds 5 MiB")
    return frame


def decode_envelope(frame: bytes) -> Envelope:
    if (not isinstance(frame, bytes)
            or not HEADER_BYTES <= len(frame) <= MAX_FRAME_BYTES):
        raise KabelProtocolError("invalid Kabel frame length")
    serializer_id = int.from_bytes(frame[:HEADER_BYTES], "big")
    if serializer_id not in KNOWN_SERIALIZER_IDS:
        raise KabelProtocolError(f"unknown Kabel serializer id {serializer_id}")
    return Envelope(serializer_id, frame[HEADER_BYTES:])


def encode_cbor_frame(value: object) -> bytes:
    return encode_envelope(CBOR_SERIALIZER_ID, dumps(value))


def decode_cbor_frame(frame: bytes) -> Any:
    envelope = decode_envelope(frame)
    if envelope.serializer_id != CBOR_SERIALIZER_ID:
        raise KabelProtocolError(
            f"expected CBOR serializer 14, got {envelope.serializer_id}"
        )
    return loads(envelope.payload)
