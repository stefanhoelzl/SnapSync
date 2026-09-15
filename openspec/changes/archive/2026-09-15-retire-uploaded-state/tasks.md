## 1. Vocabulary and port

- [x] 1.1 Add `TerminalOutcome { COMPLETED, FAILED }` to `:domain` `model/`, with a KDoc stating it is the set
      an upload can terminate in (design D6)
- [x] 1.2 Remove `LedgerState.UPLOADED`; reclassify `isDone`, `needsJob` and `bytesBelievedStored` (keep all
      three separate, D5) and rewrite their KDocs to drop the `UPLOADED` rationale
- [x] 1.3 `LedgerStore`: change `markTerminal(key, state)` to `markTerminal(key, outcome: TerminalOutcome)`;
      delete `uploadedRows` and `promoteUploaded`; fix the KDoc at ~127 that argues from `UPLOADED`
- [x] 1.4 Fix `BackgroundTransfer.kt` KDoc (~34) that says the cycle finds completion work by reading
      `UPLOADED` rows

## 2. Storage

- [x] 2.1 `Ledger.sq`: delete `selectUploaded` and `promoteUploaded`; rewrite the `selectNeedingJob` comment
      that contrasts itself with `selectUploaded`
- [x] 2.2 Add `8.sqm`: the data-only `UPDATE ledgerRow SET state = 'COMPLETED' WHERE state = 'UPLOADED'`,
      with a header comment stating it places nothing and the staged-revert stance (D3)
- [x] 2.3 `SqlDelightLedgerStore`: map `TerminalOutcome` to the stored state in `markTerminal`; delete
      `uploadedRows` / `promoteUploaded`
- [x] 2.4 Update every other `LedgerStore` implementation: `:adapter:generic:fake` `InMemoryLedgerStore`,
      `:domain:feature` commonTest `InMemoryLedgerStore` and `FakeLedgerStore`, and the stub in
      `OsDrivenUploadMechanismTest`

## 3. Delete the dead completion path

- [x] 3.1 Delete `SyncEvent.UploadCompleted` and fix the `AlreadyUploaded` KDoc in `SyncModel.kt`
- [x] 3.2 `SyncEngine`: delete `complete`, its `handle` arm and log arm; drop `UPLOADED` from `decide`; rewrite
      the class KDoc (the ledger is no longer "written exclusively by this engine")
- [x] 3.3 `LedgerWriter`: delete `recordCompleted`, `uploadedRows` and `promote`
- [x] 3.4 Add a test-only completed-row seed helper in `:test:world` `commonMain` over `LedgerStore.put`, and
      move every test that seeded a `COMPLETED` row through `recordCompleted` onto it (`UploadCycleTest`,
      `LedgerStoreContract`); delete `SyncEngineTest`'s `UploadCompleted` cases

## 4. The cycle

- [x] 4.1 `UploadCycle`: delete `promoteUploaded`; replace `promoteThenPublishManifest` with the manifest
      write; rewrite the KDocs that describe promotion (~222–226, ~568–592, the `placeInAlbum` parameter doc
      at ~111–116)
- [x] 4.2 In `enqueue`, after `resourcesFor` and before the job loop, place (one best-effort `runCatching`
      call, gated on `ready.saveToAlbum`) the assets of slice rows whose state is `DISCOVERED` and whose
      resource resolved (D2)
- [x] 4.3 `AlbumCoordinator`: fix the KDoc that says the extension places "at upload completion"

## 5. Adapters and harness

- [x] 5.1 `IosUrlSessionUploadPlatform.recordTerminal`: record `TerminalOutcome.COMPLETED` on success; fix the
      `reconcileStranded` KDoc (~252, ~261)
- [x] 5.2 `PhotoKitJobMapping`: `TerminalDisposition` carries a `TerminalOutcome`; succeeded → `COMPLETED`;
      rewrite its KDoc; fix `IosPhotoKitUploadPlatform`'s KDoc (~85)
- [x] 5.3 Update every other `markTerminal` caller to compile: the simulator job queue
      (`iosSimulatorArm64Main` `UploadJobQueue`), the stranded pass, and `:test:world` `UploadFakes`
      (succeeded → `COMPLETED`, KDoc at ~27 and ~111)
- [x] 5.4 Fix the remaining `UPLOADED` mentions in KDocs: `test/rig` `UploadJobCycle.kt` (~98) and
      `UrlSessionOutcomeTest.kt` (~113)

## 6. Tests

- [x] 6.1 `LedgerStoreContract`: delete the `UPLOADED`/promotion/uploaded-row cases; assert
      `markTerminal(COMPLETED)` flips a `REQUESTED` row with every other column intact and signals once, and
      that `aggregates()` then counts the photo completed
- [x] 6.2 `SqlDelightLedgerStoreTest`: a v8 database holding a raw `'UPLOADED'` row plus one row in each other
      state, migrated to current — the `UPLOADED` row decodes `COMPLETED` with columns intact, the others keep
      their states, and `aggregates()` counts the converted photo completed
- [x] 6.3 `UploadCycleTest` placement: only still-`DISCOVERED`, resolved slice rows are placed, only with
      `saveToAlbum`, and before `createJob`; a policy-excluded or unresolvable row is not placed; a re-created
      `FAILED` row is not placed; `LIMIT_EXCEEDED` mid-slice still placed the slice; a declined cycle places
      nothing; a placement failure does not stop job creation
- [x] 6.4 `UploadCycleTest`: rewrite the declined-cycle assertion (~531) and the promote-before-publish test
      (~1139–1190); add a regression that a retry-spent failure drained for a `COMPLETED` key writes nothing
      and creates no job
- [x] 6.5 `PhotoKitJobMappingTest`: succeeded → `COMPLETED`, never re-created; `UploadLedgerAuditTest`: drop the
      `UPLOADED` case
- [x] 6.6 `:test:integration`: rewrite `LostUploadAckIntegrationTest` and `LostUploadRecordIntegrationTest`
      for direct `COMPLETED` and enqueue-time placement (placed exactly once); keep
      `ReconfigureIntegrationTest`'s album-on test green and add a case that an own photo enqueued after the
      toggle is placed before its upload completes

## 7. Specs and generated artifacts

- [x] 7.1 `grep -rn "UPLOADED\|promoteUploaded\|uploadedRows\|UploadCompleted\|recordCompleted" --include=*.kt --include=*.sq --include=*.sqm .`
      returns only `8.sqm` and the migration test
- [x] 7.2 `./gradlew architectureDiagrams` and commit (`architecture/ports.md` loses `UploadCompleted`)
- [x] 7.3 `npx --yes @fission-ai/openspec@1.5.0 validate --specs --strict` and validate this change
- [x] 7.4 At sync, hand-edit the Purposes deltas cannot reach: `sync-engine` (no longer "an upload completed"
      / "written exclusively by the engine"; the `UPLOADED` history line), `event-album` ("Uploaded photos are
      added at upload-cycle completion"), and add this change's decision-record citation to `sync-ledger`,
      `sync-engine` and `event-album`

## 8. Verify and ship

- [x] 8.1 `./gradlew build` and `./gradlew compileIosMainKotlinMetadata` green
- [ ] 8.2 Branch → PR with the `enhancement` label → `/ship --keep-workspace`
- [ ] 8.3 On a confirmed merge, report phase M9 to the `tierless` workspace per the handoff, naming as
      deviations: placement at enqueue rather than admission; the dead completion path and `TerminalOutcome`
      added; data-only `8.sqm` with a staged-revert stance
