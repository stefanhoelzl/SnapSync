One PR (design D1). The groups are ordered for review. Groups 1–4 change one surface together — the Kotlin does
not compile against a `Ledger.sq` that has lost columns it still binds — so `./gradlew build` is required to be
green at the end of group 4 and at every group after it, not between groups 1 and 4.

## 1. Schema: `10.sqm` and `Ledger.sq`

- [ ] 1.1 Add `adapter/generic/app/src/commonMain/sqldelight/ledger/app/snapsync/engine/db/10.sqm`:
  `UPDATE ledgerRow SET state = 'DISCOVERED' WHERE state = 'FAILED';`, then `ALTER TABLE ledgerRow DROP COLUMN`
  for `attempt`, `eventId` and `absent`. Its header comment, written in the style of `8.sqm`/`9.sqm`, states:
  - v10 → v11;
  - why each drop is valid on both the fresh and the upgraded shape;
  - why the rewrite is REQUIRED and is not a read alias (design D2);
  - that the verify task cannot see the rewrite, so `SqlDelightLedgerStoreTest` covers it;
  - the roll-forward downgrade story (design, Migration Plan).
- [ ] 1.2 In `Ledger.sq`, remove the three columns from `CREATE TABLE`, and remove them from every statement:
  `get`, `recordUnlessSettled`, `insert`, `selectByDestinationPath` and `selectNeedingJob`.
- [ ] 1.3 In `Ledger.sq`, delete the statements for the removed sweeps: `backfillEventId` and
  `clearAbsenceMarks`.
- [ ] 1.4 In `Ledger.sq`, remove the `absent = 0` filters from `selectManifestRows`, `selectPending`,
  `aggregates` and `selectNeedingJob`.
- [ ] 1.5 Retarget `demoteRequested` to `SET state = 'DISCOVERED'`.
- [ ] 1.6 Rewrite every comment in `Ledger.sq` that names a removed concept. This covers the header, the column
  notes, the `get` note, the guard note ("a stale FAILED"), `selectRequestedKeys` and the `markTerminal` note.
- [ ] 1.7 Leave `ledger/databases/9.db` as it is (design D6). Confirm that `verifyCommonMainLedgerDatabaseMigration`
  passes.

## 2. Model: three states, no attempt, no provenance, no mark, no `UploadJob`

- [ ] 2.1 In `domain/model/.../Ledger.kt`, remove `LedgerState.FAILED`.
  - `isDone`, `needsJob` and `bytesBelievedStored` become exhaustive over three states; `needsJob` is
    `DISCOVERED` alone.
  - `TerminalOutcome.FAILED` maps to `LedgerState.DISCOVERED`, and its KDoc is reworded (design D3).
  - Update the `DISCOVERED` and `COMPLETED` KDoc wherever it names `FAILED`.
- [ ] 2.2 From `LedgerEntry`, remove `attempt`, `eventId` and `absent`, and remove `markedAbsent`/`markedPresent`
  along with the `rebuilt` absent parameter. Update `equals`, `toString`, `withState` and the class KDoc.
  `Resource.toLedgerRow` loses `attempt` and `eventId`.
- [ ] 2.3 In `domain/model/.../SyncModel.kt`, delete `UploadJob` (design D4).
  - `SyncEvent.UploadFailed`/`UploadStarted` and `SyncDecision.Upload`/`Retry` carry `request: UploadRequest`.
  - `SyncDecision.Work` exposes `request`.
  - Fix the KDoc of `UploadError` and `Retry` ("attempt + 1", "attempt-budget policy").
- [ ] 2.4 Remove the `filterNot { it.absent }` read in `DeviceManifest.kt`.

## 3. Ports and adapters

- [ ] 3.1 In `LedgerStore`, delete `backfillEventId` and `clearAbsenceMarks`. Reword the KDoc of
  `recordUnlessSettled`, `rowsNeedingJob`, `requestedKeys`, `manifestRows`, `backfillManifestDetail` and
  `demoteRequested`, which name `FAILED`, `attempt` or absent rows.
- [ ] 3.2 Update the `TransferRecord.markTerminal` KDoc: the recordable set is `COMPLETED`/`DISCOVERED`, and the
  callback still cannot write `REQUESTED`.
- [ ] 3.3 Update `SqlDelightLedgerStore`: `toEntry` loses its three parameters, the three writes stop binding
  them, `manifestRows` stops passing placeholders, and the two sweep implementations are deleted.
- [ ] 3.4 Update `:adapter:generic:fake`'s `InMemoryLedgerStore`: remove the fields, the provenance sweep, the mark
  sweep and the absent filters, and retarget its `demoteRequested` to `DISCOVERED`. Confirm that `FakeHonestyTest`
  passes.
- [ ] 3.5 In `PhotoKitJobMapping` and `IosUrlSessionUploadPlatform`, update KDoc and log text that says a failure
  records `FAILED`. The `TerminalOutcome.FAILED` call sites do not change.

## 4. Features

- [ ] 4.1 Update `SyncEngine`.
  - Drop the `eventId` constructor parameter and every `attempt`.
  - `retry` records the failure through `recordFailed(resource)` and answers `Retry` with a newly minted request.
  - Log lines keep the arm and key, without `attempt=`.
  - `decide`'s `when` covers three states.
  - Reword the class KDoc.
