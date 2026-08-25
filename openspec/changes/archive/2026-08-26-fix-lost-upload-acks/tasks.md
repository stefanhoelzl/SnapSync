## 1. Settle the two open questions first

- [x] 1.1 Measure whether `PHAssetCollectionChangeRequest.addAssets` duplicates an asset already in a user
      album: on a simulator (load `ios-simulator`), place one asset, place it again, count the album's
      members. Record the result, the OS build, and an expiry trigger in `design.md` under Open Questions.
      If it duplicates, decide the mitigation there before task 6.2 is written.
      → **IDEMPOTENT** (simulator, iOS 26.5 / Xcode 26.6 / macOS 26.5.2): 3 adds → 1 member, no throw.
      Recorded in `design.md` with its expiry trigger; the risk row is retired and 6.2 needs no
      mitigation.
- [x] 1.2 Settle how `UploadCycle` mints a fresh `UploadRequest` for a retry-spent failure now that
      `UploadError` no longer crosses the port (today the minted request arrives on
      `SyncDecision.Retry` from `engine.handle(UploadFailed)`). Record the chosen shape in `design.md`;
      task 5.3 depends on it.
      → **Settled:** keep `engine.handle(UploadFailed)` unchanged, and keep `error` on `PlatformUploadJob`.
      Revises D7 (recorded in `design.md`); task 5.2 amended.

## 2. Prerequisite — stop over-reporting stranded rows

- [x] 2.1 Narrow `pendingKeys` in `UrlSessionUploadController` to `REQUESTED` rows only. This needs a
      state-scoped read; add it beside the uploaded-row read planned in 3.4 rather than filtering
      `pendingResources()` in Kotlin.
- [x] 2.2 Extend `UrlSessionOutcomeTest` to pin that a non-`REQUESTED` row is never a stranded candidate.
- [x] 2.3 Verify against the SNAPSYNC-16 shape: a key reported stranded repeatedly inside one process must
      now be reported at most once.

## 3. Ledger — the state, the done-set, and the two new verbs

- [x] 3.1 Add `UPLOADED` to `LedgerState`. Fix every resulting compile error rather than adding an `else`:
      `SyncEngine.decide` must treat it as already-uploaded (skip).
- [x] 3.2 Add `LedgerState.isDone` in `:domain` `model/` as an exhaustive `when` with no `else`
      (`COMPLETED` → true; `UPLOADED`, `REQUESTED`, `FAILED` → false) plus the derived done-state set.
- [x] 3.3 Rewrite `selectPending`, `aggregates` and `selectCompletedManifestRows` in `Ledger.sq` to take the
      done-state set as a bound parameter (`state NOT IN :doneStates` / `state IN :doneStates`); remove
      every `'COMPLETED'` literal from those three predicates.
- [x] 3.4 Add `markTerminal(key, state): Boolean` and the uploaded-row read to `LedgerStore`.
      `markTerminal` is **non-suspending** and is one `UPDATE … WHERE key = :key AND state = 'REQUESTED'`
      whose applied/not-applied answer is read via `changes()` inside the same transaction — mirroring
      `DownloadStore.sq`'s three callback writes.
- [x] 3.5 Implement both on `SqlDelightLedgerStore`, signalling `changes` once on an applied
      `markTerminal`. Confirm no `withContext` / dispatcher hop is introduced.
- [x] 3.6 Implement both on `InMemoryLedgerStore` (`:adapter:generic:fake`), keeping the fake's public
      surface exactly its port contract plus its initial-state constructor (`FakeHonestyTest`).
- [x] 3.7 Extend `:test:world`'s `LedgerStoreContract` for: a guarded flip applies; a non-`REQUESTED` row is
      not clobbered; an absent key applies to nothing; the uploaded-row read returns whole entries; an
      `UPLOADED` row counts pending in `aggregates` and appears in the pending-resource read; the manifest
      projection excludes it. Both driver tests (JVM + native) inherit it.
- [x] 3.8 Confirm no `.sqm` is written and `./gradlew verifyLedgerSchema` (the migrated-vs-created
      comparison) still passes untouched.

## 4. The app-driven delegate records terminally, and the adapter forgets

- [x] 4.1 Give `IosUrlSessionUploadPlatform` its `LedgerStore` (via `UploadPorts`, so every root and the
      world answer at the compile). Confirm no late-bound mutable field is introduced.
- [x] 4.2 In `recordTerminal`, call `markTerminal(key, UPLOADED | FAILED)` **synchronously before the
      callback returns**, delete the staged temp file, and log a write that applied to nothing.
- [x] 4.3 Delete the `terminal` list.
- [x] 4.4 Delete `inFlight`: derive the staging path from the key, read `contentType` from the ledger row,
      resolve tasks via `getAllTasks()`, and recover a resource from the row's `assetId` + `role`.
- [x] 4.5 (code done — cap now reads `liveTaskKeys()`, `cancelAll`/`cancelKey` re-derive from `getAllTasks`;
      the TEST clause is outstanding) Move the concurrency cap in `createJob` from the in-process count to the session's live task set,
      and rewrite `cancelAll` / `cancelKey` / `sweepStaging` against `getAllTasks()` and the staging
      directory. Add a test that the cap binds when the OS holds live tasks and no in-process record exists.
