# DapTap

**NameDrop for Android.** Hold your phone to someone else's and your contact card lands on theirs, iPhones included, with no app on their side.

[Download the latest APK](https://github.com/CodingGenius0001/namedrop-android/releases/latest/download/DapTap.apk) · [Web card](https://codinggenius0001.github.io/namedrop-android/)

## What happens when you tap

| They have | What they see |
|---|---|
| **iPhone** (AirDrop set to *Everyone for 10 minutes*) | On Android 17 (One UI 9) phones whose NFC chip supports it, DapTap sends Apple's **NameDrop NFC frame**, so the iPhone plays its own NameDrop animation. DapTap then hands your card to **Quick Share**, and the iPhone gets its built-in **AirDrop contact request** with your photo. |
| **iPhone** (any AirDrop setting) with *Web card* turned on | An NFC banner that opens your card in Safari, with **Save** and **Send mine back**. Their reply comes back to your phone end-to-end encrypted. |
| **Android phone with DapTap** | Both cards swap over NFC, the same way NameDrop works between iPhones. |
| **iPhone → you** | They share *My Card* by AirDrop to your Galaxy. Open the received `.vcf` with DapTap and you get the same card screen with **Save**. |

## How it works

DapTap combines three mechanisms that are each available to a normal, unrooted app:

1. **NFC Type 4 tag emulation** (`HostApduService`, `core/Type4Tag.kt`). While your phone is unlocked, it answers the NFC Forum NDEF application (`D2760000850101`). iPhones read it with background tag reading, and Android phones read it with the system tag dispatcher. Every read tells the tap engine that someone is touching your phone. The tag holds an NFC external record (`keshav.com:daptap`) that iOS ignores, so an iPhone shows no banner unless the web card is on.
2. **Apple's NameDrop polling frame** (`nfc/NameDropBeacon.kt`, `core/AppleEcp.kt`). iPhones start NameDrop with an Apple *Enhanced Contactless Polling* frame: `6A 02 89 05 00 01 00 01` + a 6-byte BLE address ([reverse-engineered by kormax](https://github.com/kormax/apple-enhanced-contactless-polling)). Android 17 added a public API, `NfcAdapter.EXTRA_READER_TECH_A_POLLING_LOOP_ANNOTATION`, that lets an app put a custom frame of up to 16 bytes into the NFC polling loop. While DapTap is open, it alternates between sending that frame and being a card, with randomised timing so two DapTap phones never stay in lock-step. The iPhone's reply (an AirDrop ECP frame) shows up in *Settings › Diagnostics › Tap log*.
3. **Quick Share ↔ AirDrop** (`share/QuickShare.kt`). Samsung's Quick Share on the Galaxy S26 (and Google's on Pixel 9/10) speaks AirDrop. DapTap sends Quick Share a vCard 3.0 file, so the iPhone receives a native AirDrop contact. An optional accessibility helper taps the iPhone in Quick Share's list for you, but only when exactly one iPhone is listed.

### What is not possible

NameDrop's *contact poster* exchange, the full-screen poster with *Receive Only / Share* buttons, runs over Apple's private Wi-Fi link (AWDL) between Apple ID–authenticated devices. Android apps can't speak AWDL: even Quick Share's AirDrop support lives in privileged system code. Researchers who tried to reuse NameDrop's exchange type found it rejected ([Protocol Prying, 2026](https://arxiv.org/html/2606.26967v1)). DapTap gets as close as an app can: Apple's own NFC trigger, Apple's own AirDrop sheet, and Apple's own contact import.

## Install

1. On your phone, open the [latest APK](https://github.com/CodingGenius0001/namedrop-android/releases/latest/download/DapTap.apk) and install it (allow installs from your browser when asked).
2. Make your card. Turn on NFC.
3. Optional, under **Settings**:
   - *Display over other apps*: the tap animation plays even when DapTap is closed.
   - *Pick their iPhone for me*: the accessibility helper. Sideloaded apps first need **App info › ⋮ › Allow restricted settings**.

Every build is signed with the same key, so new APKs install over old ones.

## Testing a tap

- **iPhone:** unlock it, set AirDrop to *Everyone for 10 minutes*, open DapTap on your phone, and touch your phone's back (centre) to the top back edge of the iPhone.
- **What to look for:** the grid ripples, the status chip says *Sent*, and Quick Share opens with your card. If anything misbehaves, *Settings › Diagnostics › Tap log* records every NFC command, every Apple frame seen and every decision, and *Copy* puts it on the clipboard.

## Project layout

```
app/src/main/java/com/keshav/daptap/
  core/      pure Kotlin: Type 4 tag + reader, NDEF, Apple ECP, vCard, AES-GCM box, payload, cell font
  nfc/       TapService (HCE), NameDropBeacon (Android 17 NameDrop frame)
  tap/       TapEngine: decides what a touch means; Android side effects
  share/     Quick Share / AirDrop handoff
  relay/     end-to-end encrypted mailbox on ntfy.sh (web-card replies, share-back)
  overlay/   tap animation over other apps
  a11y/      optional Quick Share auto-pick
  ui/        Compose screens (grid-built name posters)
app/src/debug/  SimulatedReader: replays an iPhone's NFC reads for testing without hardware
web/        the web card (GitHub Pages), its tests, and a Playwright end-to-end check
```

## Tests

```
./gradlew :app:testDebugUnitTest          # tag protocol, NDEF, ECP, vCard, crypto, relay, Kotlin↔web cross-check
cd web && node --test test/unit.test.mjs  # web card logic
cd web && node test/e2e.mjs "<card url>" --reply   # renders the card in WebKit/Chromium, sends a reply through ntfy.sh
```

`CrossLanguageTest` runs the web code under Node against vectors produced by the app, so both sides agree byte for byte on the payload, the AES-GCM boxes and the vCard.

## Privacy

Cards live on the phone. Nothing goes to a server, except for web-card replies and share-back: those pass through ntfy.sh as AES-256-GCM ciphertext on a random topic, with the key carried only in the NFC tag or the URL fragment, which never reaches a server.
