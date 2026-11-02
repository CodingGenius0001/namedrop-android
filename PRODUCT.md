# Product

<!-- impeccable:product-schema 1 -->

## Platform

android

## Stack

Native Android: Kotlin + Jetpack Compose (single `app` module, minSdk 26, target 35). Companion fallback page: static HTML/CSS/JS served by GitHub Pages from this repo. APKs built by GitHub Actions. Stack delegated to Claude; the user pinned Android + GitHub Actions + GitHub Pages (public repo `CodingGenius0001/namedrop-android`).

## Users

Keshav (primary user, Samsung Galaxy S26 Ultra owner, college student) and the people he meets, most of whom carry iPhones. The scene: two people standing together, phones in hand, wanting to swap contact info in a second, the way two iPhones do with NameDrop.

## Product Purpose

DapTap is NameDrop for Android: bring your phone to someone else's and your contact card lands on their phone, theirs lands on yours. Success: an iPhone owner with no app installed receives a native iOS contact request after a tap, and Keshav gets theirs back with as few steps as possible.

## Positioning

NameDrop itself is locked to Apple ID–authenticated devices over Apple's private AWDL link. DapTap is the only path that reaches a stock iPhone: it detects the tap over NFC (host card emulation + Apple ECP polling-frame detection) and hands a full vCard to the S26's system Quick Share, whose AirDrop interoperability makes the iPhone show its built-in AirDrop contact sheet.

## Operating Context

- Tap: phone backs together (S26 NFC coil is mid-back; iPhone's is at the top edge).
- iPhone receiving natively requires AirDrop set to "Everyone for 10 minutes" (an Apple/Quick Share limitation).
- iPhone → Android natively: the iPhone shares its own card via AirDrop to the Galaxy; DapTap opens received .vcf files with its receive animation.
- Android ↔ Android: both phones run DapTap; the tap exchanges cards mutually over NFC.
- Fallback (opt-in): the NFC tap opens a web card on the iPhone with Save + Share back; replies return end-to-end encrypted through a public relay (ntfy.sh).
- Works with the app closed: overlay animation over any screen when a tap happens.

## Capabilities and Constraints

- Cannot trigger Apple's NameDrop UI itself (needs custom NFC polling frames, AWDL, and Apple-signed identity). Documented in README.
- Quick Share target selection is Quick Share's own UI; an opt-in accessibility helper may auto-pick the single iPhone in the list.
- Received history kept on-device only.
- No accounts, no server of our own; contact data never stored server-side in plaintext.

## Brand Commitments

- Name: **DapTap** (user-chosen). The gesture is a dap: two phones bump, you've met.

## Evidence on Hand

No logos, imagery, testimonials, or press. Nothing to fabricate.

## Product Principles

1. The tap is the whole interface: everything else should get out of its way.
2. Native over web: prefer the receiver's built-in UI whenever the platform allows.
3. Consent on both sides: nothing is saved or sent without a deliberate tap.
4. Private by construction: on-device storage, end-to-end encryption for any relay hop.

## Accessibility & Inclusion

Standard Android accessibility: TalkBack labels on every control, respects system font scale and reduced-motion (animator duration scale 0 ⇒ no ripple/warp motion), contrast AA.
