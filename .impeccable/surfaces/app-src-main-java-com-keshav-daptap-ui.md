---
version: 1
slug: "app-src-main-java-com-keshav-daptap-ui"
primary_target: "app/src/main/java/com/keshav/daptap/ui"
related_targets: ["web/c"]
---

Scope: DapTap Card screen (home), the incoming-card screen, People, Settings, and the tap/receive overlay; the web fallback card follows later. Mode: operate.

Audience/job: someone standing with a new acquaintance (often an iPhone owner), phone in hand, swapping cards in one tap; afterwards, finding who they met. Constraints: Material 3 controls, Android navigation and predictive Back; first name always on ONE line in cell letters, cell size computed from the name's length (user pinned); liquid-glass material (user pinned); reduced motion removes all motion.

## Direction contract

THESIS: Every swap is a frosted glass ticket in a wallet: your card is your contact pass, each person you meet is a pass in the stack, and states stamp themselves (SENT, SAVED, RECEIVE ONLY, VOID) instead of vanishing. Refuses the avatar-circle-plus-list contact screen and the generic glass card.

OWN-WORLD: Carrier navy ground (#17295C to #0A1330), passes in frosted glass (blur, white 8-22% fill, 1px top highlight), notched corners and a dashed perforation separating pass from stub, tab-cap labels in tracked caps, values in a carbon-purple numeral voice, jet red for the live state and stamps only, white cell-letter first name.

STORY: Open: your pass, name big in glass cells, stub saying READY TO TAP and your last swap. Tap: the stub tears off with a glass-edge shine, a SENT stamp lands; their pass slides into the wallet on top with SAVED. People: the stack, newest on top.

FIRST VIEWPORT: top app bar (DapTap, settings), contact pass (CONTACT PASS · NO., cell name, surname, role, detail rows), perforation, stub (READY TO TAP, last swap, stamp), older passes peeking behind, full-width Edit card, navigation bar Card / People / Settings.

FORM: Jet-age ticket wallet (dealt challenger, chosen by the user over the pick), translated into liquid glass. seed:726cc93c6cb5b1ad

FINISH: unreviewed and undocumented is unfinished; this build ends with the finish review, the verdict, DESIGN.md, and every shipping raster carrying its provenance
