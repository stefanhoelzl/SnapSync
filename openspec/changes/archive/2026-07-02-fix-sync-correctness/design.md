## Context

This is change 1 of three carved from `docs/sync-refactor.md` (`fix-sync-correctness` →
`relocate-upload-cycle` [Move B] → `add-rawasset-walk-seam` [Move A]). It lands the §7 pre-existing
correctness fixes **before** the relocation, because these alter behavior while the relocation is
behavior-preserving; mixing them would make the relocation PR hard to review as a pure move.

Current state (verified against code, 2026-07-02):

- `SnapSyncRoot.disableExtension()` (`app/ios/src/iosMain/.../SnapSyncRoot.kt:365`) runs
  `scope.launch { ledgerBackend.clearRequested() }` on a `Dispatchers.Main` `SupervisorJob` scope.
  `enableBackgroundUpload()` calls `disableExtension()` then, synchronously, `setUploadExtensionEnabled(true)`
  — so the clear is fire-and-forget and can outlive the re-enable. `clearRequested` is a synchronous
  SQLite `DELETE`; on the main thread it is a hang risk under WAL contention.
- `UploadCycle.reconstruct` (`UploadCycle.kt:149`) builds the completion `Resource` with
  `assetId = entry?.assetId ?: ""`; when the ledger row was pruned (`entry == null`) it writes a
  phantom `assetId=""` `COMPLETED` row.
- `assetIdFromUploadKey` exists **only** as a private function in `Reconciler.kt:111`, deliberately
  duplicated "so the reconciler needs no gallery dependency". `reconstruct` needs the same parse but
  currently does not use it.
- `ExtensionReconciler.reconcile` (`Reconciler.kt:81`) calls `files.list(deviceId)` with no explicit
  time bound; `resetTo(seeds)` is already a single atomic call.
- `SuppressionSource` already exists as a super-interface of `DownloadStore`
  (`download-store/.../DownloadStore.kt:44,53`); the extension's composition root links the store, not
  the narrowed type.
- `EventFilesSource`/`HttpEventFilesSource` (`capability/rejoin`) are dead — the live path is
  `DeviceFilesSource`; the only remaining references are two stale comments.
- The `'/'→'_'` suppression normalization is in `GalleryResourceEnumerator.kt:43`
  (`it.replace('/', '_')`).

## Goals / Non-Goals

**Goals:**
- Land the eight §7 correctness/hygiene fixes as one reviewable change, behavior-corrected and covered
  by tests that run on JVM **and** the iOS simulator (testing rule 1).
- Keep every fix testable in a `domain`/`capability` module; add tests where the fix makes a string
  contract load-bearing.
- Leave the code positioned so change 2 (relocation) is a clean, behavior-preserving move.

**Non-Goals:**
- No module relocation (`:capability:upload`), no `RawAsset` walk seam — those are changes 2 and 3.
- No change to the engine⇄platform seam shape, the ledger schema, or the reconcile algorithm beyond
  bounding the network `LIST` and the optional empty guard.
- No marker→ledger-table migration (dropped after review — `docs/sync-refactor.md §7` closing note).

## Decisions

### D1 — The awaited disable becomes a suspending helper; ordering enforced at the call site
Make `disableExtension()` **suspending** and have `enableBackgroundUpload()` `await` it before
`setUploadExtensionEnabled(true)`. The clear runs `withContext(Dispatchers.Default)` (Native has no
`Dispatchers.IO`) with a small bounded retry loop around the SQLite write. Both disable paths
(re-register and `LeaveEvent`) go through the one helper.

*Where the logic lives.* `SnapSyncRoot` is the untested app shell (hard rule). The **ordering** (await
the clear, then re-enable) is unavoidably app-shell wiring — it sequences two iOS-only platform calls.
But the **bounded off-main clear** (the retry loop + dispatcher) is pure logic; it SHOULD live in a
tested `domain`/`capability` helper the shell calls, so the retry/threading behavior is exercised in
`commonTest`, leaving `SnapSyncRoot` a two-line sequence. `LeaveEvent` (`:capability:rejoin`) is
already a tested use-case that disables the extension; the re-register path is its untested twin. We
keep the retry/off-main helper in a tested module and inject it into both; we do **not** try to move
the `setUploadJobExtensionEnabled` PhotoKit calls out of the shell.
*Alternative rejected:* leaving it all in `SnapSyncRoot` and only awaiting — cheaper, but parks new
testable logic (retry/threading) in the wiring-only layer, violating the hard rule and giving the
race fix no automated coverage.

