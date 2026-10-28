# Proposal

## Why

A spec drift audit of the selection concept (phase 4.4a of the core-layers sequence, before its code moves) found the
`photo-sharing` and `photo-access` specs promising things the shipped app does not do, contradicting each other, and
stating mechanism where they should state outcomes. The app's behaviour is the intended one in each case; the specs
are what is wrong, so the contract of record is aligned before the selection code is moved against it.

## What Changes

Wording only; no behaviour changes and no code changes.

- **A photo that comes back after leaving the member's library view is uploaded again.** Re-adding a photo to the
  limited selection, moving it back into DCIM on Android, and widening limited access to full for a photo that was
  de-selected earlier bring it back as a recovered photo does: uploaded again. The promise "without being uploaded
  again" stays for what only falls out of the member's choice — a narrowed range, a messaging-app album, sharing
  turned off. **BREAKING** for the contract: "Switching between full and limited access never re-uploads" no longer
  holds in its absolute form and is renamed.
- **Narrowing access withdraws what falls outside the selection.** The scenario "Revoked access withdraws nothing" no
  longer says "or temporarily narrowed", which contradicted `photo-access`.
- **The preview counts what will be shared, including photos waiting for the event's start.** The four sets the member
  is told are the same are the same once the event has started; before it, the preview also counts the in-range photos
  that wait for the start, which upload once it has passed.
- **Small corrections:** the album title matches ignoring case and surrounding spaces; the video floor is a pixel count
  ("fewer pixels than 1280 × 720"), so a 1100 × 900 video is shared; three sentences that state how or why are
  rewritten as outcomes or removed ("fixed values, never derived from the device's camera" becomes "the same on every
  phone"; "because Android does not tell the app that a photo was edited" goes; "from the device's next upload pass"
  becomes "the next time the device looks at the library").

Not done, though it was considered: adding `changes/archive/2026-10-15-limited-selection-decides` to the two specs'
Purpose — a Purpose carries at most one decision-record cite (`openspec/config.yaml`, SHAPE), and both already have one.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `photo-sharing`: "Media that is certainly not an event photo is excluded" (album match, video floor, the floors'
  wording); "On Android under full access, only photos in the DCIM folder are shared" (rationale removed); "What the
  member is told is shared is exactly what is shared" (the preview before the start); "Deleting or no longer sharing a
  photo withdraws it" (timing wording; narrowed access withdraws); "Restoring or re-including a photo shares it again"
  (which returns upload again).
- `photo-access`: "Switching between full and limited access never re-uploads" — renamed and restated: a switch
  re-uploads only photos that were de-selected earlier, and nothing still shared.

## Impact

- `openspec/specs/photo-sharing/spec.md`, `openspec/specs/photo-access/spec.md`.
- No code. Tests carrying `@Verifies` for a renamed requirement follow the rename (the `photo-access` switching
  requirement), so the verifies report stays whole.
- The selection move (phase 4.4a) is built against the aligned specs.
