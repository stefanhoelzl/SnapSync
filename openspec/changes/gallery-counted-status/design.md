## Context

The status screen is a snapshot projection of the engine's ledger (design.md §2.3–2.4). Today it
shows a five-state classification rendered as an icon + a fraction-shaped ring with **no numbers**
(`sync-status-screen` forbids textual counts), and the progress denominator is the ledger alone:
`(completed + failed) / (pending + completed + failed)`. Because the ledger is written only by the
background upload extension, a freshly-taken photo is invisible to the screen until the extension next
runs, discovers it, and records a `REQUESTED` row.

Two facts from the recent rebase shape this change:
- The ledger is already **asset (photo)-based**: `aggregates()` counts photos, not resource rows — a
  photo is `completed` only when all its resources are `COMPLETED` (`sync-ledger`). No ledger change is
  needed here.
- The app pivoted to **event-scoped contribution** (design.md): it uploads photos with capture date ≥
  event start. The in-scope filter is **design-only so far** — discovery still enumerates the whole
  library — so this change measures the whole library too, deferring scoping (see Risks).

We want a screen readable in one beat: "n of N images synced" / "N images synced", with `N` from the
**live photo library** so it is honest before the ledger catches up.

## Goals / Non-Goals

**Goals:**
- Show textual counts: `n of N` while syncing, `N` when complete.
- Make `N` reflect the live library immediately (independent of extension/ledger lag).
- Collapse the five-state model to three legible states + a two-color LED indicator.
- Keep the design-system rules intact (no color/appearance in `App*` signatures).
- Keep every state forgeable in the JVM harness without a device.

**Non-Goals:**
- Event-scoped (capture-date / media-type) filtering of the count — deferred; `N` is whole-library
  today, moving in lockstep with discovery when scoping lands.
