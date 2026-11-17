#!/usr/bin/env python3
"""Fetch and decrypt the tap log a phone uploads in DapTap's Testing mode.

    scripts/read-test-log.py <testing code>              # everything on the server, merged into one timeline
    scripts/read-test-log.py <testing code> --hours 6    # only the last 6 hours of it
    scripts/read-test-log.py <testing code> --raw        # each upload as sent, oldest first
    scripts/read-test-log.py <testing code> --file f     # decode a saved ntfy JSON stream instead

The phone keeps 96 hours of log and re-sends all of it before ntfy's 12-hour cache forgets the last copy, so the
uploads overlap. Stored lines are `<seq>\t<yyyy-MM-dd HH:mm:ss.SSS>  <text>`; merging by sequence number removes
the overlap, and a jump in the numbers is shown as missing lines. Uploads from before 1.0.45 have no sequence
numbers and are printed as they are.

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
    ap.add_argument("--all", action="store_true", help="kept for old notes; merging already covers every upload")
    ap.add_argument("--raw", action="store_true")
    ap.add_argument("--hours", type=float)
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
    texts = [gzip.decompress(b"".join(uploads[u][2][i] for i in range(uploads[u][1]))).decode() for u in done]
    if a.raw:
        for t in texts:
            print(t)
            print("-" * 60)
        return

    merged = {}   # seq -> "date time  text"
    headers = []  # (header lines of each upload)
    old = []      # whole uploads without sequence numbers
    for t in texts:
        head, has_seq = [], False
        for line in t.splitlines():
            seq, tab, rest = line.partition("\t")
            if tab and seq.isdigit():
                merged[int(seq)] = rest
                has_seq = True
            elif not has_seq:
                head.append(line)
        if has_seq:
            headers.append(head)
        else:
            old.append(t)
    for t in old:
        print(t)
        print("-" * 60)
    if not merged:
        return
    for h in headers[-1:]:
        print("\n".join(h))
    print(f"{len(headers)} uploads merged: lines {min(merged)}-{max(merged)}")
    print("-" * 60)
    cutoff = None
    if a.hours is not None:
        import datetime
        cutoff = (datetime.datetime.now() - datetime.timedelta(hours=a.hours)).strftime("%Y-%m-%d %H:%M:%S.%f")[:23]
    prev = None
    for seq in sorted(merged):
        line = merged[seq]
        if cutoff and line[:23] < cutoff:
            continue
        if prev is not None and seq != prev + 1:
            print(f"... {seq - prev - 1} lines missing (not on the server) ...")
        print(line)
        prev = seq

if __name__ == "__main__":
    main()
