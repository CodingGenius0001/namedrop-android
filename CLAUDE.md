# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

DapTap is NameDrop for Android: touch an iPhone, and the iPhone plays its own NameDrop with no app on its side. It is a single-module Kotlin/Compose app, tested on a Galaxy S26 Ultra (One UI 9, Android 17) against a real iPhone. The repo is public, so never commit testing codes, keys or anything from a decrypted tap log.

## Commands

Gradle needs JDK 17. On this laptop:

```sh
export JAVA_HOME=~/.local/opt/jdk-17.0.21+9

./gradlew :app:assembleDebug :app:testDebugUnitTest          # debug build and all unit tests
./gradlew :app:testDebugUnitTest --tests 'com.keshav.daptap.SnapTest'   # one test class
./gradlew :app:assembleRelease                               # minified, as CI builds it

(cd web && node --test test/unit.test.mjs)                   # web card unit tests
(cd web && npm run e2e)                                      # Playwright; needs npm install first
```

There is no lint task configured. `CrossLanguageTest` runs `web/test/xlang.mjs` under Node to check Kotlin and the web card agree byte for byte, and is skipped when Node isn't installed.

Read the log a phone uploaded in testing mode (the code is in *Settings › Diagnostics*; needs Python `cryptography`):

```sh
python3 scripts/read-test-log.py '<testing code>'         # add --all for every upload still on the server
```

### Emulator

The adb binary is `~/Android/Sdk/platform-tools/adb`, the AVD is `pulse_test`, and the debug package is `com.keshav.daptap.debug`. Start the emulator as a background task with `-gpu host`: swiftshader segfaults during swaps. The device's toybox `grep -i` segfaults on big logs.

`app/src/debug/.../SimulatedReader.kt` is a broadcast receiver that drives the real engine without hardware. Its extras are documented in the file. A full swap against an in-app fake iPhone on loopback:

```sh
adb shell am broadcast -n com.keshav.daptap.debug/com.keshav.daptap.SimulatedReader \
  --ez namedrop true --ez loopback true
```

The emulator has no `mosey0` and can't join Wi-Fi, so it can't prove anything about the Apple link. Only a real iPhone tap can.

## Releasing

**Pushing to `main` ships to the real phone.** CI (`.github/workflows/build.yml`) builds every branch, but on `main` it also publishes release `v1.0.N` (N is the CI run number). `pages.yml` then mirrors `DapTap.apk` and `version.json` to GitHub Pages, where the in-app updater finds them. `versionCode`/`versionName` come only from `GITHUB_RUN_NUMBER`, so local builds are `1.0.0`. `app/keystore/` is gitignored; CI restores the same key from secrets so updates install over old builds.

Don't commit `.impeccable/ref/` or `.impeccable/review/`, which are untracked on purpose.

## How a tap works

Paths are under `app/src/main/java/com/keshav/daptap/`. `DapTapApp.kt` wires everything together, and `tap/TapEngine.kt` decides what each touch means.

1. **NFC.** `nfc/TapService` (HCE) answers as a tag; `core/Snap.kt` is Apple's handshake, ported from namedrop-re-public and checked byte for byte by `SnapTest`. `nfc/NameDropBeacon` sends the polling frame on Android 17. The handshake tells the iPhone where DapTap's server is.
2. **The Apple link.** Samsung's Quick Share can raise a Wi-Fi Aware link for iPhones, `mosey0`, which exists only while its UI is up. `share/QuickShare` opens it behind a cover (`overlay/OverlayController`). `namedrop/LinkRoute` finds the link's network. `tap/SwapService` keeps DapTap in the foreground, because One UI throttles it behind Quick Share otherwise.
3. **The exchange.** The iPhone is the QUIC client. It connects to `namedrop/NameDropServer` (kwik, HTTP/3, UDP 60192) and calls `/Hello`, `/Ask` and `/Exchange`; `namedrop/Exchange` is the pure logic. Without the link, or when the other phone is Android, cards swap over NFC or fall back to Quick Share.

`mosey0` is a restricted network, so ordinary apps can't send on it. `applink/AppleLink` supervises two ways through, and `DapTapApp` hands the server whichever socket applies:

- **Direct** (`applink/DirectLink`): DapTap's uid is listed in `Settings.Global uids_allowed_on_restricted_networks`, using `WRITE_SECURE_SETTINGS` from `pm grant` during one-time setup. No debugging is needed afterwards. EPERM/EACCES falls back to the helper. A listed uid also bypasses VPN rules.
- **Helper** (`helper/LinkHelper`): a shell-uid relay started through the phone's own Wireless debugging (`applink/Adb`, `Spake2`, `PairingService`). It lives in adbd's cgroup, so it dies when Samsung's Auto Blocker turns debugging off, and Wireless debugging needs a joined Wi-Fi network. Bump `PROTOCOL` when its ops change.

`diag/` is testing mode: encrypted log upload to ntfy.sh (`LogUploader`), plus interface and link probes. `core/` is pure Kotlin. `web/` is the Pages site and the optional web card (`web/c`).

## Rules learned from real taps

- **Binding a socket:** `Network.bindSocket(DatagramSocket)` takes the socket's lock, and Android holds that lock while another thread is blocked in `receive()`, so the bind hangs until a packet arrives. Never call it on a socket kwik is receiving on. `LinkHelper` binds from its own receive thread; `DirectLink` binds a duplicate descriptor from `ParcelFileDescriptor.fromDatagramSocket`, which doesn't take the lock. `SimulatedReader --es directbindwait <iface>` checks this on a device.
- **Repeat binds:** bind on link up, on the `REBIND_GAPS_MS` schedule and on the iPhone's first packet, and never deduplicate them. A bind made as the interface appears can silently not route. Stop rebinding once the iPhone connects: every failed 1.0.34–1.0.38 tap had a rebind land mid-exchange. That cause is likely but not proven.
- **Threads:** HCE callbacks run on the main thread and `TapEngine`'s scope is `Main.immediate`, so never block it. Defer with `later {}` or `Dispatchers.Main`.
- **Interface index:** Samsung can rebuild `mosey0` mid-tap, which changes its index. kwik keeps the old scoped address, which is why `DirectLink` rewrites the destination.
- **kwik:** `app/libs/kwik-0.10.3-daptap.jar` is kwik minus one class. `app/src/main/java/tech/kwik/` holds the patched `ServerConnectionCandidate` (LGPL-3.0) and an accessor. Never replace the jar with upstream.
- **Wi-Fi:** never tell the user to turn Wi-Fi off; Wi-Fi Aware needs the radio on, even with no network joined.

## Working on this project

- The goal is a plain tap at a career fair with no settings fiddling. The user has rejected web and Safari paths and the AirDrop fallback as answers to tap failures; the target is true native NameDrop.
- iPhone sessions are scarce because they need a friend's phone. Log complete, untruncated data on the first try, and read the uploaded log after every tap instead of guessing.
- An emulator loopback has hidden real-phone failures before. Always say what a test did not cover, and don't call something working until a real iPhone shows it.
- Don't store the device keys an iPhone sends (IRK, Ed25519, IDS ID). They could recognise that iPhone later, and the README promises we don't keep them.
- `DESIGN.md` is the UI's visual system (the ticket wallet in liquid glass). Read it before UI work.
- Docs lag the code. `README.md` and `web/index.html` still describe only the helper path, and `PRODUCT.md`'s positioning predates native NameDrop. When they disagree with the code, trust the code.
