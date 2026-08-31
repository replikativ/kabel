# kabel-protocol

`kabel-protocol` is the small language-neutral part of Kabel for Python. It
implements the durable four-byte serializer prefix, serializer `14` CBOR,
Boring's standard keyword/set tags, bounded frames, and an optional asyncio
WebSocket adapter.

```python
from kabel_protocol import encode_cbor_frame, decode_cbor_frame

wire = encode_cbor_frame({"type": "ping", "n": 7})
assert decode_cbor_frame(wire) == {"type": "ping", "n": 7}
```

Install WebSocket support with `kabel-protocol[websocket]`. The package does
not implement Kabel's Clojure pub/sub strategy objects: applications exchange
ordinary CBOR values, while protocols such as Netz define portable session and
sync semantics above this carrier.

The Python package follows Kabel's repository license, EPL-1.0.
