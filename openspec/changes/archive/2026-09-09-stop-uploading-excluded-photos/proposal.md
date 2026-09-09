## Why

A member who narrows their capture-date cutoff is told one thing and gets another. The reconfigure
surface shows "a live count of the photos that will be shared" (capability `reconfigure-membership`),
the manifest declares that narrowed set — and the uploader keeps sending the photos the member just
excluded, for as long as it takes to drain the backlog a wider policy recorded.

`photo-selection-policy` already forbids this, twice over. *One policy gates both byte upload and
manifest listing*: "A photo excluded by the policy … SHALL **neither** have its bytes uploaded **nor**
appear in the manifest." And *The admitted set is a single derivation every consumer receives* names the
exact mechanism of the defect: "Admission SHALL be applied at the point of **query** … and no consumer
SHALL treat an **upstream-filtered structure** as the admitted set. An upstream stage MAY exclude assets
earlier … but such a pre-filter enforces only a subset of the rules, so the consumer still asks the
policy."

`UploadCycle.enqueue` reads `rowsNeedingJob(limit)` and hands those rows to the platform. A ledger row is
an upstream-filtered structure: it records that the policy admitted the asset **at the moment it was
walked**. Nothing re-asks. The policy has five consumers and four of them ask `EventPhotoSet`; the byte
upload is the one that asks nobody.

This is a bug against a requirement that is already correct. The contract does not change.

The window is not narrow. `enqueueBatchSize` is 16, so a first walk over a large library leaves a backlog
that drains over hundreds of OS-scheduled cycles, and a raised cutoff does **not** clear the discovery
cursor (`ReconfigureEvent` clears it only when the cutoff is **lowered**). A member who joins with the
default range, lets the walk run, and then narrows — which the surface's live count actively invites —
has thousands of excluded photos still queued behind a promise that says forty.

Nothing is exposed: the manifest never declares those assets, so no event union serves them and no other
member sees them. What leaves is bytes — against the member's stated choice, on their cellular
connection, into the operator's storage, where they sit unreferenced until the sweep's floor releases
them. For a product whose founding rule is that a cutoff is "**required**, never absent", uploading what
the member excluded and declining to mention it is the wrong side of that rule.

## What Changes

- **The byte upload asks the policy, like every other consumer.** The cycle's enqueue stage derives its
  work from the admitted set rather than from the bare needs-job rows. The admission over ledger rows
  already exists and is already used — `projectDeviceManifest` builds `candidatesFromFacts` from row
  facts and asks `EventPhotoSet` — so the uploader adopts the construction the manifest already uses,
  against the same rows, with the same reduced fact set (capture-date bounds and the two id-set
  exclusions speak; origin defaults to admit-on-doubt, because an origin-excluded asset never earned a
  row).

- **The work bound moves from what is READ to what is RESOLVED.** Filtering after a bounded read
  starves: `rowsNeedingJob` returns rows in a stable key order, so an excluded backlog sitting at the
  front is re-read and re-discarded every cycle while admitted work further down is never reached — a
  silent, permanent stall, strictly worse than the bug being fixed. `enqueue`'s own contract already
  states the intended shape ("The batch bounds only what is RESOLVED"); the implementation bounds the
  read instead. A local row scan is cheap; the platform round-trip and the staged temp file are what the
  bound exists to protect.

- **A scenario pins the narrowing case.** Every existing scenario under both requirements reasons about
  a *static* policy — "for the same capture-date range", "a photo captured after the ceiling". None
  covers a policy that **narrows after rows were recorded**, which is the hole this shipped through. The
  delta adds that scenario so the fix cannot silently regress.

- **A consequence, not a change here:** `resetTo`'s clear currently has an unwritten second job — it
  drops a prior event's non-settled rows before they can upload under a new membership's policy. Once
  the uploader admits, that job disappears, and the clear's remaining purpose (dropping phantom
  `REQUESTED` rows) is already covered on both tiers. That unblocks a non-destructive reconcile, but no
  reconcile behaviour changes in this change.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `photo-selection-policy`: no requirement changes meaning. Adds scenarios pinning the **narrowing**
  case — that a membership which raises its cutoff stops uploading the newly-excluded assets, including
  ones already recorded as needing a job — under *One policy gates both byte upload and manifest
  listing* and *The admitted set is a single derivation every consumer receives*.
- `sync-ledger`: the needs-job read's bound is re-stated as bounding the work a cycle **resolves**, not
  the rows it reads, so a bounded read cannot starve admitted work behind excluded rows.
- `harness-world-model`: the world's fake change feed derives `removedAssetIds` from the **unscoped**
  gallery, never from the policy-scoped read. Found during implementation, not anticipated here: the fake
  diffed the scoped read, so a narrowing reached the cycle as a mass deletion and the cycle marked exactly
  the excluded rows absent — doing the fix's job in the harness alone and hiding whether the fix existed.

## Impact

- `domain/feature/upload/UploadCycle.kt` — the enqueue stage: admission before resolution, and the bound
  applied to admitted rows.
- `domain/ports/LedgerStore.kt` + `domain/feature/upload/LedgerWriter.kt` — `rowsNeedingJob`'s contract,
  and its SQLDelight and in-memory backends.
- `domain/model/DeviceManifest.kt` — `candidatesFromFacts` gains a second caller; it may move beside the
  admission rather than staying private to the manifest projection.
- No backend change. No wire-format change. No migration.
- Tests: `:test:integration` over `:test:world` can drive the whole path — join wide, walk, narrow the
  cutoff, run cycles, assert the excluded assets' bytes never reach the backend store while the admitted
  ones still do. The starvation case is the second test and the one that catches a careless fix.
- Changelog label: `bug`.
