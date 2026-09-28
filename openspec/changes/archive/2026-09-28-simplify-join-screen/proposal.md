## Why

The join screen is too complicated. A first-time guest passes two screens before they can join — a
four-point photo-access explainer, then the choices — and on the choices screen the capture range alone
fills the first view with five option rows (three for the start, two for the end), pushing the decisions
that actually matter (receive, the album) below the fold. The same arrangement is repeated on the
settings screen of a joined member. A guest scanning a QR code at a party should see the whole decision
at a glance and join with one tap.

## What Changes

- **One join screen instead of two.** The separate photo-access explanation step is removed. A guest
  whose photo access has never been asked sees one line on the join screen saying iOS will ask for
  photo access next, with an info affordance that shows the same explanation on demand; their confirm
  action reads "Join & allow photos", and tapping it raises iOS's access dialog and then joins whatever
  they answer. Everyone else sees plain "Join". **BREAKING** (to the current contract): iOS's dialog is
  now raised by the join confirmation, not by a separate "I understand" step.
- **The capture range becomes one choice with a calendar.** Instead of separate start and end lists
  (Event start / Now / Custom, Event end / Custom), the member picks from **the whole event**, **from
  now** (only while the event is running), or a **custom range** on a calendar bounded to the event's
  window, with a time for each end. The shared range stays bounded inside the event window exactly as
  today.
- **The deletion date is no longer stated on the join screen.** The event's retention (deleted 30 days
  after it starts) remains the promise it is (capability `event-lifetime`); the app simply stops
  reciting it at the gate. **BREAKING**: the app then states retention nowhere.
- **Settings follow the join screen.** The joined member's settings screen offers the same simplified
  range choice, so the two surfaces stay one decision.
- Unchanged: the share and receive switches (both on by default, never flipping each other), the
  exclusions statement, the live count, the album choice, both-off disabling Join with a reason, the
  confirm-only join, full/invalid/offline handling.
- Layout-only (no contract): share and receive sit in one card, the album becomes a regular switch, the
  range shows as one row with the preset name and the count.

## Capabilities

### New Capabilities

_(none)_

### Modified Capabilities

- `join-event`: the capture-range choice is reshaped (whole event / from now / custom calendar range);
  the explanation-before-iOS-asks requirement becomes an in-screen notice with on-demand explanation
  and a first-timer confirm that raises the dialog; the deletion-date requirement is removed.
- `photo-access`: the deliberate action that may raise iOS's dialog at the join gate becomes the
  first-timer's join confirmation instead of continuing past the explainer.
- `manage-membership`: the settings screen shows a saved range as the whole event or as a custom range,
  replacing the separate Event start / Event end labels.

## Impact

- `:ui:screens` — the join Ready surface, the explainer phase (removed as a phase, kept as a sheet), the
  shared participation sections (one card, album switch, range row), the reconfigure screen.
- `:ui:components` — the range-preset choice rows are replaced by a range row + the existing calendar
  range dialog, extended with preset chips and bounded to the event window.
- `:domain:model` / `:domain:presentation` — the range form's from/until presets collapse into one
  range preset (whole event / from now / custom); the join phases lose the explain-access step and the
  confirm gains the dialog-then-commit sequence for never-asked access.
- Forge harness presets, UI tests, `:test:integration` join journeys, marketing screenshots (the
  `joining` capture changes).
