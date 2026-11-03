#!/usr/bin/env python3
"""Act like an iPhone's NameDrop exchange against DapTap's QUIC server (for tests).

    fake-iphone-exchange.py <host> <port> <expected SPKI hex>

Mirrors what namedrop-re-public captured from iOS: QUIC v1, ALPN h3, no SNI, requests without
:authority, POST /Hello then POST /Exchange with a binary-plist body carrying a vCard. Accepts the
server's certificate only if its public key is the expected SNAP key 1, as iOS does. Prints JSON.
"""
import asyncio
import json
import plistlib
import ssl
import sys

from aioquic.asyncio import connect
from aioquic.asyncio.protocol import QuicConnectionProtocol
from aioquic.h3.connection import H3_ALPN, H3Connection
from aioquic.h3.events import DataReceived, HeadersReceived
from aioquic.quic.configuration import QuicConfiguration
from cryptography.hazmat.primitives import serialization

VCARD = (b"BEGIN:VCARD\r\nVERSION:3.0\r\nN:Appleseed;Jane;;;\r\nFN:Jane Appleseed\r\n"
         b"TEL;type=CELL:+1 408 555 0100\r\nEMAIL:jane@example.com\r\nEND:VCARD\r\n")


class Client(QuicConnectionProtocol):
    def __init__(self, *a, **k):
        super().__init__(*a, **k)
        self.h3 = H3Connection(self._quic)
        self.waiters = {}
        self.responses = {}

    def quic_event_received(self, event):
        for ev in self.h3.handle_event(event):
            r = self.responses.setdefault(ev.stream_id, {"headers": {}, "body": b""})
            if isinstance(ev, HeadersReceived):
                r["headers"] = {k.decode(): v.decode() for k, v in ev.headers}
            elif isinstance(ev, DataReceived):
                r["body"] += ev.data
            if getattr(ev, "stream_ended", False) and ev.stream_id in self.waiters:
                self.waiters.pop(ev.stream_id).set_result(self.responses[ev.stream_id])

    async def post(self, path, body):
        sid = self._quic.get_next_available_stream_id()
        # iOS omits :authority on this path.
        self.h3.send_headers(sid, [(b":method", b"POST"), (b":scheme", b"https"), (b":path", path.encode()),
                                   (b"content-type", b"application/octet-stream")])
        self.h3.send_data(sid, body, end_stream=True)
        fut = asyncio.get_running_loop().create_future()
        self.waiters[sid] = fut
        self.transmit()
        return await asyncio.wait_for(fut, 10)


async def main(host, port, spki_hex):
    cfg = QuicConfiguration(is_client=True, alpn_protocols=H3_ALPN)
    cfg.verify_mode = ssl.CERT_NONE
    cfg.server_name = None
    out = {}
    async with connect(host, int(port), configuration=cfg, create_protocol=Client) as c:
        cert = c._quic.tls._peer_certificate
        got = cert.public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
        out["cert_key_matches_snap_key1"] = got.hex() == spki_hex.lower()
        hello = await c.post("/Hello", plistlib.dumps({"id": {"id": "11111111-2222-3333-4444-555555555555"},
                                                       "featureFlags": 3, "contextType": {"unknown": {}}},
                                                      fmt=plistlib.FMT_BINARY))
        out["hello_status"] = hello["headers"].get(":status")
        out["hello"] = plistlib.loads(hello["body"])
        ex = await c.post("/Exchange", plistlib.dumps({"TransferID": {"id": "T-1"}, "FullName": "Jane Appleseed",
                                                       "Handle": {"email": {"_0": "jane@example.com"}},
                                                       "VCardData": VCARD}, fmt=plistlib.FMT_BINARY))
        out["exchange_status"] = ex["headers"].get(":status")
        reply = plistlib.loads(ex["body"])
        reply["VCardData"] = reply.get("VCardData", b"").decode("utf-8", "replace")
        out["exchange"] = reply
    print(json.dumps(out, default=str))


if __name__ == "__main__":
    asyncio.run(main(*sys.argv[1:4]))
