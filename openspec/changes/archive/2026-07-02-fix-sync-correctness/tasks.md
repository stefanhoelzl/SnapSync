# Tasks — fix-sync-correctness

Ordered by dependency. Groups 1–2 are the shared-parser foundation others build on; each fix is
independently landable but the whole change ships together. Every new/changed logic test SHALL run on
JVM **and** `iosSimulatorArm64` (testing rule 1).

## 1. Shared `assetIdFromUploadKey` parser (foundation)

- [x] 1.1 Add `assetIdFromUploadKey` to `:domain:gallery` next to `uploadKey`, as its exact inverse (`<assetId>-<role>.<ext>` → `assetId`, handling assetIds with embedded `-`)
- [x] 1.2 Add a `commonTest` round-trip test in `:domain:gallery`: for keys produced by `uploadKey`, `assetIdFromUploadKey` recovers the original `assetId` (with and without embedded `-`) — runs on JVM + simulator
- [x] 1.3 Replace the private `assetIdFromUploadKey` in `capability/rejoin/.../Reconciler.kt:111` with the gallery one (add the `:domain:gallery` dependency to `:capability:rejoin` if not present); delete the duplicate
- [x] 1.4 Point `UploadCycle.reconstruct` at the gallery parser (gallery is already a `:app:ios:photokit-extension` dep); confirm no new module edge is needed for this change
- [x] 1.5 `./gradlew build` + `./gradlew compileIosMainKotlinMetadata` green

## 2. Fix `reconstruct` phantom `assetId=""` row

- [x] 2.1 In `UploadCycle.reconstruct` (`UploadCycle.kt:149`) derive `assetId` from `job.key` via `assetIdFromUploadKey` instead of `entry?.assetId ?: ""`
- [x] 2.2 Gate the terminal completion record on a **recoverable** key (never record a phantom `assetId=""` row when the ledger entry is absent)
- [x] 2.3 Add a `UploadCycleTest` scenario: a succeeded job whose ledger row was pruned completes with the key-derived `assetId`, no `assetId=""` row written
- [x] 2.4 Confirm the "Completion and retry adjudication" spec scenarios (incl. "A pruned-row completion derives assetId from the key") pass

## 3. Fix the `clearRequested` re-enable race (§7.1)

- [x] 3.1 Create a tested bounded-retry, off-main clear helper in a `domain`/`capability` module: runs `clearRequested()` on `Dispatchers.Default` (NOT `IO` — absent on Native) with a small bounded retry; suspends until done
- [x] 3.2 Add `commonTest` coverage for the helper: it retries on transient failure, gives up after the bound, and never runs on the main dispatcher
- [x] 3.3 Make `SnapSyncRoot.disableExtension()` suspending and route it (and `LeaveEvent`'s disable) through the shared helper; drop the `scope.launch { … }` fire-and-forget
- [x] 3.4 In `enableBackgroundUpload()` **await** the disable/clear before `setUploadJobExtensionEnabled(true)` so the re-enabled extension's fresh `REQUESTED` rows are never deleted
- [x] 3.5 Keep `SnapSyncRoot` to the mechanical two-step sequence only (no retry/threading logic in the untested shell)
- [x] 3.6 Verify the "Disabling the extension clears orphaned REQUESTED rows" spec scenarios (race, off-main, leave) are covered

## 4. Bound reconcile's device `LIST` + empty guard (§7.3, §7.4)

- [x] 4.1 Wrap `files.list(deviceId)` in `ExtensionReconciler.reconcile` (`Reconciler.kt:81`) with `withTimeout`; on expiry take the existing defer path (no seed, marker unset, ledger/cursor untouched)
- [x] 4.2 Keep `resetTo(seeds)` a single atomic, un-timed transaction (only the network `LIST` is bounded)
- [x] 4.3 Add the empty-listing guard: when `list` returns empty AND the ledger holds `COMPLETED` rows, defer (leave ledger/cursor/marker untouched) instead of wiping to empty; an empty listing against an empty/no-`COMPLETED` ledger still settles with zero rows
- [x] 4.4 Add `ReconcilerTest` scenarios: listing timeout defers without settling; empty-listing-with-`COMPLETED`-ledger defers; empty-listing-fresh-device still settles
- [x] 4.5 Confirm `busyTimeout` is left at the SQLiter default (~5s) — do **not** set it (design.md)

## 5. Narrow the extension's suppression linkage (§7.5)

- [x] 5.1 Expose a read-only `SuppressionSource` factory and wire it into the upload cycle's composition root (`UploadExtensionRoot`) in place of the `DownloadStore`
- [x] 5.2 Ensure no `DownloadStore`-typed value reaches the upload cycle (compile-enforced narrowing)
- [x] 5.3 Add a `commonTest` for the `'/'→'_'` suppression normalization: a discovered `assetId` containing `'/'` matches its `'/'→'_'` `createdLocalId` and is suppressed (assert the shape against `:domain:download-store`)

## 6. Delete dead code (§7.7)

- [x] 6.1 Delete `capability/rejoin/.../EventFilesSource.kt`, `HttpEventFilesSource.kt`, and `HttpEventFilesSourceTest.kt`
- [x] 6.2 Confirm `DarwinHttpClient` still has a live consumer (`DeviceFilesSource`'s iOS impl) after deletion; update its stale comment (`DarwinHttpClient.kt:9`) and the `EventCreationClient.kt:33` comment referencing `HttpEventFilesSource`
- [x] 6.3 `./gradlew build` + `./gradlew compileIosMainKotlinMetadata` green after deletion

## 7. Doc-accuracy fixes (§7.8)

- [x] 7.1 `docs/design.md §2.2` — stranded `REQUESTED` is not re-enum-rescued (correct the claim)
- [x] 7.2 `docs/design.md §2.4` — "sole writer" is imprecise (the app writes via `clearRequested`); the suppression predicate is `createdLocalId IS NOT NULL`; `Role` naming (`primary`/`motion` vs code's `live`)
- [x] 7.3 Reframe the `:app:ios:photokit-extension` module as "not harness-reachable", not "untested"
- [x] 7.4 Refresh the stale `CLAUDE.md` module table (it omits several existing modules)

## 8. Verification

- [x] 8.1 `./gradlew build` (all targets + JVM tests, incl. new `commonTest`s on JVM) green
- [x] 8.2 `./gradlew compileIosMainKotlinMetadata` (iOS proxy compile) green
- [x] 8.3 `openspec validate fix-sync-correctness --strict` passes
- [~] 8.4 On-device pass (SE2, iOS 26.5, dev IPA built via ssh-mac from this working tree). **Verified:** both Kotlin frameworks compile for arm64 + sign + archive + export; app installs & launches clean; **`clearRequested` off-main completes before re-enable** — logged "re-registered (disable→enable, cleared REQUESTED)" across two provision cycles, no hang/black-screen (Group 3); storage-truth status renders ("9 images synced"); fresh-event 404 handled gracefully; OS (`dasd`) schedules the upload-extension runner — and the **extension process actually ran a full cycle** (`dasd`: `assetresourceuploadextensionrunner` ran 3.46 min, cleanly suspended at the ~3-min cap, **not** force-killed 50001) **with no crash report** (newest SnapSync/BackgroundUpload `.ips` are all pre-session 06-27/30), so the reconcile+`UploadCycle` changes execute on device without crashing. **Not isolated this session (OS-owned `process()` timing / conditions):** the specific reconcile/cycle log lines (bounded `LIST`, empty-guard, `reconstruct` — also unit-tested JVM+sim); ~20–50k-library reconcile; bunny `LIST` read-your-writes consistency (empty-guard fate); Keychain survives reinstall