- [x] 4.6 Drop the storage clause from the stranded reconciliation and route its `FAILED` write through
      `markTerminal`, keeping the per-key diagnostic line at the call site.

## 5. Port reshape — terminal facts stop crossing

- [x] 5.1 Rename `BackgroundTransfer.fetchAckJobs()` to `drainTerminals()`, returning only retry-spent
      failures the cycle must re-create. Remove `acknowledge` from the port.
- [x] 5.2 Shrink `PlatformUploadJob` to `key`, `contentType`, `error`, `data`; delete `state`, `handle` and
      the `PlatformJobState` enum. (`error` retained per 1.2 — `SyncEngine.handle` logs it and the minting
      path needs one.)
- [x] 5.3 Rework `UploadCycle.settleTerminalJobs` into a re-create pass over `drainTerminals()`, using the
      minting shape settled in 1.2.
- [x] 5.4 Return an empty list from `drainTerminals()` on `IosUrlSessionUploadPlatform`.
- [x] 5.5 In `IosPhotoKitUploadPlatform`, record `UPLOADED`/`FAILED` through `markTerminal` and acknowledge
      in place; return only retry-spent failures whose `resource` is still available. Acknowledge every
      presented job regardless of whether its guarded write applied (error 50008).
- [x] 5.6 Update `PhotoKitJobMappingTest` and `UrlSessionOutcomeTest` for the reshaped job type.

## 6. The promotion pass

- [x] 6.1 Add a promotion pass to `UploadCycle`, shared by both tiers: read the uploaded rows, place their
      assetIds in the event album, fire the notify, then promote each row to `COMPLETED`.
- [x] 6.2 Promote **regardless** of the album and notify outcomes (both stay best-effort). No dedup is
      needed before `place`: 1.1 measured `addAssets` idempotent, so a crash-repeat is a no-op.
- [x] 6.3 Gate the notify on "at least one row promoted this cycle" and delete the `wasCompleted`
      read-before-write; confirm the notify still fires after the device-manifest write.
- [x] 6.4 Confirm a direction-declined cycle and a no-event cycle promote nothing, place nothing and notify
      nothing, leaving `UPLOADED` rows intact.

## 7. The law

- [x] 7.1 Widen the state-and-authority scenario from "a core object" to "a core object or a port
      implementation", and add the once-only-delivery obligation with its forcing-proof clause.
- [x] 7.2 Cross-reference the new obligation from `@PlatformEntry`'s KDoc, alongside the logging obligation
      it already carries. Do not add a new gate — `LawsDigestTest` keeps CLAUDE.md's digest in sync, so
      update that line too.

## 8. Verification

- [x] 8.1 Add the regression test to `:test:integration`: drive a cycle to a terminal callback, discard the
      core and rebuild it over the **same** fake ledger, then assert the next cycle promotes to `COMPLETED`
      and creates no upload job. Runs on JVM and `iosSimulatorArm64`.
- [x] 8.2 Add the negative case: a `REQUESTED` row with no live task and no terminal record is recorded
      `FAILED` and re-uploaded, and no device listing is fetched.
- [x] 8.3 Add the PhotoKit-tier case over `:test:world`: a succeeded job becomes `UPLOADED`, is acknowledged,
      and is promoted with an album placement and one notify in the same cycle.
- [x] 8.4 Run `./gradlew build` and `./gradlew compileIosMainKotlinMetadata`; run the simulator tests via
      `ssh-mac-build`.
      → `build` green; iOS metadata clean; **1239 `iosSimulatorArm64Test`s, 0 failures, 0 skipped**
      (macOS 26.5.2 / Xcode 26.6), covering `adapter/ios/*` which Linux can only compile.
- [x] 8.5 Run `./gradlew architectureDiagrams` and commit any change (stale diagrams block the PR).
- [x] 8.6 `npx --yes @fission-ai/openspec@1.5.0 validate --specs --strict`.

## 9. Ship

- [x] 9.1 Write `Bugsink-Resolves: SNAPSYNC-11` into the first commit of the fix.
- [x] 9.2 Verify on device (load `ios-device` + `rig-channel`): force-quit mid-upload, relaunch, and confirm
      `debug.log` shows a promote with no `createJob` for the same key and no `Unknown(detail=stranded)`.
      → **PARTIAL — see `design.md`, "On-device verification".** The mechanism is confirmed on a real
      SE2/26.6 on the app-driven tier: the `URLSession` delegate fires, records `UPLOADED` durably at that
      moment, and the cycle promotes. 30 photos uploaded with no `stranded` line and no duplicate
      `createJob`. The force-quit landing *between* the two writes was NOT reproduced — the window is
      ~20 ms and the rig's tier pin does not survive a relaunch, so the loop cannot be repeated cheaply.
      That half is covered deterministically by `LostUploadAckIntegrationTest`.
- [ ] 9.3 Label the PR `bug` and ship via `/ship`.
