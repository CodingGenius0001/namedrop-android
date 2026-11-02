#!/usr/bin/env python3
"""Fetch and decrypt the tap log a phone uploads in DapTap's Testing mode.

    scripts/read-test-log.py <testing code>            # newest upload
    scripts/read-test-log.py <testing code> --all      # every upload still on the server
    scripts/read-test-log.py <testing code> --file f   # decode a saved ntfy JSON stream instead

The code (Settings > Diagnostics > Testing code) is `<topic id>.<key>`. Each upload is a few ntfy
messages: base64url(iv[12] || AES-256-GCM(uploadId[8] || index || total || gzip slice) || tag), AAD "daptap1".
"""
import argparse
import base64
import gzip
import json
import sys
import urllib.request

from cryptography.hazmat.primitives.ciphers.aead import AESGCM


def b64url(s):
    return base64.urlsafe_b64decode(s + "=" * (-len(s) % 4))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("code")
    ap.add_argument("--all", action="store_true")
    ap.add_argument("--file")
    ap.add_argument("--base", default="https://ntfy.sh/")
    a = ap.parse_args()

    topic_id, _, key = a.code.strip().partition(".")
    aes = AESGCM(b64url(key))
    if a.file:
        lines = open(a.file).read().splitlines()
    else:
        url = f"{a.base.rstrip('/')}/daptap-log-{topic_id}/json?poll=1&since=all"
        with urllib.request.urlopen(url, timeout=20) as r:
            lines = r.read().decode().splitlines()

    uploads = {}  # uploadId -> (time, total, {index: bytes})
    order = []
    for line in lines:
        m = json.loads(line)
        if m.get("event") != "message":
            continue
        try:
            raw = b64url(m["message"])
            plain = aes.decrypt(raw[:12], raw[12:], b"daptap1")
        except Exception:
            continue
        uid, idx, total, data = plain[:8], plain[8], plain[9], plain[10:]
        if uid not in uploads:
            uploads[uid] = (m.get("time", 0), total, {})
            order.append(uid)
        uploads[uid][2][idx] = data

    done = [u for u in order if len(uploads[u][2]) == uploads[u][1]]
    if not done:
        sys.exit("no complete upload yet")
    for uid in (done if a.all else done[-1:]):
        _, total, parts = uploads[uid]
        print(gzip.decompress(b"".join(parts[i] for i in range(total))).decode())
        if a.all:
            print("-" * 60)


if __name__ == "__main__":
    main()
