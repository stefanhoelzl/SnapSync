# Proposal: redesign-event-ui

## Why

A user-approved UI redesign — 11 design rounds, an independent 4-lens review, and fixes, all
signed off — has shipped as a working PoC (`ui/screens` + `ui/components`). The specs still describe
the **old** join/create surfaces: a three-way direction segmented control, a two-preset cutoff
selector with the free date/time picker removed, a nested album checkbox, an `AppExplainer`
component, and Material 3 `DatePicker`/`TimePicker`/clock-dial internals. Every one of those is now
false. Specs are the contract of record and must not lie, so the deltas rewrite the contradicted
surface requirements to match the settled code.

The redesign is not a reskin. It removes engineering vocabulary that nine independent reviewers
flagged (the `Both` / `Upload only` / `Download only` labels), and it re-frames the two decisions a
guest actually makes as plain on/off switches — from which the participation **direction** is
**derived**, never named. The domain contract is untouched: `UiState`, `JoinEvent`, and the
`(cutoff, Direction, saveToAlbum)` the confirm crosses are exactly as before.

## What Changes

**join-event** (the join surface):
- The direction selector is **removed**. Direction is derived from a **Share** switch × a **Receive**
  switch: share+receive → `Both`, share only → `UploadOnly`, receive only → `DownloadOnly`. Both off
  is representable and **blocks Join with a stated reason** — a switch never silently flips the other.
- Share OFF hides the cutoff entirely.
- The cutoff gains a third option: **Now** · **Event start** · **Custom** (default Event start).
  Custom opens a floored date+time picker directly; only OK commits; cancel restores the prior choice.
  The resulting instant is stated **once, bold**, in the Share section.
- The photo-access explainer now **names the event** (hero continuity Loading → Explain → Ready →
  Committing) — reversing the PoC-era anonymity, while keeping the three consent facts and the
  CTA-only "I understand" route to the system dialog.
- The switch-events dialog now states the participation reset in its body.

**event-album**: the opt-in is a **standalone** minor section ("Create an album"), not nested under
either switch (the album mirrors both own uploads and foreign downloads), with a note that
adaptively names exactly the feeds the current switches produce.

**event-creation-ui**: a host-framed hero, one question over the name field, the start date as a
stated-consequence card, and failures in an **error banner above Create** (never a reddened,
client-guarded field).

**photo-selection-policy**: the join-time floor clamp now also covers the **Custom** interactive
pick, which the UI additionally enforces at pick time (pre-floor days unselectable + commit coerces up).

**design-system**: the switch-header section, the recessed sub-section well, the standalone minor
section, the trailing-checkmark toggle row, the hand-drawn iOS-metric switch, the drawn app mark and
event-hero headers, the drawn calendar + time-wheels picker (replacing the M3 `DatePicker`/`TimePicker`),
the three-option cutoff choice rows, the error banner, the join-gate notice/consent-fact pieces, an
optional dialog `body`, and cross-cutting truthfulness/accessibility/contrast rules. `AppExplainer`
is **removed**.

## Impact

- Specs: `join-event`, `event-album`, `event-creation-ui`, `photo-selection-policy`, `design-system`.
- No delta: `event-creation` (backend untouched), `sync-status-screen` (the joined layer — name, QR,
  status line, share/leave — is unchanged in contract), `photo-download` (a distinct receive-progress
  line was considered and rejected; recorded as a non-goal in `design.md`).
- Code: the PoC is the settled truth; this change is PROPOSE-stage only and edits **no** production
  code. Productionizing (green tests, dead-component sweep, on-device verification, marketing
  screenshots) is enumerated in `tasks.md`.
- Two `## Open Questions` are recorded, not resolved (see `design.md`): whether the domain/backend
  should accept or reject a nothing-membership, and whether `UiState.CreateEvent.error` should carry
  a typed `CreationFailureReason`.
