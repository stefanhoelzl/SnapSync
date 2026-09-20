## Why

The event album is the only on-device statement that "these photos are event X" — without it an
event's photos scatter into the camera roll, own contributions blending into the timeline and
received photos landing loose beside them. The join surface currently offers it **unchecked**, so
the default outcome of joining an event is the scattering the capability exists to prevent. The
costs are asymmetric: a wrongly-on default leaves one empty, deletable album; the current default
leaves a member who cannot find the event's photos at all.

## What Changes

- The join surface's "Create an album" opt-in **starts checked**. The seed is a single value —
  `RangeForm.saveToAlbum` — beside `shareOn` and `receiveOn`, which already default on.
- **The choice survives as a choice.** The checkbox, its adaptive feed-naming note, its
  dimmed-but-present disabled accessibility behaviour, and the forward-only `reconfigure-membership`
  toggle are all unchanged. Declining stays one tap.
- The creator is included: a mint routes into the same gate, so a host gets an album too.
- Copy is unchanged. The note is already adaptive; its on-state sentence now introduces the row, and
  "No album is created." appears only after a deliberate uncheck — exactly when it informs.
- **The headless `autoJoin` path deliberately keeps its album default OFF.** It is a dev/test path
  whose defaults are minimal and side-effect-free, and the event link's explicit `saveToAlbum=true`
  override already exercises album placement without a tap. Its sibling defaults do mirror the
  surface seeds (cutoff = `startsAt`, direction = `Both`), so the mirror is no longer exact and the
  divergence is stated rather than left for a reader to assume.
- Memberships already joined are **untouched** — no migration. A persisted `false` cannot
  distinguish "never thought about it" from "chose off", and the toggle is forward-only, so flipping
  one would produce a half-album holding only what it syncs afterwards.
- Not breaking: `EventConfig`, the album map, the coordinator's guard, the upload cycle's placement
  stage and the importer's lookup are all unchanged.

## Capabilities

### New Capabilities

_None._

### Modified Capabilities

- `event-album`: the join-surface requirement "The album opt-in is a direction-independent join-surface
  affordance" states the affordance "SHALL **default off** (opt-in)", and its standalone-row scenario
  asserts "and defaults off". Both become default-on, and the Purpose's opening "An **opt-in**,
  per-membership album" is reworded to describe a declinable default rather than an opt-in.

## Impact

- `ui/presentation/src/commonMain/kotlin/app/snapsync/presentation/RangeForm.kt` — the seed.
- `ui/screens/src/commonTest/.../JoinScreenTest.kt` — the default-off case inverts (keeping a
  `saveToAlbum = false` case so the "No album is created." note keeps coverage), and the
  tap-reports-the-choice case now taps from checked.
- Marketing screenshots: the forge's `joining` preset renders the REAL join gate from the real
  `RangeForm()` default, so `screenshots/` may move. `joining` is normally byte-identical between
  runs, so any diff there IS this change; re-dispatch, eyeball, and commit in the same PR only if it
  actually moved.
- Unaffected and deliberately left alone: `StatusContainerHost`'s `autoConfirm` album default,
  `JoinGateIntegrationTest`'s `confirmJoinAs` helper and `World.provision`, which all set the value
  explicitly rather than inheriting the seed; the `event-link` payload and its decoder; every
  downstream album mechanism.
