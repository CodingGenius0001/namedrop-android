---
name: DapTap
description: NameDrop for Android. Your name as a grid-built poster; a tap rebuilds theirs on the same grid.
colors:
  ultramarine: "#1F3BD1"
  vermilion: "#FF5A36"
  signal: "#C0301E"
  emerald: "#0B7A55"
  ink-ground: "#161616"
  rust: "#B0460F"
  teal: "#00707F"
  signal-yellow: "#FFD23F"
  ink: "#FFFFFF"
  ink-soft: "rgba(255, 255, 255, 0.90)"
  grid-line: "rgba(255, 255, 255, 0.10)"
  paper: "#F8F8FA"
  paper-ink: "#16161A"
  paper-ink-soft: "#4A4A55"
  paper-outline: "#75757F"
  paper-outline-soft: "#D3D3DA"
  night: "#0F0F12"
  night-ink: "#ECECF0"
typography:
  display:
    fontFamily: "DapTap cell alphabet (drawn on a 7-row grid; fallback: system sans, Black)"
    fontSize: "7 x pitch (pitch = width / (columns + 2), max 26dp app / 30px web)"
    lineHeight: 1
  headline:
    fontFamily: "Roboto (Android system) / Archivo (web)"
    fontSize: "30px"
    fontWeight: 700
    lineHeight: 1.12
    letterSpacing: "-0.01em"
  title:
    fontFamily: "Roboto (Android system) / Archivo (web)"
    fontSize: "22px"
    fontWeight: 700
    lineHeight: 1.2
  body:
    fontFamily: "Roboto (Android system) / Archivo (web)"
    fontSize: "17px"
    fontWeight: 400
    lineHeight: 1.35
  label:
    fontFamily: "Roboto (Android system) / Archivo (web)"
    fontSize: "14px"
    fontWeight: 500
    lineHeight: 1.3
rounded:
  cell: "22%"
  photo: "14%"
  field: "12px"
  panel: "20px"
  overlay: "22px"
  chip: "24px"
  pill: "999px"
spacing:
  xs: "4px"
  sm: "10px"
  md: "16px"
  lg: "24px"
  bar: "64px"
components:
  button-primary:
    backgroundColor: "{colors.ink}"
    textColor: "{colors.ultramarine}"
    rounded: "{rounded.pill}"
    height: "56px"
    padding: "0 22px"
  button-secondary:
    backgroundColor: "transparent"
    textColor: "{colors.ink}"
    rounded: "{rounded.pill}"
    height: "52px"
  status-chip:
    textColor: "{colors.ink}"
    rounded: "{rounded.chip}"
    padding: "12px 16px"
    height: "48px"
  field-web:
    backgroundColor: "{colors.ink}"
    textColor: "#111111"
    rounded: "{rounded.field}"
    padding: "0 14px"
    height: "48px"
  photo-tile:
    rounded: "{rounded.photo}"
    textColor: "{colors.ink}"
---

# Design System: DapTap

## Overview

**Creative North Star: "The Crouwel Grid Specimen"**

Every card is a poster printed on one drenched, saturated ground. A faint construction grid runs across the whole surface at the exact pitch of the letter cells, and the person's first name is built from white rounded-square cells on that grid, always on one line and always as wide as the screen allows. Everything else (surname, role, contact rows, controls) is small, plain grotesk type, so the cell name is the only monument on the page.

The grid is not decoration; it is the motion system. A tap sends a ring of lit cells outward from where the NFC coils meet, and a received name assembles cell by cell outward from the middle. The same world carries unchanged to the floating overlay and the web fallback card. People and Settings open under a ground-coloured title band with the grid behind it; People itself is a run of person strips on their own grounds. Edit steps back to a tinted Material 3 form with a small grid-and-cells specimen panel.

Density is low on the poster and standard Material on utility screens. The visual rejection is explicit in the build: no avatar-circle-plus-list contact screen as home.

**Key Characteristics:**
- One saturated ground per person, chosen from six; white ink on all of them.
- A visible 1px construction grid at the cell pitch, white at 10%.
- First name in the grid-drawn cell alphabet, one line, auto-fit.
- One accent (vermilion or signal yellow, paired with the ground) for live state only.
- Plain grotesk for everything that is not the first name.

## Colors

Six drenched grounds, each paired with one hot accent, all carrying white ink; utility screens use a near-white or near-black neutral faintly tinted toward the active ground.

### Primary
- **Ultramarine** (ultramarine): the default ground; the whole poster, the web card body, and the Material `primary` in light mode.
- **Ground family**: **Signal** red (signal), **Emerald** (emerald), **Ink** black (ink-ground), **Rust** (rust), **Teal** (teal). A person's label picks one; their poster, overlay and People tile render on it. The order and pairings are identical in `Theme.kt` and `web/c/app.js`.

