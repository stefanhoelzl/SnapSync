# Design — move-features-upload-membership-status-trust

## Context

Migration step 5 (PLAN.md "features I"). Steps 0–4 left `:domain` with `model/` + `ports/` and
all platform impls in `:adapter:*`; the feature-blindness and flow-no-ports gates sit PENDING,
armed by the first file under `domain/src/*/kotlin/**/feature/`. This step creates that zone.
Executed as whole-file `git mv`s; bodies byte-identical, `package`/`import` lines only (the one
named exception: the `ledgerBackend`→`ledgerStore` rename in the four `app/ios` root files this
diff already touches, per 3b's advisory).

## Goals / Non-Goals

- Goal: `feature/upload`, `feature/membership`, `feature/status`, `feature/trust` populated;
  both pending gates armed and verified red-on-violation; `StatusEngineBoundaryTest` retired;
  the 3a-D3 LedgerStore seat debt paid; zero behavior change.
- Non-goals: download/album/creation features (step 6), flows/compose (7–8), UI re-homing (9),
  module deletions (6), any body edit.

## Decisions

### D1 — Feature layout: `app.snapsync.feature.<name>`, one package per feature
The gates key on the zone-named path segment (`feature`) and classify references by
`app.snapsync.…` dotted paths, taking the segment after `feature` as the feature name — so the
directory and the package must both be `…/feature/<name>/`. Flat per-feature packages, matching
3a's D2 (sub-structuring deferred to owning steps).

### D2 — `Logger.invocation` + `LogContext` move to `model/` (forced; the step's law tension)
`UploadArm` and `BackgroundUploadPump` call `log.invocation` (PLAN names both for this step;
bodies may not change). The feature-blindness gate forbids a feature file referencing
`app.snapsync.logging` (legacy), and `:domain` declares zero project dependencies, so the helper
cannot stay in `:domain:logging` and be visible to feature code — it must move INTO `:domain`.
`invocation` compiles against `LogContext`, so they move together, to `model/` (the only zone a
cross-feature helper may live in; `ports/` is interfaces-only by law).

The tension, stated for the reviewers rather than hidden: `LogContext` is a process-global
mutable holder (`object … var current`), and the State-and-authority law says `:domain` holds no
global mutable state. Why this is the least-wrong interim and not a precedent:

- It is *forced by the plan's own ordering*: PLAN step 6 deletes `:domain:logging`, so its
  commonMain surface must enter `:domain` by then regardless (the download feature's
  `DownloadController` hits the identical wall at step 6). Step 5 merely does it for the two
  files this step's own moves require.
- The lawful end-state already exists on record: D4 of `establish-target-architecture` puts
  invocation logging in `compose/` flow decorators (step 8), at which point feature bodies stop
  calling `log.invocation` and this helper leaves the hot path of the law.
- `LogContext` carries its own forcing proof in its KDoc: Kermit's `LogWriter.log` is a plain
  synchronous callback with no coroutine context, so a plain global is the only form the writer
  can read from any thread — an API-contract citation, with the trade-off measured and accepted
  (dev-only diagnostic, serial iOS entry points).
- Kill-test: no fact is lost on process death — the value is a diagnostic label with no
  authority; it fails no recoverability test.

There is no gate for the state law yet (it arms at 13b), so nothing turns red; the beacon
counts are unchanged. The violation is visible, documented here, and scheduled to dissolve.

### D3 — The ledger trio separates (pays 3a's D3 debt)
`SyncEngine` + `LedgerWriter` → `feature/upload`; `LedgerStore` → `ports/`. This is exactly the
resolution 3a's D3 recorded ("the trio separates at step 5 when `feature/` exists and the
writer/service move there; `LedgerBackend` takes its `ports/` seat then"). The chain
`SyncEngine → LedgerWriter → LedgerStore` becomes feature → feature → port, which every gate
accepts; nothing remaining under `model/` or `ports/` references any of the three (verified by
scan before the move). The upload feature is the single writer, so seating the writer and the
engine in `feature/upload` is also the single-writer placement the "Rules in features" law wants.
`establish-target-architecture` D3's "SyncEngine lives in model/" described a target in which the
service does not hold the store; until a later step reshapes that constructor, the armed gates
make `feature/upload` the only lawful seat — the same reasoning 3a recorded.

### D4 — `clearRequestedOffMain` re-homing (the human-eyes item)
- **Old home**: `capability/membership/src/commonMain/kotlin/app/snapsync/membership/ClearRequested.kt`
  (package `app.snapsync.membership`).
- **New home**: `domain/src/commonMain/kotlin/app/snapsync/feature/upload/ClearRequested.kt`
  (package `app.snapsync.feature.upload`).
- **Why upload, not membership**: its one production caller is the PhotoKit tier's
  `UploadProducer` (`PhotoKitUploadProducer.stop()` in `app/ios`) and the state it clears is the
  upload ledger's `REQUESTED` rows via `LedgerStore.clearRequested` — upload-arm mechanism
  repair, not membership lifecycle. Seating it beside the cycle/arm keeps the ledger's
  reset-family calls inside the single-writer feature.
- **Threading semantics preserved, precisely**: the function body is byte-identical. It remains
  `suspend`, still takes `dispatcher: CoroutineDispatcher = Dispatchers.Default` and wraps the
  whole retry loop in `withContext(dispatcher)` — so the synchronous SQLite `DELETE` still runs
  off the caller's thread (Kotlin/Native has no `Dispatchers.IO`; `Default` was and remains the
  chosen pool), is still awaited to completion by the caller (no fire-and-forget — the §7.1
  race this function exists to prevent), still retries `DEFAULT_CLEAR_ATTEMPTS = 3` times, and
  still returns `Boolean` instead of throwing. The move changes only which module compiles it;
  the call site (`PhotoKitUploadProducer`) changes only its import line, so the
  disable → *awaited clear* → re-enable ordering on the main-scope caller is untouched.

