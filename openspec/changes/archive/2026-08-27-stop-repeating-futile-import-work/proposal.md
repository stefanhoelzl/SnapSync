## Why

The download/import path repeats platform work whose answer cannot change, without bound and without
anyone noticing. Two instances, same defect class, found in one investigation:

**Adjudication asks about rows it already knows the answer for.** `adjudicateUnconfirmed()` runs at the
top of `reconcile`, `importReady` **and** `onResourceStaged` — the last firing once per downloaded
resource. Measured on an iPhone XS (Bugsink `SNAPSYNC-23`, build 607; reproduced 2026-08-25 on build 609):
a 131-asset burst produced **1,164 adjudication verdicts, 1,149 of them discarded** as
`absent, but its import is in flight`. Each one is a synchronous XPC round-trip into `photolibraryd`
asking about the import currently running, answered "not committed yet" because the transaction is still
open, and thrown away one line later under the lock. ~97% waste, scaling with burst size.

**A resource that cannot be imported is retried forever.** There is no attempt count, no terminal-failure
state, and no log line. `photo-download` already names the outcome — *"permanently unimportable and
permanently retried, and the photo never arrives"* — but nothing implements a way to stop. Each retry is
a full PhotoKit transaction, once per trigger, for the life of the install.

Neither misbehaves. Both burn IPC and background-wake residency, and both fill `debug.log` — which is the
concrete cost already paid, because the 1 MiB diagnostic dump that `SNAPSYNC-23` was investigated through
was largely one repeated line.

**This is not a crash fix.** `SNAPSYNC-23` is titled "crashed" and the app was killed twice in the
background mid-burst, but a deliberate reproduction with a heavier burst (131 assets, 7.79 MB each,
1,122 MB, 111 minutes across background wakes) did not die.

## What Changes

- **Adjudication runs once per process, from one call site.** The calls in `reconcile`, `importReady` and
  `onResourceStaged` are removed; a single sweep runs at process start, after the permission
  subscriptions are installed, and is followed by a drain. No latch, no memoisation, no re-arm — it runs
  once because there is one call site.
- **The sweep's stated purpose changes from rescue to confirmation.** A commit survives the death of the
  process that opened it (measured), so the post-death case is normally *present*: settle the row,
  release its bytes, unpeg the status line. The *absent* branch remains, for the narrow cases that need
  it, but it is no longer the reason the sweep exists.
- **An import that cannot succeed becomes terminal, and says so at `Error`.** A resource whose bytes
  PhotoKit rejects settles as permanently unimportable instead of being retried on every trigger. The
  give-up is reported at `Error` severity so it reaches Bugsink — a photo that will never arrive must be
  visible, not silent.
- **Resources are handed to PhotoKit with `shouldMoveFile = true`.** An importing asset stops occupying
  its bytes **twice** — staged file plus library copy — for the window between the commit and the
  client's own release. That window is where a device short of space fails with
  `PHPhotosErrorNotEnoughSpace`, because the library needs room for a full second copy at that instant.
  (It does **not** shrink the staging backlog: files already downloaded and waiting to import occupy the
  same space either way.) The consumed file is also the honest terminal signal the give-up rests on:
  bytes gone with no asset created means there is nothing left to retry with. **BREAKING** for the
  staged-byte lifecycle — PhotoKit consumes the file at ingest, while the row is still claimed and
  unconfirmed, which the current contract forbids.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `photo-download`: the adjudication cadence and the guarantee's shape ("no import runs in a process
  until that process has swept", replacing "every entry point adjudicates first"); the batched-lookup
  cadence sentence; the claimed-refs paragraph, which still asserts the measured-false premise that a
  transaction cannot outlive its process; the rationale for *present* releasing a claim; a new
  requirement that an unimportable resource settles terminally and is reported at `Error`; and the
  staged-byte release rules, which move semantics change.
- `download-store`: a terminal state for a row whose resources cannot be imported, and the staged-byte
  requirement ("released only once their row is settled"), which PhotoKit's ingest-time move contradicts.

## Impact

- `domain/` `feature/download/DownloadController.kt` — the three adjudication call sites, the drain's
  failure handling, and the new terminal settlement.
- `domain/` `compose/SnapSyncApp.kt` — the single startup sweep, ordered after
  `installPermissionSubscriptions()`.
- `domain/` `ports/DownloadStore.kt` + `adapter/generic/app` SQLDelight schema and queries — the terminal
  state and its migration; `adapter/generic/fake` mirrors it.
- `adapter/ios/app-only/IosPhotoLibraryImporter.kt` — `shouldMoveFile`, and the failure path that must
  no longer assume the staged file survives.
- `test/integration`, `adapter/generic/fake` `commonTest` — the cadence, the terminal settlement, and the
  ordering guarantee.
- No change to `ImportedAssetPresence`, its permission-aware router, or the in-flight gate; the gate
  becomes structurally satisfied at the sweep rather than merely correct, and stays as defence in depth.
