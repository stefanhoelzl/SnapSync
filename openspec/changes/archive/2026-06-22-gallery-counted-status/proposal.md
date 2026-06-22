## Why

The status screen today shows a **fraction-shaped ring with no numbers** (the spec forbids textual
counts) over a five-state classification (`NeverSynced`/`InProgress`/`Suspended`/`Complete`/`Incomplete`)
that is hard to read at a glance and whose denominator is the ledger alone — so a freshly-taken photo
isn't reflected until the background extension discovers and records it. We want a screen a user can
read in one beat: **"n of N images synced"** while syncing, **"N images synced"** when done — with the
total `N` taken from the **live photo library**, so the count is honest the instant a photo is added,
before the extension touches the ledger.

## What Changes

- **NEW** a `gallery-status` capability: a `GalleryStatusSource { val size: StateFlow<Int> }` seam in a
  new `:domain:gallery` module, giving `:domain:status` the live photo count (`N`). iOS backs it with a
  PhotoKit count (whole-library today, matching current discovery); the JVM harness backs it with a
  settable fake so every state is forgeable. It re-emits on `photoLibraryDidChange`, foreground, and
  join — the same invalidation-ding shape as the permission seam.
- **BREAKING (spec stance reversal)** the status screen now shows **textual counts** ("12 of 47 images
  synced"). This deliberately **deletes** the current `sync-status-screen` rule *"item counts and
  progress bars MUST NOT appear as text anywhere on the screen."*
- The status indicator collapses from an icon-zoo + progress ring to a **single LED-style dot**:
  yellow = in progress, green = completed (color lives only in the Material 3 skin; the `App*`
  indicator stays semantic). No headline, no progress ring, no estimate line. Loading shows no dot.
- `SyncState` is reworked from five states to **three** — `IN_PROGRESS`, `COMPLETE`, `NOTHING_TO_SYNC`:
  - `SUSPENDED` is **removed** — the setup gate already shadows every non-`GRANTED` / not-joined case
    (verified against `setup-gate`); it was never user-visible.
  - `NEVER_SYNCED` is **removed** — folds into "in progress 0 of N" (photos exist, none done) or
    "nothing to sync yet" (N == 0).
  - `INCOMPLETE` is **removed** — already unreachable under retry-forever (`failed ≡ 0`).
- `SyncProgress` gains a `total` (the gallery `N`). `LedgerSyncStatusSource` combines **three** inputs
  (ledger × permission × gallery) instead of two; `combine` naturally holds `Loading` until all three
  first-emit (our "Loading until both ledger and census" rule, for free).
- Classification is driven by **gallery-N vs completed-n**, with ledger `pending` ignored and `n`
  clamped to `N`: `N == 0 → NOTHING_TO_SYNC`; `n ≥ N → COMPLETE`; else `IN_PROGRESS`. Clamping means an
  uploaded-then-deleted-but-unpruned photo reads "N of N / complete", never a nonsensical "6 of 5".
- `Complete` keeps the relative last-sync time ("· 5 min ago") as a muted detail.

## Capabilities

### New Capabilities
- `gallery-status`: the live photo-library count seam (`GalleryStatusSource`) in `:domain:gallery`,
  consumed by `:domain:status` to supply the sync total `N`; platform-backed (PhotoKit) on iOS, a
  settable fake on JVM; re-emits on library-change / foreground / join.

### Modified Capabilities
- `sync-status`: `SyncProgress` gains `total`; `SyncState` reworked to three states
  (`IN_PROGRESS`/`COMPLETE`/`NOTHING_TO_SYNC`); classification driven by gallery-N vs completed-n
  (clamped); `LedgerSyncStatusSource` combines a third (gallery) input and holds `Loading` until all
  three sources first-emit.
- `sync-status-screen`: deletes the "no textual counts" rule; renders "n of N images synced" /
  "N images synced"; replaces the icon/ring with a two-color LED dot; drops headline, estimate, and the
  NeverSynced/Suspended/Incomplete rows; Loading waits on the gallery source too.

## Impact

- **New module** `:domain:gallery` (interface + JVM fake), wired into `:domain:status` at
  implementation scope (twin of `:domain:permission`); iOS impl in `:app:ios`, harness fake in
  `:app:desktop`.
- **Modified** `:domain:status` (`SyncProgress`, `SyncState`, `LedgerSyncStatusSource`),
  `:domain:presentation` (`UiState` reduction, removed estimate/never-synced handling),
  `:domain:ui` (`StatusScreen`/`StatusHero` LED layout) and `:domain:ui:components` (LED skin).
- **Unchanged** `:domain:engine` / `sync-ledger` — aggregates are already asset (photo)-based; no
  ledger change. `setup-gate` unchanged — it already covers all non-`GRANTED` cases.
- **Harness** `:app:desktop` control panel gains a settable gallery-size control alongside the engine
  console, so discovery-lag (N > n) and overshoot (n > N) states are forgeable.
- **Tests** `:test:integration` gains the gallery source to the assembled stack; `sync-status` and
  `sync-status-screen` test suites updated to the three-state model and count text.
