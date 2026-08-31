"""Minimal asyncio WebSocket carrier for complete Kabel binary messages."""

from __future__ import annotations

from typing import Any

from .codec import MAX_FRAME_BYTES, KabelProtocolError, decode_cbor_frame, encode_cbor_frame


class KabelWebSocket:
    def __init__(self, websocket: Any) -> None:
        self.websocket = websocket

    @classmethod
    async def connect(cls, uri: str, **options: Any) -> "KabelWebSocket":
        try:
            from websockets.asyncio.client import connect
        except ImportError as error:  # pragma: no cover - installation dependent
            raise RuntimeError(
                "WebSocket support requires 'kabel-protocol[websocket]'"
            ) from error
        websocket = await connect(uri, max_size=MAX_FRAME_BYTES, **options)
        return cls(websocket)

    async def send(self, value: object) -> None:
        await self.websocket.send(encode_cbor_frame(value))

    async def receive(self) -> object:
        frame = await self.websocket.recv()
        if not isinstance(frame, bytes):
            raise KabelProtocolError("Kabel requires a binary WebSocket message")
        return decode_cbor_frame(frame)

    async def close(self) -> None:
        await self.websocket.close()

    async def __aenter__(self) -> "KabelWebSocket":
        return self

    async def __aexit__(self, *_exc: object) -> None:
        await self.close()
