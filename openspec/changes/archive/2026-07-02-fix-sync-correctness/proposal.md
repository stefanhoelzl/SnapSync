## Why

`docs/sync-refactor.md §7` collected a set of **pre-existing correctness bugs** in the sync path —
found during the design interview for the upload-orchestration relocation, but independent of it. Two
are verified live/latent bugs (a `clearRequested` race that deletes freshly re-enabled `REQUESTED`
rows; a `reconstruct` path that writes a phantom `assetId=""` `COMPLETED` row), the rest are
robustness and hygiene fixes. They are prerequisites for the relocation (change 2,
`relocate-upload-cycle`) and the walk-seam (change 3, `add-rawasset-walk-seam`) but must land first
and on their own, because they alter behavior while the moves are behavior-preserving. This change is
**§7 only** — no module relocation, no new seam.

## What Changes

- **Fix the `clearRequested` re-enable race** (`SnapSyncRoot.disableExtension()`). Today it does
  `scope.launch { ledgerBackend.clearRequested() }` on a `Dispatchers.Main` scope: a synchronous
  SQLite write on the main thread (hang risk), fire-and-forget, that races the immediately-following
  `setUploadJobExtensionEnabled(true)` — so the re-enabled extension's fresh `REQUESTED` rows can be
  deleted by the still-running clear. Make the disable helper **suspending, awaited, off-main
  (`Dispatchers.Default` — not `IO`, which does not exist on Native), bounded-retry, and completing
  before the re-enable.** Applies to both disable paths (re-register and leave).
- **Fix `reconstruct`'s phantom `COMPLETED` row** (`UploadCycle.reconstruct`, `UploadCycle.kt:153`).
  `entry?.assetId ?: ""` writes an `assetId=""` row when the ledger row was pruned. Derive `assetId`
  from the job key via the **shared** `assetIdFromUploadKey` parser and gate the completion record on
  a recoverable key.
- **Extract `assetIdFromUploadKey` to a single shared home** (`:domain:gallery`, the owner of its
  inverse `uploadKey`) so `reconstruct` and `ExtensionReconciler` call one implementation instead of
  the current private duplicate in `Reconciler.kt:111`.
- **Bound reconcile's device `LIST`** with an explicit `withTimeout` (defer-on-timeout, mirroring the
  12s manifest guard); keep `resetTo(listing)` a single atomic transaction (only the network call is
  bounded). Optionally keep the cheap `defer iff empty listing && ledger has COMPLETED` guard against
  a same-session-switch transient.
- **Narrow the extension's suppression linkage** to a read-only `SuppressionSource` type (exposing
  only `suppressedLocalIds()`), not the full `DownloadStore` interface.
- **Pin the two now-load-bearing string contracts with tests:** the `assetId`↔`createdLocalId`
  `'/'→'_'` normalization (suppression match) and the `uploadKey`↔`assetIdFromUploadKey` round-trip
  (reconstruct). Interim test home (`:test:integration` does not exist yet).
- **Delete dead code:** `EventFilesSource`/`HttpEventFilesSource` (superseded by `DeviceFilesSource`;
  only two stale comments reference them) plus those stale comments.
- **Doc-accuracy fixes:** `design.md §2.2`/`§2.4`, the suppression predicate wording, `Role` naming
  (`primary`/`motion` vs code's `live`), the "not harness-reachable" (vs "untested") framing for the
  `:app:ios:photokit-extension` module, and a refresh of the stale `CLAUDE.md` module table.

## Capabilities

### New Capabilities

_None — this change modifies existing capabilities only._

### Modified Capabilities

- `ios-background-upload`: the disable→clear→re-enable path becomes **awaited and off-main, completing
  before re-enable** (was fire-and-forget, racing re-enable); `reconstruct` derives `assetId` from the
  key and gates the completion record on a recoverable key (was a phantom `assetId=""` row); the
  suppression match is specified to normalize `assetId` `'/'→'_'` to meet `createdLocalId`.
- `event-rejoin-reconciliation`: the device-listing fetch is **explicitly time-bounded**, with expiry
  deferring the cycle (no seed, marker unset) exactly as a fetch failure does; `resetTo` stays a
  single atomic transaction; optional empty-listing defer guard.
- `download-store`: the extension's linked suppression surface is a **narrowed `SuppressionSource`
  type** (suppression projection only), not the `DownloadStore` interface.
- `gallery-status`: `:domain:gallery` owns the **single** `assetIdFromUploadKey` parser as the exact
  inverse of `uploadKey` (round-trip guaranteed), consumed by both the reconciler and `reconstruct`.

## Impact

- **Code:** `app/ios/src/iosMain/.../SnapSyncRoot.kt` (disable/re-enable ordering — untested app
  shell; the awaitable logic may move to a tested helper — see design.md);
  `app/ios/photokit-extension/src/commonMain/.../UploadCycle.kt` (`reconstruct`);
  `capability/rejoin/.../Reconciler.kt` (shared parser, bounded `LIST`, optional guard);
  `domain/gallery/.../` (parser home); `domain/download-store/.../` (`SuppressionSource` factory
  wiring); deletion of `capability/rejoin/.../EventFilesSource.kt` + `HttpEventFilesSource.kt`
  (+ `HttpEventFilesSourceTest`).
- **Tests:** new cross-module contract tests (interim home) for the two string contracts; existing
  `UploadCycleTest`/`ReconcilerTest` gain scenarios; these run on JVM + iOS simulator per testing
  rule 1.
- **Docs:** `docs/design.md`, `CLAUDE.md` module table.
- **Not in scope:** the `:capability:upload` relocation (change 2) and the `RawAsset` walk seam
  (change 3). No new module, no seam redesign.
- **On-device verification** required for the threading/consistency fixes — see design.md
  "Must-verify-on-device", carried from `docs/sync-refactor.md §7`.
