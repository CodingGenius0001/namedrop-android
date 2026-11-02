#!/usr/bin/env bash
# Creates the DapTap signing key (once) and, if gh is logged in, stores it as repo secrets so
# every CI build is signed with the same key and installs over the previous one.
set -euo pipefail
cd "$(dirname "$0")/.."
KS=app/keystore/daptap.jks
PASS="${DAPTAP_KEYSTORE_PASSWORD:-$(openssl rand -hex 16)}"
mkdir -p app/keystore
if [ ! -f "$KS" ]; then
  keytool -genkeypair -v -keystore "$KS" -alias daptap -keyalg RSA -keysize 3072 -validity 10000 \
    -storepass "$PASS" -keypass "$PASS" -dname "CN=DapTap, O=Keshav Pillarisetti"
  echo "$PASS" > app/keystore/password.txt
  chmod 600 app/keystore/password.txt
fi
PASS="$(cat app/keystore/password.txt)"
if command -v gh >/dev/null && gh auth status >/dev/null 2>&1; then
  base64 -w0 "$KS" | gh secret set DAPTAP_KEYSTORE_BASE64
  printf '%s' "$PASS" | gh secret set DAPTAP_KEYSTORE_PASSWORD
  printf '%s' "$PASS" | gh secret set DAPTAP_KEY_PASSWORD
  printf 'daptap' | gh secret set DAPTAP_KEY_ALIAS
  echo "Secrets uploaded."
fi
