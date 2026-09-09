## Why

Two capabilities in the contract of record disagree about what happens when the database loses an
acknowledged upload record, and the one that is wrong is the one that grants the platform its licence.

`database`, under *The database holds only rebuildable state*, accepts a **10-second maximum data-loss
window on primary failover** and says the consequence is bounded because:

> "An acknowledged write lost to that failover window SHALL be repaired by the next manifest publish where
> the publishing device still asserts the fact … **There SHALL be no dedicated reconciliation pass.**"
>
> Scenario: *A lost upload record is repaired by the next publish* — "**WHEN** an acknowledged upload
> record is lost to a primary failover, so the resource's row is gone — **THEN** the device's next
> full-state manifest publish restores it, with no re-upload of bytes."

That was true under v1, whose manifest publish upserts a `resources` row per listed resource. It is **not**
true under v2. `api-endpoints` says so plainly — v2's manifest "records no upload … (the table has a
single writer there), **so no repair exists** to make a swallowed failure safe" — and `api/src/app.ts`
repeats it at the byte route: "v2's manifest writes no resource row at all, so nothing would repair it."

So `api-endpoints` is accurate and `database` is stale. The migration to v2 removed the repair; the
requirement that depends on it was never revisited. That leaves the specs asserting a self-healing
property the system no longer has, in the one place a reader goes to find out what losing a row costs.

The same requirement's neighbour is stale in a quieter way. *Replica staleness is unmeasured from the
edge* reads as though read replicas are known to exist. They are not: the probe it cites found
read-your-writes held **in every trial measured**, and its caution was reasoned **by analogy from
storage** — which genuinely is asynchronously replicated — rather than from anything established about the
database. The deployment is a single primary. The guard is still worth keeping for the day that changes,
but it should say what is true today rather than imply a hazard that is not present.

Both are the same failure: the v2 migration changed what a thing *is*, and the reasoning around it came
along unexamined. A third instance sits in `UploadReconciler`'s own comments, where the per-device listing
is still justified by storage-era read-after-write consistency.

## What Changes

- **`database` stops claiming the manifest publish repairs a lost upload record.** The rebuildable-state
  requirement keeps its shape — every row is still reconstructible without operator action, and that is
  still what makes the platform's limits acceptable — but the *upload record* case states its actual
  repair path under v2: the device re-performs the work when it does not believe the resource landed, and
  **nothing repairs it while the device does believe that**. The attestation case is untouched; a lost
  attestation record is still repaired by re-attesting.

- **The "no dedicated reconciliation pass" prohibition is removed.** It was a consequence of the repair
  that no longer exists, and as written it forbids the only mechanism that could replace it. Removing it
  does not mandate such a pass — it stops the contract ruling one out before it has been designed.

- **The replica requirement states its real basis.** Read-your-writes held in every trial measured; the
  concern was reasoned by analogy from storage; the deployment is a single primary. The guard survives
  unchanged in force — the sweep still decides on the primary, and any future change letting a destructive
  operation act on an ordinary read still has to re-confirm read-your-writes from the edge — but it binds
  **if the topology gains replicas** rather than implying it already has.

- **`UploadReconciler`'s stale justification is corrected** in passing: the comment arguing that a
  successful listing is authoritative still cites the storage `LIST`'s read-after-write consistency and
  two capability names that no longer exist (`bunny-upload-endpoint`, `bunny-list-endpoint`). Under v2 the
  listing is a database read, and the conclusion holds for a different reason — the byte route records
  the row non-best-effort and answers `502` if it cannot, so a `2xx` implies a committed row, and with a
  single primary a later read sees it.

- **No behaviour changes.** No code path is altered; the only Kotlin edit is a comment.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `database`: *The database holds only rebuildable state* — the upload-record repair path is corrected to
  match v2 and the "no dedicated reconciliation pass" prohibition is removed. *Replica staleness is
  unmeasured from the edge* — the requirement's basis is corrected; its normative force is unchanged.

## Impact

- `openspec/specs/database/spec.md` — two requirements.
- `domain/feature/upload/Reconciler.kt` — one comment block, no code.
- No API change, no schema change, no migration, no behaviour change.
- Reading order for a reviewer: `api-endpoints` lines 129–136 and 280–290 (already correct), then the two
  `database` requirements (stale), then `publishStatements` in `api/src/db.ts` (the `legacy` flag that
  makes the difference).
- Sequencing: independent of the other changes, but it should land **before** any reconciliation work, so
  that work is not proposed against a contract that forbids it.
- Changelog label: `internal`.
