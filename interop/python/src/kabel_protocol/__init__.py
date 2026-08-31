"""Portable Kabel binary framing and CBOR helpers."""

from .codec import (
    BINARY_SERIALIZER_ID,
    CBOR_SERIALIZER_ID,
    MAX_FRAME_BYTES,
    Envelope,
    KabelProtocolError,
    Keyword,
    decode_cbor_frame,
    decode_envelope,
    dumps,
    encode_cbor_frame,
    encode_envelope,
    loads,
)

__all__ = [
    "BINARY_SERIALIZER_ID",
    "CBOR_SERIALIZER_ID",
    "MAX_FRAME_BYTES",
    "Envelope",
    "KabelProtocolError",
    "Keyword",
    "decode_cbor_frame",
    "decode_envelope",
    "dumps",
    "encode_cbor_frame",
    "encode_envelope",
    "loads",
]
