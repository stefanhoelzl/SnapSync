# Design

## Context

See proposal.md — Why. Every edit here makes the spec state what the shipped app already does; the audit traced each
one to the code before this change was written. Phase 4.4a (the selection concept's move into the core's layers) is
built against the aligned specs, so they must be right before it lands.

## Goals / Non-Goals

**Goals:**
- The `photo-sharing` and `photo-access` specs state only outcomes the app honours, and no longer contradict each
  other.

**Non-Goals:**
- Any behaviour change. Where the spec described a better behaviour than the app's, the user chose the spec to follow
  the app (D1, D3). A later change may still make a returning photo come back without a re-upload; it would restate
  these requirements.
- The decision-record pointer the audit asked for (proposal.md, "Not done").

## Decisions

### D1 — A photo that left what the device shares is uploaded again when it returns

The device withdraws a photo by forgetting it was shared when the photo leaves what the device can see as its own:
deleted, removed from the limited selection, moved out of DCIM, or excluded by full access for what it is. When such a
photo returns, the device has no record that the event already holds it, and uploads it again; the event service
stores it as the same photo, so no member sees a second copy. This was accepted in
`changes/archive/2026-09-22-selection-is-the-walk` (design, Risks: "LIMITED → GRANTED re-uploads every previously
de-selected photo"), but the specs kept the older promise.

What only falls out of the member's choice — a narrowed range, a messaging-app album, sharing turned off — is
withdrawn without being forgotten, so it comes back without a re-upload. The spec now draws the line there.

- **Rejected: make the code keep the promise** (remember "withdrawn, already on the server" instead of forgetting).
  It costs a ledger state that phase 4.4b would otherwise not need; the user chose the spec to follow the code. The cost
  of the re-upload is the data a member spends on photos they took back, which they can observe but which loses nothing.
- The `photo-access` requirement's name stated the old promise absolutely ("never re-uploads"), so it is renamed; the
  tests carrying `@Verifies` for it follow the rename in the same change.

### D2 — Narrowing to limited access withdraws; only revoking withdraws nothing

`photo-access` already requires that photos outside a new selection are withdrawn, and the app does so. The
`photo-sharing` scenario "Revoked access withdraws nothing" said "revoked or temporarily narrowed"; "narrowed" is
dropped. Revoking (no access at all) and an unreadable library still withdraw nothing.

### D3 — The preview counts photos waiting for the event's start

Before an event starts, the member's in-range photos from before the start wait and are shared once it has passed
(requirement "Nothing is shared before the event starts"). The join preview answers "how many photos will be shared",
and counts them; the upload and the member's own progress leave them out until the start. So the four sets are equal
once the event has started, which the requirement now says, with a scenario for the wait.

- **Rejected: make the preview count only what is uploadable now.** Before the start it would read 0 for a member
  whose 12 photos will all be shared — a wrong answer to the question the row asks.

### D4 — The small corrections

- The album title is matched ignoring surrounding spaces as well as case: an app that pads its album title is still
  caught, and a user's "Signal Hill Hike" album still is not.
- The video floor compares pixel counts, so the spec says "fewer pixels than 1280 × 720", with a scenario for a video
  narrower than 1280 that is still shared.
- "These floors SHALL be fixed values, never derived from the device's camera" stated a mechanism; the outcome a
  member can rely on is that the floors are the same on every phone.
- "because Android does not tell the app that a photo was edited" is rationale; it moves here: Android exposes no
  edited flag, so the edit exemption cannot apply there.
- "from the device's next upload pass" named a mechanism; the scenarios already say "the next time the device looks at
  the library", and the requirement now says the same.

## Risks / Trade-offs

- [A `@Verifies` naming the old requirement title no longer resolves] → the rename updates the three tests that name it
  (`SelectionIsTheWalkIntegrationTest`, `LimitedSelectionIntegrationTest`, `PhotoAccessIntegrationTest`), and
  `verifiesReport` must still count the requirement verified.
- [New scenarios with no test] → the 1100 × 900 video, the re-added selection photo, the de-selected photo returning on
  widening, and the preview before the start each get a `@Verifies` test that passes against the current app — they
  describe it, so a failing one means the analysis was wrong and the spec must not be archived as written.
