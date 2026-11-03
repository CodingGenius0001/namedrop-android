---
name: DapTap
description: NameDrop for Android. Your card is a frosted-glass contact pass in a navy ticket wallet; every swap stamps itself.
colors:
  navy-top: "#17295C"
  navy-bottom: "#0A1330"
  dock: "#0C1736"
  ink: "#FFFFFF"
  ink-soft: "rgba(255, 255, 255, 0.78)"
  carbon: "#C3B8FF"
  jet: "#FF6A58"
  perforation: "rgba(255, 255, 255, 0.34)"
  ultramarine: "#1F3BD1"
  signal: "#C0301E"
  emerald: "#0B7A55"
  ink-ground: "#161616"
  rust: "#B0460F"
  teal: "#00707F"
  paper: "#F8F8FA"
  paper-ink: "#16161A"
  night: "#0F0F12"
  night-ink: "#ECECF0"
typography:
  display:
    fontFamily: "DapTap cell alphabet (drawn on a 7-row grid; fallback: system sans, Black, 56sp)"
    fontSize: "7 x cell (cell = width / columns, capped: 26dp pass, 7dp People, 8-9dp overlay)"
    lineHeight: 1
  headline:
    fontFamily: "Roboto (Android system)"
    fontSize: "30px"
    fontWeight: 700
    lineHeight: "34px"
  title:
    fontFamily: "Roboto (Android system)"
    fontSize: "22px"
    fontWeight: 700
  body:
    fontFamily: "Roboto (Android system)"
    fontSize: "17px"
    fontWeight: 400
    lineHeight: "23px"
  value:
    fontFamily: "Roboto (Android system)"
    fontSize: "17px"
    fontWeight: 500
    fontFeature: "tnum"
  label:
    fontFamily: "Roboto (Android system)"
    fontSize: "12px"
    fontWeight: 700
    letterSpacing: "0.16em"
rounded:
  pass: "26px"
  pass-row: "22px"
  notch: "11px"
  stamp: "8px"
  photo: "14%"
  cell: "22%"
  pill: "999px"
spacing:
  wallet-margin: "16px"
  pass-padding: "24px"
  stub: "92px"
  row-gap: "12px"
  bar: "64px"
components:
  pass:
    rounded: "{rounded.pass}"
    padding: "20px 24px 24px"
    textColor: "{colors.ink}"
  pass-row:
    rounded: "{rounded.pass-row}"
    padding: "16px 20px 18px"
    textColor: "{colors.ink}"
  stub:
    height: "{spacing.stub}"
    padding: "0 24px"
    textColor: "{colors.ink-soft}"
  stamp:
    textColor: "{colors.jet}"
    rounded: "{rounded.stamp}"
    padding: "5px 12px"
    typography: "{typography.label}"
  button-primary:
    backgroundColor: "{colors.ink}"
    textColor: "{colors.navy-top}"
    rounded: "{rounded.pill}"
    height: "56px"
  button-outlined:
    textColor: "{colors.ink}"
    rounded: "{rounded.pill}"
  nav-bar:
    backgroundColor: "{colors.dock}"
    textColor: "{colors.ink-soft}"
  pass-field-value:
    textColor: "{colors.carbon}"
    typography: "{typography.value}"
---

# Design System: DapTap

## Overview

**Creative North Star: "The Ticket Wallet, in liquid glass"**

Your card is a contact pass: a frosted glass ticket on a carrier-navy ground. Each person you meet is another pass in the stack, and states stamp themselves (SENT, SWAPPED, SAVED, READY) instead of vanishing. The person's colour is not a fill; it glows through the glass from behind as soft light, so the same six grounds still tell people apart. The navy ground carries a faint security-dot screen, the paper a pass is printed on.

The first name is the one monument: built from white rounded-square cells on one line, auto-fit to the pass. Everything else is plain Roboto, with tracked-caps labels, tabular values, and a single red (jet) reserved for live state and stamps. Cards, People, the person screen, iPhone-link setup and the floating overlays all live in this world; Settings and Edit keep plain Material 3.