### Secondary
- **Vermilion** (vermilion): live-state accent on Ultramarine and Ink grounds. The ready dot, the sent check, the web focus ring and focused input border.
- **Signal Yellow** (signal-yellow): the same role on Signal, Emerald, Rust and Teal grounds, where vermilion would vanish or fail 3:1.

### Neutral
- **Ink** (ink): every glyph cell, name, detail value and the filled primary button on a ground.
- **Soft Ink** (ink-soft): role line, detail icons, unselected nav items, secondary button stroke. 0.90 alpha on both app and web, the lowest that clears 4.5:1 on every ground.
- **Grid Line** (grid-line): the construction grid, 1px.
- **Raised Ground**: the ground mixed 18% toward black (web: 20%). Status chip, Card-tab navigation bar, photo plate, web sheet. Derived, never a fixed hex.
- **Paper / Paper Ink / Paper Ink Soft / outlines** (paper, paper-ink, paper-ink-soft, paper-outline, paper-outline-soft): light Material scheme for People, Settings and Edit, each background lerped 3-6% toward the ground.
- **Night / Night Ink** (night, night-ink): dark scheme base, lerped 6% toward the ground; dark `primary` is the ground lifted 45% toward white.

### Named Rules
**The One Ground Rule.** A poster surface is the ground and nothing else: no gradients, no second fill, no tinted cards on top. Depth comes from Raised Ground only.

**The Live Accent Rule.** The accent appears only as state. At rest it is the ready dot and the focus ring; while a tap is live (touching, sent) it fills the status chip as one plane, with near-black #16161A text (about 6:1 on both accents). It never fills a button, a heading or any other panel.

**The Paired Accent Rule.** Accent follows the ground it is paired with; never put vermilion on Signal, Emerald or Rust.

## Typography

**Display Font:** the DapTap cell alphabet (`core/CellFont.kt`, mirrored in `web/c/cellfont.js`), drawn, not typeset; non-Latin names fall back to system sans Black at 56sp, one line, ellipsised.
**Body Font:** Roboto via the Android system default; Archivo (400/500/600/800) on the web card.

**Character:** a monumental, mechanical display alphabet against a quiet, neutral grotesk. The contrast is in scale and construction, not in pairing two expressive faces.

### Hierarchy
- **Display** (cell alphabet, 7 rows, cell = pitch): first name on the poster, overlay word (DAP, SENT, SWAP, or the name, cells up to 9dp), and the HELLO / YOU specimen panels (cells up to 8dp). Uppercased, accents stripped, one-column letter gap.
- **Headline** (bold 700 app / 800 web, 30, 1.12): rest of the name under the cell name, two lines max. Web headline message: 800, clamp(30px, 8vw, 44px).
- **Title** (bold, 22): the DapTap wordmark in the poster bar (web: 800, 20px, -0.01em) and sheet titles; 18 semibold for the person screen bar; 17 semibold for overlay titles.
- **Body** (400, 17, 1.35): role line (soft ink, 40ch on web), contact detail values, button labels (16-17, semibold for primary).
- **Label** (500, 14): status chip text, web form labels, overlay body (14/19, soft ink).

### Named Rules
**The One Line Rule.** The first name always fits on one line: pitch = available width / (columns + 2), capped at 26dp (app) or 30px (web, floor 6px). Never wrap, truncate or scale the cell name any other way.

**The First Word Rule.** Only the first word of a name is drawn in cells. Surname and everything else is type.

## Layout

The poster is laid out on its own grid. Pitch is computed from the first name; the construction grid uses the same pitch (the app doubles the grid pitch when cells fall below 9dp so lines do not turn to haze). Content insets one pitch left and right, and the top of the content is snapped to the next grid line below the status bar plus a 64dp bar, so cells register exactly with the grid. A photo, when present, is a whole number of cells (at least 3, about 84-88px) above the name, followed by one pitch of space.

Vertical rhythm under the name: one pitch, headline, role (4-6px), 20-22px, detail rows (min 40dp app / 44px web, 20px icon, 14px gap). Fixed bottom action area: 16px side and bottom padding, 10-12px gaps. The web card caps the poster at 720px and pads by safe-area insets.

Utility screens follow Material 3: top app bar, 16dp horizontal padding, list items at standard height (56dp minimum for toggles), section labels 24dp above.

## Elevation & Depth

Flat by construction. Depth on a ground is tonal: Raised Ground (ground pushed toward black) for chips, the nav bar, the photo plate and sheets. The only shadows are the Material default on the Edit card extended FAB and the web action bar's upward edge shadow, both separating fixed controls from scrolling poster content.

### Shadow Vocabulary
- **Action edge** (`box-shadow: 0 -10px 24px -12px rgba(0,0,0,.35)`): web fixed action bar over the scrolling poster.
- **FAB rest**: Material 3 extended FAB default elevation (Android Edit card).

### Named Rules
**The Tonal Lift Rule.** On a ground, lift by darkening, not by shadow. A new raised element uses Raised Ground.

## Shapes

