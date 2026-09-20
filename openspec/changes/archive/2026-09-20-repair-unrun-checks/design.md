## Context

Two independent checks claim to verify something and do not. They share no code; they share the property
that a green result carries no information.

**The SQLDelight migration verify.** `verifyCommonMainLedgerDatabaseMigration` and its download-database
sibling are registered by the plugin and run inside `./gradlew build` — confirmed in a full build log.
SQLDelight verifies by walking the database's source folders for committed `.db` snapshots, applying every
migration *after* each snapshot's version, and comparing the result with the schema the `.sq` `CREATE`
statements produce. With no snapshot committed there is nothing to apply migrations to, so the task
executes, finds no work, and succeeds. Measured: a probe migration adding a column absent from
`Ledger.sq` left the task green.

The claim the task is supposed to discharge is load-bearing and widely cited — `sync-ledger`'s
requirements state it three times, and `Ledger.sq`, `6.sqm` and `8.sqm` each justify a `DEFAULT` by it.
Replaying the chain in SQLite against the created schema finds two real divergences:

| divergence | reachable? |
|---|---|
| `7.sqm` adds `destinationPath` but never `CREATE INDEX ledgerRow_destinationPath` | **yes** — the upload-acknowledgement lookup table-scans on every upgraded device |
| `downloadAsset.creationDate` gets `DEFAULT ''` from `1.sqm`; `DownloadStore.sq` declares none. Migrated column order differs too | no — every insert is column-explicit |

The first is the defect the check exists to prevent, sitting in the tree for as long as the check has been
inert. `sync-ledger`'s own schema requirement names only the `assetId` index, so the spec could not have
caught it either.

**The forge preset test.** `ForgeStatusHostTest` asserts that each marketing preset reduces, through the
real `StatusContainerHost`, to the frame the App Store listing depicts — the property
`ios-appstore-metadata` fixes as "the forging substitutes the container's inputs, never its output". Its
source set is added to `commonTest` only under `-Psnapsync.forge=true`. `screenshots.yml` builds the
`SnapSyncForge` Xcode scheme (forge `main`, no JVM tests); `build.yml`'s property-gated step runs
`compileIosMainKotlinMetadata`, which is `main` metadata only. So nothing compiles it. It stopped
compiling at `93e8eb4f` (2026-08-28), which moved the event name and invite URL onto `Layer.Joined`.

That `build.yml` step exists *because* gated source rots — its own comment records `:test:rig` referencing
a constant that never existed, "from the commit that introduced it until this one". The guard was built
for this failure and then did not cover its test half.

## Goals / Non-Goals

**Goals:**

- Make the migration-verify task verify, so that the claim in `sync-ledger`, `Ledger.sq` and the migration
  comments is true rather than aspirational — and so the migrations written after this change are checked
  as they land.
- Remove the reachable drift: an upgraded device's ledger schema becomes identical to a created one.
- Make `sync-ledger`'s statement of the schema complete, so a missing object is visible in review.
- Restore `ForgeStatusHostTest` to compiling and passing, and make `./gradlew -Psnapsync.forge=true build`
  green.
- Close the gap that let it rot, inside the CI step already built for that purpose.

**Non-Goals:**

- Retroactively proving the *existing* migration chain. A snapshot generated today verifies forward only
  (D2); the one harmful historical divergence is fixed by hand instead, and the rest of the chain keeps
  the coverage it already has — the per-migration tests in `SqlDelightLedgerStoreTest`.
- Normalising the benign download-database divergence (D4).
- Any change to the ledger's columns, states, discovery or upload lifecycle. Those belong to the
  subsequent phases of the wider rework and are deliberately untouched here.
- Specifying the capture pipeline or the forge binary's containment; both are settled and unchanged.

## Decisions

### D1 — Enable the check rather than delete the claim

The alternative was to strip "the verify task proves the two schemas identical" from the spec, the five
code comments and the test comment, leaving verification off. That is honest and cheap, and it was
rejected: it converts a false statement into no statement while the reachable drift stays in the tree and
undetected, and every migration the following phases add stays unverified. Enabling costs one line per
database plus a committed snapshot, and it fails with a readable schema diff (measured on a probe
migration).

