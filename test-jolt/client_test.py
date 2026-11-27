"""Run from the Kabel checkout with the interop/python websocket extra installed."""
import asyncio
import os

from kabel_protocol import decode_cbor_frame, encode_cbor_frame
from websockets.asyncio.server import serve


async def echo(socket):
    request = await socket.recv()
    assert decode_cbor_frame(request) == {"n": 7}
    reply = encode_cbor_frame({"n": 8})
    await socket.send([reply[:3], reply[3:]])
    await socket.wait_closed()


async def main():
    async with serve(echo, "127.0.0.1", 0) as server:
        port = server.sockets[0].getsockname()[1]
        proc = await asyncio.create_subprocess_exec(
            os.environ.get("JOLT", "jolt"), "-M:jolt-client", "test-jolt/client.clj",
            f"ws://127.0.0.1:{port}",
        )
        try:
            status = await asyncio.wait_for(proc.wait(), 120)
            if status:
                raise SystemExit(status)
        finally:
            if proc.returncode is None:
                proc.kill()
                await proc.wait()


if __name__ == "__main__":
    asyncio.run(main())