### D5 — `ExtensionReconciler` seats in `feature/upload` (not `feature/membership`)
PLAN calls this "single-writer placement: upload". The reconciler performs the ledger's
`resetTo` clear-and-seed and the leave-side marker clear — writes to the upload feature's
durable state. In `feature/membership` it would be a second feature writing the upload ledger,
violating "exactly one writer feature per durable port". Its tests and their `FakeLedgerStore`
fixture move with it (same package, fixture referenced without imports).

### D6 — `DownloadStatusSource` stays in `:domain:status`
It is the download arm's read-model (real impl: `:capability:download`'s
`StoreDownloadStatusSource`, moving at step 6), consumed by presentation. In `feature/status` it
would make the step-6 download feature reference a sibling feature. PLAN scopes step 5's status
move to "the ledger→SyncStatus projections", which this file is not.

### D7 — Tests that stayed behind (and why)
- `LedgerBackedSyncStatusSourceTest`, `OwnDeviceGalleryStatusSourceTest` stay in
  `:domain:status` commonTest: they drive the moved sources through `:domain:gallery`'s
  in-memory fakes (`InMemoryGalleryStatusSource`, `InMemoryPhotoLibrary`), and `:domain` has no
  project dependencies to reach them. Same shape as 3a's D9. They gain imports of the moved
  subjects; the fakes move at step 6/10, and the tests follow then.
- `DeviceAttestationTest` stays in `:capability:attest` commonTest: it constructs
  `InMemoryAttestStore`, which stays in that module's commonMain as the honest double bound for
  `:adapter:fake` at step 10 (moving a production-visible fake into a test source set would
  change the module's API for no migration gain).
- `UploadPushReceiverTest` stays with its subject in `:capability:upload`.

### D8 — Adapter interim edges after this step
- `:adapter:ios:ext-safe`: `api(":domain:logging")` **deleted** (LogContext now arrives via the
  existing `api(":domain")`; the two writers gain an explicit `import app.snapsync.model.LogContext`
  — they shared the `app.snapsync.logging` package before, so no import line existed to rewrite).
  `api(":capability:album")` and `api(":domain:gallery")` **stay**, re-documented: the album
  seams and the `ResourceEnumerator` composition move at step 6.
- `:adapter:ios:app-only`: `implementation(":domain:logging")` **deleted** (its two adapters
  import `invocation`, now `app.snapsync.model.invocation` via `api(":domain")`).
- `:capability:upload` and `:capability:download` likewise drop `:domain:logging`;
  `:capability:upload` also drops its vestigial `:domain:engine` + `:domain:gallery` deps
  (their last uses moved to `model/` at 3a — the build file's comment predates that).

### D9 — `StatusEngineBoundaryTest` retired in this diff
The moved status sources leave `:domain:status` with one production file; the Konsist guard's
non-vacuity twin (`files.size > 3` + `LedgerBackedSyncStatusSource` in scope) would fail `build`.
The armed feature-blindness gate supersedes it for the sources that mattered: a `feature/status`
file referencing `app.snapsync.engine` (or any legacy/sibling package) is a violation by text
match, fully-qualified included, with the zone gate's own non-vacuity contract. Spec delta:
`architecture-guards` REMOVED requirement.

### D10 — Name sweep (3b advisory)
`ledgerBackend` → `ledgerStore` in the four `app/ios` root files this diff touches for imports
anyway (`SnapSyncRoot`, `UploadExtensionRoot`, `UrlSessionUploadController`,
`PhotoKitUploadProducer` — locals/privates only). **Deliberately left**: `test/world`'s public
`World.ledgerBackend` property — renaming it would touch eight world/integration test files this
diff otherwise leaves alone, inflating a byte-reviewed diff for a naming advisory; it rides a
step that touches the world (10).

## Risks / Trade-offs

- [Global mutable `LogContext` inside `:domain`] → documented above (D2); dissolves at step 8;
  no gate regresses.
- [Import-sweep breadth (~25 consumer files)] → compiler-verified on both JVM and iOS metadata;
  string literals (`"app.snapsync.upload.heartbeat"` etc.) protected by per-symbol import
  rewrites, never blanket package sed; `RuntimeIdentityTest` pins them regardless.
- [Same-package references made cross-package by a move] → the three model-trio files gain
  explicit imports; compile is the proof.

## Migration Plan

Single PR (branch `arch`, RUN.md model: implementer never commits). Rollback = revert the diff;
no durable state, schema, or identity string moves.