A third option — hand-writing a test that builds both schemas and diffs them — was rejected as a
reimplementation of what the plugin already does, in a place where it would itself need maintaining.

### D2 — Snapshot the current schema; do not reconstruct history

`generate<...>Schema` emits a `.db` at the *current* version from the `CREATE` statements. It therefore
proves nothing about migrations that already shipped: to catch the `7.sqm` omission retroactively, the
snapshot would have to be the schema as it stood *before* `7.sqm`, which means generating it from an old
checkout and committing an artifact no current source produces.

Rejected as archaeology with a poor ratio: the divergence it would find is already found (by hand, and
recorded above), and the fix is the same either way. The snapshot is seeded at the current version and its
job is every future migration.

One property makes this safe to leave unattended, and it is worth stating because it is the opposite of
the usual worry about generated artifacts: **a stale snapshot is strictly stronger than a fresh one.** An
older snapshot has more migrations applied to it before the comparison, so forgetting to regenerate after
adding a migration weakens nothing. There is consequently no freshness gate here, and none is needed —
unlike `architecture/`, where staleness is a real failure and *is* gated.

### D3 — Fix the index drift with a migration, not by deleting the index

The two ways to make the schemas agree are to add the index to the migration chain or to remove it from
`Ledger.sq`. The second is rejected on the merits, not on principle: the index backs the
upload-acknowledgement lookup, which `ios-photokit-upload` requires the extension to perform on every
returned job, in a process the OS invokes with a deadline. Removing it would resolve the divergence by
making every device as slow as the upgraded ones.

`CREATE INDEX` is row-preserving — it builds a new b-tree over existing rows and rewrites none of them —
so it settles nothing, creates no upload work, and keeps the property every ledger migration since `2.sqm`
has carried: a surviving `COMPLETED` row is what stops the next cycle re-uploading stored bytes.

Sequencing matters and is not incidental: the snapshot is generated **before** this migration is written,
so the migration is the first thing the newly-enabled check verifies. The fix and the proof of the fix
land together.

**`IF NOT EXISTS` is required, and the check is what found that out.** The omission split the v9
population into two shapes, and the migration runs on both: a database migrated through `7.sqm` has no
index, while one **created fresh** at v7/v8/v9 was built from the `CREATE` statements, which have always
carried it. A bare `CREATE INDEX` fails on the second — *index already exists* — which is a crash on
update for every recent install.

This was not reasoned out in advance; the enabled check failed on it immediately, because the committed
snapshot is exactly a fresh-at-v9 database. That is the change justifying itself on its first run: a
shipped-crash bug caught before any device saw it, by the task that had been reporting success on an empty
comparison for as long as it had existed.

SQLite normalizes `IF NOT EXISTS` out of `sqlite_master.sql` (measured), so both routes still produce
byte-identical schema text and no drop-and-recreate is needed to force agreement.

The consequence for coverage is worth stating, because it is the inverse of the usual one: the snapshot
verifies the **no-op** half of this migration, not the half that does the work. No snapshot represents an
upgraded-through-`7.sqm` database, so the migration test carries that half — and therefore asserts both,
one of which the snapshot can never reach.

### D4 — Leave the download-database divergence, and say so

`downloadAsset.creationDate` differs between chain and `CREATE` in its declared default, and the migrated
column order differs. Neither is observable: every statement in `DownloadStore.sq` names its columns, so
ordinal position never leaks, and the column is `NOT NULL` with every insert supplying it, so the default
is never applied.

Making them agree means rebuilding the table (SQLite cannot reorder columns or alter a default in place),
which puts real rows through a copy to erase a difference nothing can read. Rejected. The snapshot is
seeded from the `CREATE` statements, so this divergence is neither fixed nor detected — it is recorded
here so that the next person to replay the chain finds a decision rather than a discovery.

### D5 — Extend the existing CI step; run the test, don't just compile it

