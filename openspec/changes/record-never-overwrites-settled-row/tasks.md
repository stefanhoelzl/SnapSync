## 1. Storage: the guarded record and the un-mark

- [x] 1.1 In `Ledger.sq`, replace `put` with `recordUnlessSettled` (the `INSERT … ON CONFLICT(key) DO UPDATE … WHERE ledgerRow.state NOT IN :doneStates` from design D2, every column including `absent` in the `SET`), and add `insert` (a plain `INSERT`) for `resetTo`, `selectAbsentAssetIds` and `markPresent` (`UPDATE … SET absent = 0 WHERE absent = 1 AND assetId IN :assetIds`); rewrite the comments that describe `put`
- [x] 1.2 In `LedgerStore` (ports), remove `put`, add `recordUnlessSettled(entry): Boolean` and `markPresent(assetIds: Collection<String>)` with KDoc, and replace every "like a [put]" reference
- [x] 1.3 In `SqlDelightLedgerStore`, implement `recordUnlessSettled` as the upsert plus `changedRows()` in one transaction binding `DONE_STATES`, notifying watchers only when applied; implement `markPresent` as one deferred transaction (read absent ids, intersect, chunked update of the matches only, notify only when applied); switch `resetTo` to `insert`; fix the class KDoc
- [x] 1.4 Correct the stale "SQLite 3.18" comment in `adapter/generic/app/build.gradle.kts` (the dialect is `sqlite-3-35`)

## 2. Every `LedgerStore` double honours the guard

- [x] 2.1 `:adapter:generic:fake` `InMemoryLedgerStore`: remove `put`, add the guarded record (declines over `isDone` rows, notifies only when applied) and `markPresent`
- [x] 2.2 `domain/feature` commonTest `InMemoryLedgerStore` and `FakeLedgerStore`: the same; also make `InMemoryLedgerStore.markTerminal` preserve `absent`, as the other two already do
- [x] 2.3 Grep for any other `LedgerStore` implementation or wrapper (`: LedgerStore`) across `test/` and `app/` and bring it to the same contract

## 3. Writer and cycle

- [x] 3.1 `LedgerWriter.record` writes through `recordUnlessSettled`, keeps its manifest-detail preservation read (never as the guard), and logs a declined record at Warn with the key, the refused state and the attempt; `recordDiscovered` keeps its own no-row rule on top
- [x] 3.2 Add `LedgerWriter.markPresent(assetIds)` delegating to the backend
- [x] 3.3 `UploadCycle`: carry the asset ids of every walk candidate (`discovery.candidates`, before admission) in `CyclePlan`, and call `ledger.markPresent` in the update stage after the `markAbsent` loop
- [x] 3.4 Verify the candidate `facts.assetId` is the same normalized value the ledger rows carry (the one `markAbsent` receives from the change feed); if not, normalize at the call site
- [x] 3.5 Update the `SyncEngine` KDoc ("each an unconditional idempotent per-key upsert") and the `recreateRetrySpent` comment to describe the guarded write

## 4. Tests

- [x] 4.1 `LedgerStoreContract`: migrate every `put` seed to `resetTo` or the writer; add scenarios — each record op over a `COMPLETED` row leaves it unchanged and reports not applied; `FAILED → REQUESTED` and `REQUESTED → FAILED` still apply; `resetTo` still replaces `COMPLETED` rows; a declined record does not notify watchers; `markPresent` clears a `COMPLETED` row's mark leaving every other field, writes and notifies nothing when no supplied asset is absent
- [x] 4.2 Migrate the remaining `put` seeds: `SqlDelightLedgerStoreTest`, `IosLedgerStoreTest`, `ReconcilerTest`, `UploadCycleTest`, `OsDrivenUploadMechanismTest`, `CollectDiagnosticDumpTest`, `ResetDeviceStateTest` (the staged-revert raw `INSERT OR REPLACE` test stays as it is)
- [x] 4.3 `SyncEngineTest`: `UploadFailed` and `UploadStarted` over a `COMPLETED` key leave it `COMPLETED`, and `UploadFailed` still answers `Retry`
- [x] 4.4 `UploadCycleTest`: a first-failure retry for a key seeded `COMPLETED` leaves the row `COMPLETED`; an asset with an absent `COMPLETED` row that the walk returns again is un-marked, projects into the manifest, and gets no job; a walk candidate outside the policy is still un-marked

## 5. Verify

- [x] 5.1 `./gradlew build` green (incl. `verifyCommonMainLedgerDatabaseMigration`, detekt tiers, architecture guards)
- [x] 5.2 `./gradlew compileIosMainKotlinMetadata` green; `./gradlew architectureDiagrams` and commit if anything changed
- [x] 5.3 `npx --yes @fission-ai/openspec@1.5.0 validate --specs --strict` and `validate record-never-overwrites-settled-row --strict` pass
- [ ] 5.4 The PR's macOS CI run executes `LedgerStoreContract` on the native driver (`iosSimulatorArm64Test`) green — the iOS runtime check of the conditional upsert
