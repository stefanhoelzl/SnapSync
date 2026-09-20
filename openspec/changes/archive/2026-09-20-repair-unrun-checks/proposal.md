## Why

Two checks this repo relies on report success without checking anything, and one of them is already
hiding a live defect.

`verifyCommonMainLedgerDatabaseMigration` runs inside `./gradlew build` and is cited across the ledger's
migrations, `Ledger.sq` and `sync-ledger`'s requirements as the proof that a migrated schema and the
`CREATE` statements are identical. It proves nothing: SQLDelight compares the migration chain against a
committed `.db` snapshot, no snapshot exists, and a deliberately drifting migration passes the task green
(measured). Replaying the chain by hand finds the drift the task exists to catch — **`7.sqm` adds the
`destinationPath` column but never creates `ledgerRow_destinationPath`**, so every device that *upgraded*
into the current schema lacks the index a freshly-created device has. That index is what the OS-driven
upload tier's acknowledgement path reads on a deadline; without it, every returned job scans the table.

`ForgeStatusHostTest` — the assertion that the marketing presets reduce, through the real container, to
the frames the App Store listing depicts — has not compiled since 2026-08-28. Its source set is gated on
`-Psnapsync.forge=true`, and the CI step that exists to compile property-gated trees (`build.yml`, whose
own comment records that `:test:rig` rotted this exact way) compiles `main` metadata only, not test
source sets. The guard built for this class of rot did not cover it.

Both are cheap to make true, and both should be true before any further ledger migration is written.

## What Changes

- **The ledger's migration chain becomes genuinely verified.** Set `schemaOutputDirectory` on both
  SQLDelight databases, generate and commit the schema snapshots. The existing verify tasks then compare
  the migrated schema against the created one on every `./gradlew build`, and fail with a schema diff when
  they disagree (measured: a probe migration adding an unmatched column fails the task).
- **Fix the drift the check was supposed to have caught.** A new ledger migration creates
  `ledgerRow_destinationPath`, so an upgraded device's schema matches a created one. `CREATE INDEX` is
  row-preserving and creates no upload work.
- **Record the second, benign drift.** `downloadAsset.creationDate` carries a `DEFAULT ''` from `1.sqm`
  that `DownloadStore.sq` does not declare, and the migrated column order differs from the created one.
  Neither is reachable at runtime (every insert is column-explicit), but the snapshot must be seeded
  against the schema that actually exists, so the difference is stated rather than silently normalised.
- **`sync-ledger`'s schema requirement names both indexes.** It currently names only the `assetId` index,
  so the spec's own statement of the schema is incomplete — which is why the missing one was invisible in
  review as well as in CI.
- **Repair `ForgeStatusHostTest`.** Rewrite it against the current shape: `container.stateFlow` carries
  `UiState`, not `Layer`; `JoinPhase.Ready` is now `JoinPhase.Detailed(EventDetails, Step.Ready)`;
  `Layer.Joined` takes `membership`, `inviteUrl` and `health`; the event name and invite URL are read from
  the reduced state rather than from the host. Ten compiler errors across three targets, not three.
- **Close the rot hole.** `build.yml`'s property-gated compile step also compiles and runs the forge test
  source set, so the same decay fails a PR instead of going unnoticed for weeks.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `sync-ledger`: the schema requirement states the **complete** set of created objects (both indexes, not
  just `assetId`'s); a new requirement makes the migration-chain verification a real, committed artifact
  — a schema snapshot per database — rather than a claim; the migration adding the missing index is
  stated as row-preserving and work-free, like its predecessors.
- `download-store`: its schema gains a committed snapshot too, and the one **known, deliberate
  divergence** between its migration chain and its `CREATE` statements becomes a recorded contract
  ("do not repair this") rather than a discovery waiting for the next person to replay the chain.
- `testing-architecture`: the canonical-check requirement extends to **build-property-gated source sets
  including their tests** — source no build compiles is source that rots, and a gate that compiles only
  the `main` half of a gated tree does not discharge it.

## Impact

- `adapter/generic/app/build.gradle.kts` — `schemaOutputDirectory` on both database blocks.
- `adapter/generic/app/src/commonMain/sqldelight/ledger/` — a new `.sqm` creating the missing index, the
  committed schema snapshot, and the comments in `Ledger.sq` / `6.sqm` / `8.sqm` that assert what the
  verify task proves (true from this change on).
- `adapter/generic/app/src/commonMain/sqldelight/download/` — the committed schema snapshot.
- `adapter/generic/app/src/jvmTest/.../SqlDelightLedgerStoreTest.kt` — a migration test for the new
  migration, alongside the existing per-migration tests, and the comment at the data-only migration.
- `ui/presentation/src/forgeTest/.../ForgeStatusHostTest.kt` — rewritten.
- `.github/workflows/build.yml` — the property-gated step also compiles/runs the forge tests.
- **Runtime, on upgraded devices only**: the acknowledgement path's `destinationPath` lookup stops being a
  table scan. No row is rewritten, no upload is created, and no downgrade hazard beyond the standard
  staged-revert stance the ledger's migrations already carry.
- No API, backend, or dependency change.