### D2 — `assetIdFromUploadKey` moves to `:domain:gallery`, next to its inverse `uploadKey`
One shared parser, consumed by both `ExtensionReconciler` and `reconstruct`, pinned by a round-trip
test against `uploadKey`. `:domain:gallery` is agnostic and already JVM+simulator-tested, and it owns
`uploadKey`, so the inverse belongs there.
*Cross-change interaction (important for change 2):* today `UploadCycle` imports **only**
`app.snapsync.engine.*`, and change 2 (`relocate-upload-cycle`) leans on that to make
`:capability:upload` depend on `:domain:engine` alone. Having `reconstruct` call a `:domain:gallery`
parser adds a gallery symbol to `UploadCycle`. In **this** change that is free — `UploadCycle` still
lives in `:app:ios:photokit-extension`, which already depends on `:domain:gallery`. But change 2 must
then either (a) let `:capability:upload` depend on `:domain:gallery` (gallery is agnostic + tested, so
this is clean and preferred), or (b) host the parser in `:domain:engine`. We choose (a)'s enabler now
by putting the parser in gallery and **flag the dependency decision to change 2** rather than
pre-deciding its module graph. *Alternative rejected:* put the parser in `:domain:engine` to preserve
`UploadCycle`'s engine-only imports — but the key **format** is a gallery concern, not an engine one;
polluting the engine's vocabulary to protect a future module boundary is the wrong trade.

### D3 — Bound only the network `LIST`; keep `resetTo` atomic
Wrap `files.list(deviceId)` in `withTimeout` and treat expiry exactly like a failed `Result`: create
no jobs, leave the marker unset, retry next cycle (the existing defer path). `resetTo(seeds)` stays a
single atomic transaction — the insert is ~seconds even at ~100k rows, only the network call needs
bounding. This mirrors the 12s manifest-PUT guard.

### D4 — Optional empty-listing defer guard (kept, cheap)
Keep the simple `resetTo(listing)`; additionally, when the listing is **empty** *and* the ledger
already holds `COMPLETED` rows, defer this cycle instead of wiping to empty — a cheap guard against a
same-session-switch transient where a just-uploaded object is not yet listed. This rests on the
assumption that bunny Storage `LIST` is read-your-writes consistent after recent PUTs (a
must-verify-on-device item); the guard is the conservative fallback if it is not. If verification shows
the assumption holds cleanly, the guard is harmless (rare re-upload is already accepted; `502` defers).

### D5 — Narrow the extension's suppression linkage to `SuppressionSource`
The extension's composition root links a read-only `SuppressionSource` factory exposing only
`suppressedLocalIds()`, not the `DownloadStore` interface. `SuppressionSource` already exists; this is
a wiring/type-surface change that makes the existing "read-only, suppression-only" requirement
compile-enforced.

### D6 — Interim home for the two contract tests
The `assetId`↔`createdLocalId` `'/'→'_'` normalization and the `uploadKey`↔`assetIdFromUploadKey`
round-trip are now load-bearing (suppression match; `reconstruct`). `:test:integration` does not exist
yet, so the round-trip test lives with the parser in `:domain:gallery`'s `commonTest`; the
normalization test lives where both sides are reachable — `:domain:gallery` (enumerator normalizes)
with the `createdLocalId` shape asserted against `:domain:download-store`. Revisit the home when
`:test:integration` is built.

### D7 — Delete dead code, then fix the docs
Remove `EventFilesSource`/`HttpEventFilesSource` (+ their test) and the two stale comments referencing
them. Apply the §7.8 doc-accuracy fixes to `docs/design.md` and refresh the `CLAUDE.md` module table.

## Risks / Trade-offs

- **[The disable/re-enable ordering still has an app-shell seam]** → The `setUploadJobExtensionEnabled`
  calls cannot be tested off-device; only the retry/off-main clear is covered. Mitigation: keep the
  shell to a mechanical two-step sequence and cover the logic in the injected helper; verify the full
  sequence on device (below).
- **[Parser move nudges change 2's module graph]** → `:capability:upload` will need a `:domain:gallery`
  dependency (or the parser rehomed). Mitigation: D2 documents this explicitly so change 2 does not
  rediscover it; gallery is agnostic + tested so the edge is clean.
- **[Empty-guard rests on `LIST` read-your-writes consistency]** → If bunny Storage is eventually
  consistent, the guard is load-bearing rather than belt-and-suspenders. Mitigation: D4 keeps it;
  device verification confirms which regime we are in.
- **[Deleting `HttpEventFilesSource` removes a Darwin-client consumer]** → Confirm `DarwinHttpClient`
  still has a live consumer (`DeviceFilesSource`'s iOS impl) after deletion, and update its comment.

## Migration Plan

No data migration. Behavior-affecting deploy only. Rollback is a straight revert — no schema or
persisted-format change (the dropped marker→table migration is explicitly out of scope). Sequence: land
this change, verify on device, then proceed to change 2 (`relocate-upload-cycle`).

**Must-verify-on-device (carried from `docs/sync-refactor.md §7`):**
- `clearRequested` off-main completes before re-enable under cross-process WAL contention; no
  freshly-enabled `REQUESTED` row is deleted.
- `busyTimeout` (SQLiter default ~5s) is sufficient under Class-C cold-boot; do not "set" it.
- Reconcile completes on a large library (~20–50k) with the `LIST` bounded.
- **bunny Storage `LIST` is read-your-writes consistent after recent PUTs** (the assumption behind D4).
- Keychain `device-identity` survives reinstall.

## Open Questions

- **Contract-test home:** interim placement per D6 — should these fold into `:test:integration` when it
  is built (change scope elsewhere), or stay next to the parser? (Non-blocking.)
- **Empty-guard fate:** keep permanently (D4) or drop once `LIST` consistency is device-confirmed?
  Decide after verification.