The world's atom is the rounded square: cells inset 7% of the pitch with a 22% corner, ripple cells inset 12% with 20%. Photo tiles and initials plates are rounded squares at 14% of their size. Containers that carry the world (specimen panels 20dp, overlay 22dp) use soft rounded rectangles; controls that are pressed (buttons, status chip) are full pills. Circles are reserved for the live dot (8dp) and the ground swatches in the colour picker. Strokes are 1px (grid, secondary button outline) except the 3dp selected-swatch ring.

## Components

### Buttons
Tactile white pills that invert the ground.
- **Shape:** full pill (rounded.pill; Material default on Android).
- **Primary:** ink fill, ground-coloured text and icon, min 56dp (web 54px), semibold 16-17. Save on a person card, Edit card FAB on home.
- **Secondary:** transparent with a 1px soft-ink outline, ink text, min 52dp. Send mine back.
- **States:** disabled primary drops to Raised Ground with ink text (app) or 55% opacity (web); web press scales to 0.98 over 120ms.
- **Utility screens:** Material text buttons in the tinted `primary`.

### Status Chip (signature)
- Raised Ground pill (24dp), min 48dp, 16 x 12 padding, ready dot in the accent, 14 medium text wrapping to two lines, polite live region. While a tap is live the pill fills with the accent (180ms colour fade) and its text, dot and check turn near-black. At font scale 1.15 and above the Edit card button beside it folds to its icon. The dot pulses (alpha 1 to 0.25, 520ms) only while phones are touching. Text crossfades 160/120ms. Web equivalent: an 8px accent dot before the status line.

### Poster (signature)
Ground, construction grid, cell name, headline, role, detail rows, as laid out above. Used for your card (home), a received card, and the web card.

### Tap Ripple and Cell Build (signature motion)
- **Ripple:** a band 3.5 pitches wide of lit cells (white, max 55% alpha, fading with progress) expands from the tap origin (0.5, 0.45 on the phone; top centre on the web; bottom centre in the overlay), 1100ms linear, looping while touching (web: 1.3s, cubic-bezier(.25,.7,.3,1)).
- **Build:** each cell starts after a delay proportional to its distance from the origin (65% of the run) and grows from zero over 35% of it. 850ms after sending, 1000ms (+120ms delay) for a received card, 900ms (+250ms) in the overlay; web cells 420ms each, staggered over 700ms after 380ms.
- **Reduced motion:** animator scale 0 or `prefers-reduced-motion` removes ripple and build entirely; names appear complete.

### Overlay
A ground-coloured 22dp rounded panel, 10dp from the screen edges under the status bar, 10dp grid, ripple from bottom centre, a one-word cell headline (max 9dp cells), 17 semibold title, 14 soft-ink body. Tapping opens the app.

### Specimen Panel
On utility screens the world appears as a 20dp rounded ground panel whose grid, padding, photo and letters all step in one 8dp cell (YOU / the first name in Edit; HELLO on empty People, left-aligned on a grid line). Never more than one per screen.

### Inputs / Fields
- **Android:** Material 3 outlined text fields, single line, label above, supporting text for errors.
- **Web:** white field, 12px corners, 2px transparent border that turns accent on focus, 48px min height, 17px text in near-black, caret in the ground colour, inside a Raised Ground bottom sheet with 24px top corners.

### Navigation
Material 3 bottom navigation (Card / People / Settings, outlined icons). On the Card tab it adopts the world: Raised Ground container, ink indicator with ground-coloured selected icon, soft-ink unselected. On other tabs it is the tinted Material surface container, under a ground-coloured title band with a 16dp grid.

### Lists
People is a stack of full-width strips, each on that person's own ground with a 7dp grid. The first name is built in 7dp cells registered two cells in, with the surname (17 bold) and role / how / when (14 soft ink) below and a photo of 8 cells at the right only when there is one. A 1dp Raised Ground hairline separates neighbours. Settings keeps Material list items under the ground title band.

## Do's and Don'ts

### Do:
- **Do** compute pitch from the first name and draw the grid at the same pitch (The One Line Rule).
- **Do** keep all text on a ground white or soft white; use Raised Ground for anything that sits on it.
- **Do** reserve the paired accent for live state: the ready dot, focus, and the status chip while a tap is live.
- **Do** start motion where the phones touch and let reduced-motion remove it completely.
- **Do** keep app and web in lockstep: the same six grounds, accents and cell alphabet (CellFontSyncTest guards the alphabet).

### Don't:
- **Don't** use the avatar-circle-plus-list contact layout for a person's card; that is what the poster replaces.
- **Don't** draw anything other than the first word in cells, or wrap it onto two lines.
- **Don't** put gradients, photos-as-background or second fills on a ground.
- **Don't** fill a button, heading or panel with the accent; the live status chip is the only plane it takes.
- **Don't** add glyphs to the cell alphabet that break the 7-row grid or introduce free diagonals.