- App-side reconciliation of `n` against the live library (we trust the extension's prune for deletes).
- Staleness detection ("waiting for system") — a separate later slice (design.md §2.4).
- Per-photo or byte-level progress; estimates of any kind.

## Decisions

### D1: `N` comes from a new `gallery-status` seam, not the ledger
A new `:domain:gallery` module defines `GalleryStatusSource { val size: StateFlow<Int> }`, a structural
twin of `:domain:permission`'s `PermissionStatusSource`. `:domain:status` depends on it at
**implementation** scope so gallery types never reach presentation's classpath. iOS backs it with a
PhotoKit count; the JVM harness backs it with a settable fake.
- **Why a new module over inlining in `:domain:status`?** The repo favors small single-purpose seams
  with compiler-enforced boundaries; permission earned its own module for the same "one StateFlow"
  shape. A standalone module keeps `:domain:status` about *projection*, not seam-ownership, and lets
  iOS implement the seam without depending on the projection module.
- **Why not fold into `:domain:permission`?** Permission is *authorization*; gallery is *content*. They
  share a trigger (`photoLibraryDidChange`) but not a purpose, and the gallery count will later need the
  event start date (which permission has no business knowing).
- **Payload is a bare `StateFlow<Int>`** for now (it is literally one number), with `GalleryStatusSource`
  named generically so the type can grow (e.g. an in-scope vs total split) without reshaping the seam.

### D2: Classification is gallery-N vs completed-n; `pending` is ignored; `n` is clamped to `N`
`SyncProgress` gains `total` (= gallery `N`). State, in order: `N == 0 → NOTHING_TO_SYNC`;
`min(n, N) ≥ N → COMPLETE`; else `IN_PROGRESS`. `n` = ledger `completed` (photos).
- **Why ignore ledger `pending`?** With `N` live, a ledger row still `pending` for a photo already
  deleted from the library (not yet pruned) would otherwise pin the screen to "in progress" forever.
  Census `N` already dropped that photo, so driving the denominator off the gallery and the numerator
  off completions sidesteps the stuck-pending trap. Alternative — requiring `pending == 0` for complete
  — was rejected for exactly this reason.
- **Why clamp `n` to `N`?** A photo uploaded then deleted stays `COMPLETED` in the ledger until the next
  extension prune, while `N` drops instantly → `n > N`. Clamping shows "N of N / complete" instead of a
  nonsensical "6 of 5"; it self-heals on the next prune. Alternative (show raw `n`) leaks the lag as a
  weird number.

### D3: Trust the extension's prune for deletions (no app-side reconcile)
`n` counts ledger completions as-is; we do **not** intersect the ledger against the live library in the
app. The deletion paths already in place — `deleteByAssetId` (incremental removals) and `retainAssets`
(full-enumeration reconcile) — keep deleted photos out of the count once the extension runs. The clamp
(D2) covers the brief window before it does.
- **Why not reconcile app-side?** It would require the ledger to expose the completed-asset *set* (not
  just a count) and the gallery seam to expose the live *set* (not just a size), then intersect — a much
  larger seam for a transient cosmetic discrepancy the clamp already neutralizes.

### D4: Three states, LED indicator, textual counts
`SyncState = { IN_PROGRESS, COMPLETE, NOTHING_TO_SYNC }`. `SUSPENDED` removed (the setup gate already
shadows every non-`GRANTED`/not-joined case — `setup-gate` reduces to `UiState.Setup` whenever permission
≠ GRANTED or config is absent, so SUSPENDED was never user-visible). `NEVER_SYNCED` removed (folds into
"in progress 0 of N" or `NOTHING_TO_SYNC`). `INCOMPLETE` removed (unreachable: `failed ≡ 0`).
- The indicator collapses to a **two-color LED dot** carried by a slimmed semantic `StatusIndicator`
  (`InProgress` → yellow, `Complete` → green); color lives only in the Material 3 skin
  (`:domain:ui:components`), never in an `App*` signature. `NOTHING_TO_SYNC` uses the **green** dot
  ("nothing outstanding"); the text carries the nuance. `Loading` shows **no dot** + "Loading …".
- **Why text-only over a determinate ring + text?** During discovery lag `n` is frozen but correct; a
  ring reads as "stuck", a spinner would fake motion the app can't verify (the extension runs on the
  OS's schedule, not the app's). The number is honest whether moving or frozen. We kept a glanceable LED
  for at-a-distance state, dropped the ring.
- **Headline collapses** to dot + one count line. `COMPLETE` keeps a muted relative-time second line.

### D5: `LedgerSyncStatusSource` combines three inputs; `Loading` until all three first-emit
The factory takes `LedgerWatcher`, `PermissionStatusSource`, **and** `GalleryStatusSource`. `combine`
emits its first `Ready` only after every input has produced a value, which is exactly our "Loading
until ledger and gallery first-emit" rule — no extra gating logic. `active = permission == GRANTED`
stays computed here (unchanged), though gating is enforced by the presentation gate (`setup-gate`).

## Risks / Trade-offs

- **[N and discovery can diverge once event-scoping lands]** If discovery starts filtering by
  capture-date/media-type while the gallery count stays whole-library, `n` (filtered) never reaches `N`
  (unfiltered) → permanent "in progress". → Mitigation: when scoping is implemented, the date+image
  predicate MUST be a single shared helper feeding both discovery and `GalleryStatusSource`. Tracked as
  an explicit dependency; this change deliberately keeps both unfiltered so they already match.
- **[Transient n > N on delete]** Covered by the clamp (D2); resolves on the next prune cycle.
- **[Missed `photoLibraryDidChange` while the app was dead]** `N` could be stale on cold start. →
  Mitigation: refresh on foreground and on join, not only on the change callback.
- **[Stance reversal]** Deleting the "no textual counts" rule is a deliberate bet that legible numbers
  beat a fraction ring. Recorded in the proposal's Why so it does not read as an oversight.

## Open Questions

- None blocking. The event-scoped predicate sharing (Risks #1) is a known future dependency, not an
  open decision for this change.