Density is low on the pass and standard Material on utility screens. The build visibly rejects the avatar-circle-plus-list contact screen.

**Key Characteristics:**
- Liquid-glass material (pinned): coloured glow, white frost, bright top edge, soft black shadow.
- Tear-off stub below a dashed perforation between two notches, on the Card and setup passes.
- First name in cell letters, one line, auto-fit (pinned).
- Material 3 navigation bar, buttons and Android Back (pinned).
- Reduced motion removes all motion (pinned).

## Colors

Navy ground, white ink, one lilac numeral voice, one red; six person colours that only glow.

### Primary
- **Carrier Navy** (navy-top to navy-bottom, vertical gradient, #17295C to #0A1330): the ground of every wallet screen and of the overlay banner's plate.
- **Dock Navy** (dock): the navigation bar when in the wallet (Card, People, person). The Scaffold behind it is navy-bottom.

### Secondary
- **Jet Red** (jet): the live dot, the pulsing dot while phones touch, and every stamp outline and text. About 5:1 on navy.

### Tertiary
- **Carbon Lilac** (carbon): values that read like carbon copy: the "NO." pass number, a nameless row's phone number, the DONE check in setup. About 8:1 on navy.

### Neutral
- **Ink** (ink): names, values, detail text, the filled primary button.
- **Soft Ink** (ink-soft, 0.78 white): secondary text, tab-cap labels, icons, unselected nav items, outlined button stroke.
- **Perforation** (perforation, 0.34 white): the dashed line.
- **Person grounds**: Ultramarine, Signal, Emerald, Ink, Rust, Teal (`Grounds` in Theme.kt, picked by the card's label). They glow at 0.9 / 0.45 alpha through a radial light behind the glass and tint Photo plates and the Edit screen; they are never a flat pass fill.
- **Paper / Night** (paper, paper-ink, night, night-ink): the Material schemes for Settings and Edit, each lerped 3-6% toward the ground; dark `primary` is the ground lifted 45% toward white.

### Named Rules
**The Live Red Rule.** Jet appears only as state: the dot, the pulse, a stamp. It never fills a button, heading or pass.
**The Glow Not Fill Rule.** A person's colour reaches a pass only as light through the glass, never as a flat plane.

## Typography

**Display Font:** the DapTap cell alphabet (`core/CellFont.kt`), drawn, not typeset; names it cannot draw fall back to system sans Black 56sp, one line, ellipsised.
**Body Font:** Roboto via the Android system default.

**Character:** a monumental mechanical name over quiet grotesk and tracked-caps labels; contrast is in scale, not in pairing faces.

### Hierarchy
- **Display** (cell alphabet, 7 rows): first name on the pass (cell up to 26dp, fit to inner width), People rows (7dp), overlay word (9dp), swap cover (14dp), HELLO on empty People (8dp). Surname is built on the same pitch in whole, half or third cells when it fits.
- **Headline** (bold 30/34): surname when the alphabet cannot draw it, two lines max.
- **Title** (bold 22): DapTap bar title, People title, "No one yet"; setup step title bold 24; person bar 18 semibold.
- **Body** (17/23): role line, detail values, button text (16 semibold).
- **Value** (medium 17, tabular figures): `PassField` values.
- **Label** (bold 12, +0.16em, caps): pass head, field heads, stub head (13), "DONE".

### Named Rules
**The Tab-Caps Rule.** Every label on a pass is `Wallet.tabCaps`; do not invent another label style.

## Layout

Side margin 16dp, pass inner padding 24dp (20dp on People rows), stub 92dp, People rows 12dp apart, top bar 64dp. The Card pass is centred in the free space between bar and Edit card button and scrolls when taller. Cell pitch = inner width / name columns, so longer names get smaller cells.

## Elevation & Depth

Depth is glass, not paint: a soft black shadow (18dp, spot 0.55 alpha), a frost gradient (white 0.22 to 0.08), a 1dp edge gradient (white 0.75 at top fading to 0.10, then 0.22), and the colour glow behind it (56dp blur on Android 12+, a plain radial light below). Up to two older passes peek out under the Card pass at 12dp inset steps, drawn white 0.10 and 0.06 and never through the glass.

## Shapes

Passes are 26dp rounded rectangles (22dp for People rows and the overlay banner) with two 11dp half-circle notches at the perforation. Stamps are 2.5dp outlined 8dp rectangles set at -8 degrees (-5 on People). Cells are rounded squares (22% radius, 7% inset). Photos are 14% rounded squares. Dots are 8dp circles.

## Components

### Pass (signature)
Glass over navy. Head row (kind left, issuer or "NO." right), optional photo, cell name, surname, role, detail rows (40dp min height, 20dp icons). Card pass: "CONTACT PASS" and "NO. 000n".

### Stub and Stamp (signature)
Below the perforation: dot, tab-caps state (READY TO TAP, HOLD STEADY, ON ITS WAY, CARD READ, CARDS SWAPPED, NO NFC, NFC IS OFF, TAP SHARING IS OFF), one plain line, stamp (SENT, SWAPPED, SAVED, READY, RECEIVED). A stamp slams in (scale 1.8 to 1, spring). When the next tap is ready the stamped stub tears away (drops 36dp, tilts 5 degrees, 380ms) and a fresh one rises. Polite live region; tappable only when it offers a fix.

### Shine and Press
While phones touch, a diagonal band of light sweeps the glass (800ms burst, then 1200ms steady) and the pass presses to 0.975 from the tap origin; the dot pulses (520ms). Reduced motion: none of it runs.

### People
A stack of rows, newest first: head is source (TAP, NAMEDROP, AIRDROP, WEB CARD, SHARED, LINK) and relative time; cell name 7dp; surname 17 bold; role 14; photo 64dp at right; SAVED stamp. A nameless iPhone card leads with its phone number in carbon and "No name yet · open to add one". Empty: a "YOUR WALLET / EMPTY" pass with HELLO.

### Buttons
Material 3 Button, white fill, navy text, min 56dp (Edit card, Save); outlined with 1dp soft-ink stroke for secondary; icon buttons in ink. Settings keeps Material text buttons.

### Navigation
Material 3 NavigationBar (Card / People / Settings). In the wallet: dock container, white indicator, navy selected icon, soft-ink unselected. On Settings: the tinted Material surface container. Android Back returns to Card from other tabs and closes the person and setup screens.

### Setup
Guided iPhone-link: a step pass ("STEP n OF n", title, body, action) with the live check on its stub ("CHECKING FOR YOU" / "YOUR CALL"); finished steps are rows with a carbon check and DONE, upcoming rows have a dim dot; the end is "You're set" with a READY stamp.

### Overlay
Swap cover: full-screen navy with a notched pass (NAMEDROP / IPHONE head, SWAP or the name in 14dp cells, stub, Cancel). Banner: a 22dp pass on its own navy plate, 10dp from the edges, one cell word (SENT, SWAP, DAP or the name, 9dp), 17 semibold title, 14 body; tapping opens the app. Shine only without reduced motion.

## Do's and Don'ts

### Do:
- **Do** build the pass from `GlassPass`, `PassHead`, `PassField`, `Stamp` and `Wallet.tabCaps`.
- **Do** fit the first name on one line from its length.
- **Do** end swaps with a stamp and give state in tab caps plus one plain line.
- **Do** keep Material 3 navigation and controls, Android Back, and let reduced motion remove all motion.
- **Do** keep text on glass at ink or soft ink (0.78 clears 4.5:1 over the lightest glass).

### Don't:
- **Don't** use the avatar-circle-plus-list contact layout.
- **Don't** wrap or truncate the first name in cells onto a second line.
- **Don't** fill a pass, button or heading with a person colour or with jet.
- **Don't** put a pass on a flat or light background; passes belong on the navy ground.

## Not Yet Migrated and Not Canonized

- The web fallback card (`web/c`) is not yet migrated; it still follows the old grounds-and-grid look.
- The direction contract names navy #14234F to #0B1530; the build is #17295C to #0A1330 and the build wins.
- `Ground.inkSoft` (0.90) in Theme.kt is a leftover from the old world; wallet screens use `Wallet.inkSoft`.
