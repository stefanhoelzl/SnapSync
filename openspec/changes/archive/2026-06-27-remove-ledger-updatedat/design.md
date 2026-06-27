## Context

`updatedAt` is a per-row `Instant` on `ledgerRow`, stamped by `LedgerWriter` on every record
operation from an injected `Clock`. Its **only** product is the status screen's `finishedAgo` line
("last … ago"): `MAX(updatedAt)` over fully-completed assets → `LedgerAggregates.newestCompletionAt`
→ `LedgerSnapshot` → `Overlaid` → `SyncProgress.lastFinishedAt` → presentation `relativeTime(...)` →
`UiState.finishedAgo`.

A prior interview established that this line is not worth its weight: redundant while uploading (the
count + in-progress indicator already say "working"), noise when complete ("47 synced · 3 d ago"
reads as stale though everything is safe), and the alternative "last checked" reading would *hide*
stalls. The decision was to remove the timestamp outright rather than redefine it. Classification is
unaffected — `SyncProgress.state` is already a pure function of counts.

**This change rebases on `drop-resource-versioning`** (in-flight in the `date` workspace), which
removes `Resource.version` and the `ledgerRow.version` column, drops `lastModified` from the rejoin
seam and the backend listing, and raises the SQLDelight dialect floor to SQLite 3.35 (its own
`2.sqm` uses `ALTER TABLE … DROP COLUMN`). The two changes are complementary — together they reduce
the row to `key, assetId, state, attempt`. To avoid a three-way collision on the migration number,
the engine files, the rejoin seam, and the backend, `drop-resource-versioning` lands **first** and
this change is authored against its post-merge baseline (so it neither re-numbers from `2.sqm` nor
re-does the `version`/`lastModified` work).

A code-wide sweep confirmed that after both changes every remaining `Clock`/`.now()` in production
Kotlin is one this change removes: `LedgerWriter`, `StatusContainerHost`, `JoinEvent`, and the
desktop `PanelController`. `drop-resource-versioning` keeps `updatedAt` (and those clocks); this
change is what finally makes the domain **clock-free**.

## Goals / Non-Goals

**Goals:**
- Delete `updatedAt` and every derived timestamp (ledger → status → presentation → UI → rejoin seed).
- Leave the domain **clock-free**: no `Clock` injection or `.now()` read in any production module.
- Preserve the existing on-device ledger across the upgrade (no forced re-enumeration/re-reconcile).
- Keep the status screen's behavior identical except for the removed "last … ago" line.

**Non-Goals:**
- Any replacement notion of time on the status screen (no "last checked", no "up to date · time").
- A sync-history / "backed up on <date>" feature. A future need would source a photo's capture date
  fresh, not reuse this record-operation timestamp.
- Re-doing anything `drop-resource-versioning` owns: the `version` removal, the `lastModified`
  removal (rejoin seam + backend + `bunny-list-endpoint`), or the 3.35 dialect bump.

## Decisions

### Remove, don't redefine
The timestamp is deleted rather than repurposed as a "last activity" stamp. A liveness stamp would be
*less* useful (it masks the one case — a stall — a timestamp could usefully expose), and keeping a
single app-level scalar still costs the cross-process write plumbing the field's removal otherwise
eliminates. Alternative considered (keep `lastCompletionAt` as one scalar): rejected — same UX wart
in the Completed state, and it retains a clock in `LedgerWriter`.

### Row-preserving `3.sqm` migration (`ALTER TABLE … DROP COLUMN`)
The schema is versioned: `1.sqm` (v1→v2, `assetId`, destructive) and — from `drop-resource-versioning`
— `2.sqm` (v2→v3, drop `version`, row-preserving). Removing `updatedAt` adds **`3.sqm` (v3→v4)**:
`ALTER TABLE ledgerRow DROP COLUMN updatedAt`. It is **row-preserving** because the dropped column is
neither the primary key nor indexed (the index is on `assetId`), so existing `COMPLETED` rows survive
and the upgrade forces no re-enumeration or re-reconcile. The SQLite 3.35 grammar this needs is
already in place from `drop-resource-versioning`'s dialect floor, so this change touches no
`build.gradle.kts`. Chosen over destructive to preserve the ledger; matches the `2.sqm` precedent.

### Presentation collapses to clock-free
With `finishedAgo` gone, `StatusContainerHost` loses `clock`/`now`, the `relativeTime` formatter, and
the `minuteTicker`. The second `combine(inputs, minuteTicker())` (whose sole purpose was aging
relative time) collapses to collecting `inputs` directly. `UiState.InProgress`/`Completed` drop their
`finishedAgo` parameter. The "Presentation formats and ticks relative time" requirement is removed
outright.

### Rejoin seed loses its timestamp
`drop-resource-versioning` set the seeded `COMPLETED` row's `updatedAt` to the join time (having
dropped `lastModified`). With `updatedAt` gone entirely, that value disappears and `JoinEvent` drops
its `Clock`/`joinTime` — the seed is now a pure state write.

## Risks / Trade-offs

- **[Lost stall signal in the InProgress/limbo state]** → Accepted by prior decision. When photos are
  discovered but iOS hasn't scheduled the extension (`inProgress = 0, pending > 0`), the screen now
  shows no detail line instead of "2 h ago". The counts + indicator remain.
- **[Collision with `drop-resource-versioning`]** → Mitigated by sequencing it first and authoring
  against its baseline: `3.sqm` (not a second `2.sqm`), no `version`/`lastModified` re-work, no
  dialect bump. If the order flips, this change must re-number to `2.sqm` and re-absorb the
  `lastModified`/backend/`bunny-list-endpoint`/dialect scope.
- **[Migration-chain schema drift]** → SQLDelight migration verification requires the migrated schema
  to equal the `.sq`; the v3→v4 `DROP COLUMN` produces exactly the new (timestamp-less) schema.
- **[Stale harness/spec text resurfacing]** → The desktop-test-harness spec already carries pre-
  gallery-counted copy; this change edits only the timestamp-bearing clauses and leaves unrelated
  staleness untouched to keep the delta focused.
- **[Large but purely subtractive test blast radius]** → Tests asserting
  `updatedAt`/`newestCompletionAt`/`finishedAgo`/`relativeTime` are deleted or simplified;
  `./gradlew build` + `compileIosMainKotlinMetadata` guard the KMP compile.

## Migration Plan

1. Wait for `drop-resource-versioning` to land on `main`; branch from there.
2. Land the schema change together: edit `Ledger.sq` (drop column, `MAX(updatedAt)` aggregate,
   `put`/`get`), add `3.sqm` (`DROP COLUMN updatedAt`), drop `EpochMillisAdapter` — one commit.
3. Ripple the type removals up the read path (engine → status → presentation → UI), then the rejoin
   seed and harness call sites.
4. No staged rollout / feature flag — the change is display-only on the client. Rollback is reverting
   the change; the ledger remains rebuildable from enumeration/reconcile.

## Open Questions

None outstanding — the design was resolved in the preceding interview/explore sessions, and the
sequencing against `drop-resource-versioning` is decided (it lands first).