A separate job for a source set used only by marketing screenshots is not worth a job. The existing
property-gated step already carries both properties and exists for exactly this; adding
`:ui:presentation:jvmTest` to it runs on the same Linux runner in seconds.

Running rather than compiling is deliberate. A compile would have caught this particular rot, but the
test's value is its assertion — that a preset reaches its frame *through the real reduction* — and that is
the property `ios-appstore-metadata` depends on. A gate that compiles an assertion without evaluating it
is the same shape of check this change exists to remove.

### D6 — Rewrite the test against the reduced state, not the host

The repair is not mechanical: `container.stateFlow` carries `UiState`, so `Layer` must be read out of it;
`JoinPhase.Ready` became `JoinPhase.Detailed(EventDetails, Step.Ready)`; `Layer.Joined` gained
`membership`, `inviteUrl` and `health`; `Layer.JoiningEvent` gained `form` and `range`. The event name and
invite URL, which the test read from the host, now live on the reduced state.

The test reads them from there. That is not merely the available route — it is the better assertion, and
the commit that broke the test is the one that made it so: reading the name off the reduced `Layer.Joined`
asserts what the screen actually renders, where reading it from a host property asserted a value beside
the state that the screen might or might not use.

## Risks / Trade-offs

- **A committed `.db` is a binary a reviewer cannot read** → the `.sq` `CREATE` statements remain the
  human-readable statement of the schema, and the check reports its failure as a textual schema diff, not
  as a binary mismatch. The snapshot is an input to a tool, never the contract.
- **The new migration runs on devices with uploads in flight** → `CREATE INDEX` takes no row locks that
  outlive it on a table of this size, touches no row, and changes no state. A device interrupted
  mid-migration re-runs it: SQLDelight applies migrations transactionally and the statement is
  idempotent in effect.
- **Downgrade** → unchanged from every prior ledger migration: the native driver refuses a database newer
  than the binary's compiled schema, so a revert of this change keeps the `.sqm` and reverts only what
  sits above it. There is no Kotlin counterpart to revert here, which makes the staged revert trivial.
- **The verify task checks schema, never data** → unchanged and still true: `8.sqm`-style data-only
  migrations remain invisible to it, which is why `SqlDelightLedgerStoreTest` asserts their effect. The
  spec sentence saying so stays, and stays accurate.
- **Enabling a check can surface further drift mid-implementation** → the chain has been replayed and
  diffed; the two divergences above are the complete set for both databases. If the task nonetheless
  fails on something else, that is a finding, and it stops the change rather than being worked around.
- **The forge test can still rot on the Kotlin/Native side** → the CI step runs the JVM test only. The
  same source compiles for `iosArm64` and `iosSimulatorArm64` under the forge property, and the Native
  half is not covered here; a Native-only breakage in this file would still reach `screenshots.yml`
  first. Accepted: the file is plain presentation-layer Kotlin with no platform surface, and
  `testing-architecture` already records that Native-only breakage is caught by the simulator gate.

## Migration Plan

1. Set `schemaOutputDirectory` on both database blocks; generate and commit `9.db` (ledger) and the
   download snapshot **at the current schema**, before any new migration exists.
2. Add the ledger migration creating `ledgerRow_destinationPath`, with its per-migration test. The verify
   task now has something to check and checks it.
3. Repair `ForgeStatusHostTest`; extend `build.yml`'s property-gated step to run it.
4. Update `sync-ledger` (complete schema statement, the verification requirement) and
   `testing-architecture` (gated source sets include their tests).

Rollback is per-part and independent: the CI step and the test repair revert cleanly; the migration
reverts under the staged stance above.

## Open Questions

None blocking. One observation recorded rather than acted on: `testing-architecture`'s canonical-check
requirement defers job ownership to "`ci-build` and `ios-ci`", and no `ci-build` capability exists in
`openspec/specs/`. `build.yml` therefore has no owning spec, which is why this change places its CI
requirement in `testing-architecture`. Whether `ci-build` should exist, be created, or the reference be
dropped is a question for a change that is actually about CI ownership.
