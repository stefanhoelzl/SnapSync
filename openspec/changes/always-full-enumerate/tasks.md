One PR, landed as the reviewable commits below (design D1). Every group leaves `./gradlew build` green.

## 1. Retire the absence mark (sweep + key-scoped delete)

- [x] 1.1 Add `deleteKeys(keys: Collection<String>)` and `clearAbsenceMarks()` to `LedgerStore`
  (`domain/ports/.../LedgerStore.kt`), with KDoc stating the key-scoped rule and the sweep's reason
  (spec `sync-ledger`, "Prune operations are writer-only").
- [x] 1.2 Implement both in `SqlDelightLedgerStore` + `Ledger.sq`: `deleteKeys` chunked below the
  bind-variable limit (reuse the `MARK_PRESENT_CHUNK` reasoning), `clearAbsenceMarks` as one
  `UPDATE ledgerRow SET absent = 0 WHERE absent = 1`; each dings `changes` only when it changed a row.
  **No `.sqm` is added.** `Schema.version` must not move (design D5).
- [x] 1.3 Implement both in `:adapter:generic:fake`'s `InMemoryLedgerStore` and in the
  `domain/feature` commonTest doubles (`InMemoryLedgerStore`, `FakeLedgerStore`,
  `OsDrivenUploadMechanismTest`'s store).
- [x] 1.4 Expose both on `LedgerWriter` only. Run `clearAbsenceMarks()` once per cycle in
  `UploadCycle.settle`, beside `backfillEventId`, with the same `runCatching` posture.
- [x] 1.5 In `UploadCycle.enqueue`, replace `ledger.markAbsent(row.assetId)` with
  `ledger.deleteKeys(listOf(row.key))`, and fix the log line and KDoc ("marked, not failed" → "deleted
  by key").
- [x] 1.6 Extend `LedgerStoreContract` / `LedgerRecordGuardContract` (`:test:world`) with the new
  scenarios: named keys only, the sibling survives, no-op writes nothing, the sweep clears and preserves
  fields, the sweep on a clean ledger does not ding, more keys than one statement binds. Every
  `LedgerStore` binding runs them.
- [x] 1.7 Cycle test: a `DISCOVERED` `X-live` that resolves to nothing is deleted while its `COMPLETED`
  `X-primary` sibling is untouched, including under a scoped (partial-grant) resolve.

## 2. The gated presence diff

- [x] 2.1 In `UploadCycle.decide`, when `discovery.fullEnumeration`: read every row once, compute
  `admittedAssetIds(rows, policy)`, and plan the deletion as the keys of rows whose asset is admitted,
  absent from the walk's **candidate** ids (not the admitted set), and not `REQUESTED` (design D3).
  Carry it on `CyclePlan`.
- [x] 2.2 In `UploadCycle.update`, apply the planned deletion through `deleteKeys` before recording
  discoveries. The manifest is published later in `publish`, so a departed asset is never listed by
  the cycle that saw it leave.
- [x] 2.3 Add a rows-deleted count to the `Enumeration` audit line.
- [x] 2.4 Cycle tests, one per spec scenario in `sync-ledger` "Deletion is a presence diff over an
  authoritative walk": in-window departed → deleted; out-of-window → kept; bare → kept;
  `fullEnumeration = false` → nothing deleted; `REQUESTED` kept, then deleted after it settles;
  an asset the admission excludes but the walk returns → kept; a raised cutoff → rows kept.
- [x] 2.5 Add a KDoc note at `predicateFor` (`PhotoKitCandidateSource.kt`): a new clause that narrows
  the walk below the policy's capture window makes rows it excludes deletable by the diff (design
  Risks). Pin in a cycle test that presence is the candidate set, not the admitted set.

## 3. Read only what the ledger does not know

- [x] 3.1 Add `recordAllUnlessSettled(entries)` to `LedgerStore`: the guarded record statement applied
  per entry inside one transaction, dinging once if any applied. Implement it in SQLDelight, the fake
  and the test doubles, and extend the contract, including an atomicity case (a failure mid-batch leaves
  no row from that batch).
- [x] 3.2 In `UploadCycle.decide`, read `resources()` only for admitted candidates whose asset has no row
  or has a bare row, using the row read from 2.1 (spec `sync-ledger`, "A walk re-reads only the assets
  the ledger does not fully know").
- [x] 3.3 In `UploadCycle.update`, collect the engine's `Work` resources and record them through one
  `recordAllUnlessSettled`, replacing the per-resource `recordDiscovered` loop. The backfill of bare rows
  stays per row: it is idempotent and bare-only.
- [x] 3.4 Add the skipped count to the `Enumeration` audit line (seen / read / new / already-uploaded /
  deleted).
- [x] 3.5 Cycle tests: a fully-known asset's `resources()` is never invoked; a bare-row asset and a
  row-less asset are read; a seed that listed only the primary is completed with the live role.

## 4. Remove the cursor

- [x] 4.1 `UploadDiscovery.discover(policy): Discovery`, and `Discovery(candidates, fullEnumeration)`.
  Drop `sinceToken`, `nextToken` and `removedAssetIds`, and restate the `fullEnumeration` KDoc as "authoritative for
  deletion" (design D2).
- [x] 4.2 `IosDiscovery`: delete the change-feed branch and the token archiving; `Readable` →
  `fullEnumeration = true`, `NotReadable` → no candidates, `false`.
- [x] 4.3 `SelectionScopedDiscovery`: drop the token pass-through, and restate its KDoc around "never
  authoritative" (spec `limited-photo-access`).
- [x] 4.4 `UploadCycle`: drop `store`, `loadToken`/`saveToken`, the removals loop, `markPresent`, and the
  "cursor advance" comment block; fix the stale class KDoc at `UploadCycle.kt:40`.
- [x] 4.5 Delete `DiscoveryStore`, `IosDiscoveryStore`, `inMemoryDiscoveryStore` (+ `InMemoryStores.kt`
  entry, `Factories.kt`), `DISCOVERY_TOKEN_KEY`, and `UploadPorts.discoveryStore`.
- [x] 4.6 Delete `AppPorts.clearDiscoveryCursor` and the cursor steps in `UploadReconciler`,
  `ReconfigureEvent` and `ResetDeviceState`, plus their tests' cursor assertions
  (`ReconfigureEventTest`, `ResetDeviceStateTest`). Wiring: `SnapSyncRoot`, `UrlSessionUploadController`,
  `UploadExtensionRoot`, `UploadCore`, `SnapSyncApp`.
- [x] 4.7 Delete `markAbsent` / `markPresent` from `LedgerStore`, `LedgerWriter`, `Ledger.sq`,
  `SqlDelightLedgerStore` and every double, and their contract cases. Keep the `absent` column and the four
  `absent = 0` filters (design D5).
- [x] 4.8 `:test:architecture`: remove `discovery.changeToken` from `RuntimeIdentityTest`, and the
  `clearDiscoveryCursor` seam from `CompositionSeamTest`.
- [x] 4.9 Grep for leftovers: `DiscoveryStore`, `changeToken`, `removedAssetIds`, `sinceToken`,
  `nextToken`, `markAbsent`, `markPresent`, `clearDiscoveryCursor` must not match under `adapter/ domain/
  app/ test/ ui/` outside comments that describe history.

## 5. World and harness

- [x] 5.1 `FakeUploadDiscovery` (`:test:world` `UploadFakes.kt`): make every readable discovery a full
  enumeration over `source.candidates(policy)` (the floor-narrowed read, never the full admission), and
  replace `expireToken` with an `unreadableWalk` lever that returns no candidates and
  `fullEnumeration = false` (spec `harness-world-model`).
- [x] 5.2 `World.kt`: drop `discoveryStore` and the `clearDiscoveryCursor` wiring. Update
  `UploadCycleWorldTest` and `CycleEntryGateIntegrationTest` (which asserted cursor stability) to
  assert the ledger instead.
- [x] 5.3 World/integration tests for the new scenarios: removal deletes in-window rows; narrowing keeps
  rows; a denylisted-album asset stays present; an unreadable walk deletes nothing.
- [x] 5.4 `:app:desktop`: remove the "Expire change token" action from `WorldInspector` /
  `WorldInspectorController` (spec `full-stack-harness`).

## 6. Verify

- [x] 6.1 `./gradlew build`: the canonical check, including `:test:architecture` and every
  `LedgerStoreContract` binding.
- [x] 6.2 `./gradlew compileIosMainKotlinMetadata`: the Linux-runnable iOS proxy (`IosDiscovery`,
  `IosLedgerStore`, both roots).
- [x] 6.3 `./gradlew architectureDiagrams` and commit `architecture/` (the port set changed).
- [x] 6.4 `npx --yes @fission-ai/openspec@1.5.0 validate --specs --strict` and
  `… validate always-full-enumerate --strict`.
- [ ] 6.5 The native driver's contract run (`iosSimulatorArm64Test`) runs on CI `macos-26`. Confirm
  it is green on the PR, since `recordAllUnlessSettled`'s transaction is driver-specific.

## 7. Measure on device (design Open Questions — the user's call)

- [ ] 7.1 If wanted before merge: on the SE2 with a rig build, seed an event-sized library, force
  foreground cycles, and read the audit line and `platform.discoverResources` durations from
  `debug.log` for (a) a trigger-driven cycle over a fully-known library, and (b) completion-driven
  cycles during an app-driven backlog drain. Record the numbers in `design.md`.

## 8. At sync (not part of apply)

- [ ] 8.1 Hand-edit the `## Purpose` sections a delta cannot carry, to drop the cursor/change-feed
  wording: `sync-ledger`, `ios-photokit-upload`, `architecture-guards`, `upload-lifecycle`,
  `leave-event`, `sync-engine` (design D9).
