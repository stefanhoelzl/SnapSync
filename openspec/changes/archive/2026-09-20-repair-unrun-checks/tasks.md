## 1. Turn the migration check on

- [x] 1.1 Set `schemaOutputDirectory` on both database blocks in `adapter/generic/app/build.gradle.kts`,
      pointing each at a `databases/` folder inside its own `srcDirs`. Confirm
      `generateCommonMainLedgerDatabaseSchema` and `generateCommonMainDownloadDatabaseSchema` now appear
      in `./gradlew :adapter:generic:app:tasks --all`.
- [x] 1.2 Write the build-file comment that carries the contract this rests on: what the snapshot is, why
      staleness is not a defect (an older snapshot verifies more of the chain), and that the task checks
      schema only. This is the paragraph a future reader needs in order not to "tidy up" the snapshot.
- [x] 1.3 Run both generate tasks and commit the snapshots **at the current schema** (ledger `9.db`, and
      the download database's), before writing any new migration — so the index migration in §2 is the
      first thing the enabled check verifies.
- [x] 1.4 Prove the check now bites: add a throwaway migration introducing a column absent from
      `Ledger.sq`, confirm `verifyCommonMainLedgerDatabaseMigration` FAILS with a schema diff, then delete
      it. Record the observed failure output in the PR description — a check nobody has watched fail is
      the thing this change exists to remove.

## 2. Repair the schema drift

- [x] 2.1 Add `9.sqm` (v9 → v10) creating `ledgerRow_destinationPath`, with the comment block the
      neighbouring migrations carry: row-preserving, catalog-plus-btree only, zero upload work, and why
      the index is load-bearing (the acknowledgement lookup runs on an OS deadline).
- [x] 2.2 Run `./gradlew :adapter:generic:app:verifySqlDelightMigration` and confirm it passes — the
      migrated schema now equals the created one.
- [x] 2.3 Add the per-migration test to `SqlDelightLedgerStoreTest`, matching the shape of its siblings:
      seed a v9 database with `DISCOVERED`/`REQUESTED`/`COMPLETED` rows, migrate, assert the index exists
      and every row keeps its key, state and columns.
- [x] 2.4 Re-run the by-hand chain replay (a v1 baseline through every `.sqm`, diffed against the `CREATE`
      statements) and confirm the only remaining divergence is the download database's `creationDate`
      default and column order — the one design.md D4 deliberately leaves.

## 3. Make the comments and the spec true

- [x] 3.1 Update the claim sites now that they are accurate, rather than leaving them as inherited
      assertions: `Ledger.sq` (three sites), `6.sqm`, `8.sqm`, and the comment at
      `SqlDelightLedgerStoreTest`'s data-only migration test. Each should point at what makes it true (the
      committed snapshot), not merely repeat that a task exists.
- [x] 3.2 Note in `Ledger.sq`, beside the `destinationPath` index, that it is created by both routes and
      why — this is the exact spot where the omission happened.
- [x] 3.3 Record the download database's known divergence in `DownloadStore.sq` or `1.sqm`, so the next
      person replaying the chain finds design.md D4's decision instead of rediscovering it.

## 4. Repair the forge test

- [x] 4.1 Rewrite `ForgeStatusHostTest` against the current shape: read `Layer` out of
      `container.stateFlow`'s `UiState`; `JoinPhase.Detailed(EventDetails, Step.Ready)` for the join gate;
      `Layer.Joined(membership, inviteUrl, health)`; event name and invite URL read from the reduced state
      rather than from the host. Keep each assertion's intent — a preset reaches its frame *through the
      real reduction* — and keep the KDoc's statement that a passing test proves no backend, attestation
      or library access is constructed.
- [x] 4.2 Handle `Layer.JoiningEvent`'s `form` and `range` fields deliberately: assert the resolved range
      the gate seeds from the loaded window, rather than weakening the assertion to make it compile.
- [x] 4.3 `./gradlew -Psnapsync.forge=true :ui:presentation:jvmTest` green.
- [x] 4.4 `./gradlew -Psnapsync.forge=true build` green on all three targets (JVM, `iosArm64`,
      `iosSimulatorArm64`) — the forge test compiles for each.

## 5. Close the rot hole

- [x] 5.1 Extend `build.yml`'s property-gated step to run `:ui:presentation:jvmTest` alongside
      `compileIosMainKotlinMetadata`, under the same two properties. One step, no new job.
- [x] 5.2 Extend that step's existing comment — the one recording that `:test:rig` rotted this way — to
      record that the forge test rotted the same way *beside* the guard, because the guard covered main
      only. The comment is the artifact that keeps the step from being narrowed again.
- [x] 5.3 Verify the guard actually catches it: break the forge test locally, confirm the extended command
      fails, restore it.

## 6. Specs and gates

- [x] 6.1 Confirm the two delta specs match what was built — `sync-ledger` (both indexes named, the new
      migration, the snapshot requirement) and `testing-architecture` (gated source sets include tests).
      Adjust the deltas to the code, not the code to the deltas, where they disagree.
- [x] 6.2 `npx --yes @fission-ai/openspec@1.5.0 validate --specs --strict` passes. Note that this checks
      structure only; it is not evidence the specs are true.
- [x] 6.3 `./gradlew build` green — the canonical check, including the now-meaningful verify tasks, the
      detekt tiers and the architecture guards.
- [x] 6.4 `./gradlew architectureDiagrams` and commit if anything moved (stale diagrams block the PR).
- [x] 6.5 Account for every module the diff touched against CLAUDE.md's module map, naming its capability
      delta or why none is needed — the archive's delta-completeness gate, run before archiving rather
      than at it.

## 7. Ship

- [ ] 7.1 One PR carrying both repairs, labelled `internal` — no customer-visible behaviour changes, and
      the index repair is a performance fix on a path no screen reports.
- [ ] 7.2 PR description states the measured evidence: the drifting-migration probe passing before and
      failing after, the replayed chain's diff, and the ten compiler errors across three targets.
- [ ] 7.3 `/ship --keep-workspace`, so the handoff report can leave this workspace before the workspace
      does.
