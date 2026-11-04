# DapTap

**NameDrop for Android.** Hold your phone to an iPhone and the iPhone plays its own NameDrop: your contact card lands on it, and theirs lands on yours. No app on their side.

[Download the latest APK](https://codinggenius0001.github.io/namedrop-android/DapTap.apk)

> Built and tested on a Galaxy S26 Ultra (One UI 9, Android 17) against a real iPhone. It works, and it is still being hardened: if a tap gets stuck, turn on *Settings › Diagnostics › Testing mode* and the next tap's log is saved for debugging.

## What happens when you tap

| They have | What happens |
|---|---|
| **iPhone** | The iPhone shows its NameDrop card, with Apple's own animation. When they tap **Share**, their card comes to DapTap and yours appears on the iPhone, ready to save, the same as between two iPhones. Needs the one-time *iPhone link* setup (below). |
| **Android phone with DapTap** | Both cards swap over NFC. |
| **iPhone, without the iPhone link** | DapTap can open Quick Share with your card, so the iPhone gets a normal AirDrop request (their AirDrop must be set to *Everyone for 10 minutes*). |

Every card you receive goes on the *People* tab, where you can save it to Contacts.

## How it works

1. **NFC.** DapTap answers NFC as a card (`HostApduService`) and, on Android 17 phones whose NFC chip allows it, sends the polling frame an iPhone sends to start NameDrop (`nfc/NameDropBeacon.kt`). The iPhone then runs Apple's NameDrop handshake with DapTap (`core/Snap.kt`), which tells it where to find DapTap over Wi-Fi.
2. **The Apple link.** On the Galaxy S26, Samsung's Quick Share can raise a Wi-Fi Aware link that iPhones speak AirDrop over (`mosey0`). DapTap opens Quick Share for a moment, behind a cover screen, so the link comes up.
3. **The exchange.** The iPhone connects to DapTap's NameDrop server (QUIC + HTTP/3, `namedrop/`) and runs `/Hello`, `/Ask` and `/Exchange`, sending its card and taking yours.

Android only lets system apps send on that link, so DapTap starts a small relay (`helper/LinkHelper.kt`) with shell permissions, through the phone's own *Wireless debugging*: DapTap pairs with it once, like a computer would (`applink/`). After that the relay comes back by itself: DapTap watches it and restarts it when it stops.

### The iPhone link setup

The first time, *Settings › iPhone link › Set up* walks you through it, one step at a time:

1. Turn off Samsung's **Auto Blocker** (it switches debugging off).
2. Join Wi-Fi.
3. Turn on **Developer options** and **Wireless debugging**.
4. Pair DapTap from the Wireless debugging screen.

DapTap opens each settings page for you and ticks off each step as it happens.

## Install

1. On your phone, open the [latest APK](https://codinggenius0001.github.io/namedrop-android/DapTap.apk) and install it (allow installs from your browser when asked).
2. Make your card. Turn on NFC.
3. Set up the iPhone link (above).
4. Optional: *Display over other apps*, so taps work while DapTap is closed.

DapTap updates itself: new builds install over old ones, all signed with the same key.

## Tapping an iPhone

Unlock both phones, open DapTap, and touch the backs together, your phone's middle to the top of the iPhone. Hold still until the iPhone shows the NameDrop card, then let them tap **Share** (or **Receive Only**).

## Project layout

```
app/src/main/java/com/keshav/daptap/
  core/      pure Kotlin: Type 4 tag + reader, NDEF, Apple ECP, NameDrop SNAP handshake, vCard, cell font
  nfc/       TapService (HCE), NameDropBeacon (Android 17 NameDrop frame)
  namedrop/  NameDrop server: QUIC/HTTP-3 (kwik), /Hello /Ask /Exchange, the Apple link route
  applink/   Wireless debugging pairing (SPAKE2 + adb TLS), starting and supervising the relay
  helper/    LinkHelper: the shell-uid relay for the Apple link
  tap/       TapEngine: decides what a touch means; the swap's foreground service
  share/     Quick Share / AirDrop handoff
  overlay/   the cover and banners over other apps
  ui/        Compose screens: the ticket wallet, setup, people
  update/    in-app updater
  diag/      testing mode: encrypted log upload, interface and link probes
app/src/debug/  SimulatedReader: replays an iPhone's NFC reads, and a loopback iPhone, for testing without hardware
web/        the GitHub Pages site (and the optional web card)
```

## Tests

```
./gradlew :app:testDebugUnitTest     # tag protocol, NDEF, ECP, SNAP, NameDrop exchange, vCard, crypto
```

On a phone or emulator with the debug build, a full swap against a fake iPhone on loopback:

```
adb shell am broadcast -n com.keshav.daptap.debug/com.keshav.daptap.SimulatedReader \
  --ez namedrop true --ez loopback true
```

## Privacy

Cards live on the phone, and a tap goes phone to phone. Nothing goes to a server, with two opt-in exceptions:

- **Testing mode** uploads the tap log to ntfy.sh, encrypted end to end on a random topic; ntfy drops it after about 12 hours.
- **Web card** (off by default, *Settings › iPhones*) passes replies through ntfy.sh as AES-256-GCM ciphertext; the key travels only in the NFC tag or the URL fragment, which never reaches a server.

DapTap never keeps the device keys an iPhone sends during NameDrop, so a card you receive can't be used to recognise that iPhone later.

Third-party code: see [THIRD_PARTY.md](THIRD_PARTY.md).