- [ ] 4.2 Update `LedgerWriter`.
  - `recordDiscovered`, `recordRequested` and `recordFailed` lose `attempt` and `eventId`, and `recordFailed`
    records `DISCOVERED`.
  - Delete the writer's `backfillEventId` and `clearAbsenceMarks`.
  - `backfillManifestDetail` loses `eventId`.
  - `record`'s preservation block and its declined-write log line lose `attempt`.
  - Reword the `recordDiscovered` guard rationale (design D3) and the `markStranded`/`rowsNeedingJob` KDoc.
- [ ] 4.3 Update `UploadCycle`.
  - Delete the `backfillEventId` and `clearAbsenceMarks` calls, with their comments, from `settle`.
  - `reconstruct` returns an `UploadRequest` and reads no ledger entry for an attempt.
  - Rewrite every KDoc and log line that says `FAILED`: the enqueue KDoc, `placeFirstEnqueued`,
    `recreateRetrySpent`, `reconcileStranded`, and the cap-hit comment.
- [ ] 4.4 Update `placeFirstEnqueued`'s KDoc per design D5. Its predicate does not change.
- [ ] 4.5 In `StrandedKeys` and `DemoteRequested`, update KDoc to say "recorded `DISCOVERED`". No logic changes
  (phase 5 owns the repair's structure).
- [ ] 4.6 In the `Reconciler` seed, construct the `LedgerEntry` without `attempt`/`eventId`. Nothing else in the
  file changes (design D7).
- [ ] 4.7 Remove the `filterNot { it.absent }` read in `AlbumGather.ownSet`.
- [ ] 4.8 Update every `SyncEngine(...)` construction in `compose/`, `:test:world` and tests, which now take no
  `eventId`.
- [ ] 4.9 Run `./gradlew compileIosMainKotlinMetadata` and `./gradlew build`.

## 5. Tests

- [ ] 5.1 Add the `SqlDelightLedgerStoreTest` migration case from design D6 (v10 → v11: the `FAILED` rewrite,
  other states untouched, the formerly absent row visible, no removed column left). Update the existing migration
  tests (v4→v5, v6→v7, v8→v9, v9→v10) so they read back through the post-v11 surface. Where a test's assertion
  only exists for a column that is now dropped, assert it with raw SQL before migrating to v11, or delete it with
  a note in the commit message.
- [ ] 5.2 In `:test:world`'s `LedgerStoreContract`, `LedgerRecordGuardContract` and `LedgerSeeds`:
  - drop the `attempt`/`eventId` arguments and the provenance and mark cases;
  - retarget `FAILED` expectations to `DISCOVERED`;
  - delete the "converges on attempt" assertion;
  - keep the demote case, asserting `DISCOVERED`.
- [ ] 5.3 In `domain/feature` commonTest, update the two test-local ledger fakes (`InMemoryLedgerStore`,
  `FakeLedgerStore`) and every upload test that names `FAILED`, `attempt`, `UploadJob` or provenance: `SyncEngineTest`,
  `UploadCycleTest`, `DemoteRequestedTest`, `StrandedKeysTest`, `OsDrivenUploadMechanismTest`,
  `LedgerEntryEqualityTest` and `PhotoKitJobMappingTest`.
- [ ] 5.4 Add an `UploadCycleTest` case: a row that returned to `DISCOVERED` after a failure is placed again by
  the enqueue pass (design D5), replacing the old "re-created failure is not placed" test.
- [ ] 5.5 Update `:test:integration`, `:test:world` world tests, and `:app:desktop`'s world inspector wherever
  they name `FAILED` or a ledger/engine attempt. The world's own creation counter stays.
- [ ] 5.6 Run `grep -rnE "\bFAILED\b" --include=*.sq --include=*.sqm --include=*.kt` over the tree. Every
  remaining hit must be `TerminalOutcome.FAILED`, `CreateResult.FAILED`, a `CycleResult`, a download state, or the
  `8.sqm`/`10.sqm` comments. Then grep `\.attempt\b`, `backfillEventId`, `clearAbsenceMarks`, `\babsent\b` and
  `UploadJob\b` and confirm there are no ledger or engine hits.

## 6. Diagrams, gates, ship

- [ ] 6.1 Run `./gradlew architectureDiagrams` and commit any change (flows or port sets may re-render).
- [ ] 6.2 Run `./gradlew build` green, including the detekt tiers. No tier ceiling may rise.
- [ ] 6.3 Run `npx --yes @fission-ai/openspec@1.5.0 validate --specs --strict` and validate this change.
- [ ] 6.4 At sync/archive, hand-edit the `## Purpose` text, which a delta cannot carry:
  - `sync-ledger`: the provenance and backfill history, and "the retired absence mark and its sweep". Record that
    this change retired `FAILED`, `attempt`, `eventId` and `absent` through `10.sqm`.
  - `sync-engine`: "requested, completed, and failed". The engine still records failures, but as `DISCOVERED`.
- [ ] 6.5 Open the PR with the `internal` label. Its description states the one-way door and the roll-forward
  rollback.
