import asyncio
import unittest

import cbor2

from kabel_protocol import (
    CBOR_SERIALIZER_ID,
    MAX_FRAME_BYTES,
    KabelProtocolError,
    Keyword,
    decode_cbor_frame,
    decode_envelope,
    dumps,
    encode_cbor_frame,
    encode_envelope,
    loads,
)
from kabel_protocol.websocket import KabelWebSocket


class FakeWebSocket:
    def __init__(self):
        self.sent = []
        self.incoming = []
        self.closed = False

    async def send(self, value):
        self.sent.append(value)

    async def recv(self):
        return self.incoming.pop(0)

    async def close(self):
        self.closed = True


class KabelProtocolTest(unittest.TestCase):
    def test_serializer_14_frame_known_answer(self):
        value = {"n": 7}
        self.assertEqual("0000000ea1616e07", encode_cbor_frame(value).hex())
        self.assertEqual(value, decode_cbor_frame(bytes.fromhex("0000000ea1616e07")))

    def test_boring_standard_tags(self):
        self.assertEqual("d827643a666f6f", dumps(Keyword(":foo")).hex())
        self.assertEqual(Keyword(":foo"), loads(bytes.fromhex("d827643a666f6f")))
        self.assertEqual({1, 2}, loads(bytes.fromhex("d90102820102")))
        self.assertEqual(bytes.fromhex("d90102820102"), dumps({1, 2}))

    def test_unknown_tags_are_preserved(self):
        tagged = loads(cbor2.dumps(cbor2.CBORTag(60000, {"x": 1})))
        self.assertIsInstance(tagged, cbor2.CBORTag)
        self.assertEqual(60000, tagged.tag)

    def test_envelope_is_strict_and_bounded(self):
        envelope = decode_envelope(encode_envelope(CBOR_SERIALIZER_ID, b"abc"))
        self.assertEqual(CBOR_SERIALIZER_ID, envelope.serializer_id)
        self.assertEqual(b"abc", envelope.payload)
        with self.assertRaises(KabelProtocolError):
            decode_envelope(b"\x00\x00\x00")
        with self.assertRaises(KabelProtocolError):
            decode_envelope(bytes.fromhex("00000063"))
        with self.assertRaises(KabelProtocolError):
            encode_envelope(CBOR_SERIALIZER_ID, bytes(MAX_FRAME_BYTES))

    def test_cbor_decoder_rejects_another_serializer(self):
        with self.assertRaises(KabelProtocolError):
            decode_cbor_frame(encode_envelope(0, b"anything"))

    def test_async_websocket_adapter_preserves_message_boundaries(self):
        async def scenario():
            socket = FakeWebSocket()
            client = KabelWebSocket(socket)
            await client.send({"type": "ping", "n": 7})
            self.assertEqual("0000000ea264747970656470696e67616e07",
                             socket.sent[0].hex())
            socket.incoming.append(socket.sent[0])
            self.assertEqual({"type": "ping", "n": 7}, await client.receive())
            await client.close()
            self.assertTrue(socket.closed)

        asyncio.run(scenario())


if __name__ == "__main__":
    unittest.main()
