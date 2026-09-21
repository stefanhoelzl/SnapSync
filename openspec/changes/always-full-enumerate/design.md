## Context

**The walk today.** `UploadCycle.decide` calls `UploadDiscovery.discover(store.loadToken(), policy)`.
With no usable token, `IosDiscovery` performs a full enumeration: it fetches `PHAsset` narrowed by the
membership's capture range, plus a screenshot/screen-recording subtype exclusion (`predicateFor`), and
reports `fullEnumeration = true`. With a token, it performs an incremental change-feed walk that returns the
changed assets plus `removedAssetIds`. The cycle's `update` stage then:

1. marks every `removedAssetIds` asset absent (`markAbsent(assetId)`);
2. clears the mark of every asset the walk returned (`markPresent`), ordered after step 1 so that an asset
   named by both ends up present;
3. records `DISCOVERED` rows for new work and backfills manifest detail onto bare rows;
4. saves the next token **unconditionally, before enqueue**. The old "advance only when fully drained" rule
   was retired by `archive/2026-08-27-fix-cap-truncation-loop`; only `UploadCycle.kt:40` and one
   `ios-photokit-upload` scenario still describe it.

`enqueue` then resolves the ledger's `DISCOVERED`/`FAILED` rows through `resourcesFor(keys)`. A key that
resolves to nothing triggers `markAbsent(row.assetId)`.

**Four facts shape this design.** Each was verified against the tree.

- **`fullEnumeration` has no production reader.** `IosDiscovery`, `SelectionScopedDiscovery` and the world
  fake set it, and nothing reads it. The "retain-live reconcile" its KDoc describes was removed on purpose
  (`sync-ledger`, "The ledger is never pruned by the selection policy"), because it was fed the
  **policy-admitted** set: raising a cutoff pruned the `COMPLETED` rows of photos still in the library.
- **The ledger is device-global and the walk is event-bounded.** The re-join seed resets the ledger to the
  device's stored-file listing across every event, and every seeded row is bare (`creationDate = ""`,
  `Reconciler.kt:141`). The walk's fetch is bounded by the capture range, widened by one day
  (`PREDICATE_WIDEN_SECONDS`).
- **A selection snapshot is not a library.** `SelectionScopedDiscovery` returns the partial-grant
  selection and sets `fullEnumeration = false` deliberately: *"an uploaded, later-deselected photo keeps its
  `COMPLETED` row (deselection is not withdrawal; upload is a publish)"*. `IosDiscovery` returns
  `fullEnumeration = false` for `CandidateRead.NotReadable` too: *"an un-enumerated cycle must cost an idle
  pass, never a photo"*.
- **The resolve-failure path selects by key and mutates by asset.** `selectNeedingJob` is key-grained and
  `markAbsent` is `WHERE assetId = :assetId`. Several resources of one photo share an `assetId` and hold
  per-key states, so a `COMPLETED` primary row plus a `DISCOVERED` Live Photo video row is an ordinary
  state. Under a partial grant the video key resolves to nothing, and today both rows are marked.

**Cost.** Measured on the SE2 (iPhone12,8 / iOS 26.6): an incremental walk that finds nothing takes
5–17 ms. A full enumeration takes 59–80 ms at 66 candidates, 145 ms at 1084
(`archive/2026-08-27-fix-cap-truncation-loop`), and 388 ms at 1054 (`archive/2026-09-09-bound-enqueue-to-free-slots`).
**Those figures cover `discover()` only**, meaning the fetch plus per-asset facts. Resource reads have been
deferred to `Candidate.resources()` since `archive/2026-07-24-introduce-candidate-source`, and
`decide` pays them for every **admitted** asset the walk returns. The measured per-asset cost ranges from
~3 ms (16 keys in 54 ms, batched, idle; 2026-09-09) to ~110 ms (`archive/2026-07-10-require-photo-cutoff`).
Under an incremental walk that set is the changed assets; under a full walk it is the member's entire
in-window library, on every cycle. SNAPSYNC-16 (iPhone11,2 / iOS 18.7.9, 104 concurrent imports) recorded
6.1–7.2 s for 224 candidates. That is an older device under load, and it is the honest worst case.

## Goals / Non-Goals

**Goals:**

- No persisted discovery cursor anywhere: no port, no store, no App-Group key, no clear effect.
- A departed photo leaves the device manifest on the next authoritative walk, including a deletion the
  change feed would have missed.
- Deletion can never be triggered by the selection policy narrowing, by a partial grant, by an unreadable
  library, or by the ledger holding rows from another event's window.
- A full walk every cycle stays near the cost of the fetch. Resources are read only for assets the ledger
  does not yet fully know.
- No schema migration. Reverting the change is a Kotlin-only revert.

**Non-Goals:**

- Dropping the `absent` column. Phase 2 drops it with `attempt` and `eventId`, so the version boundary is
  crossed once.
- Anything in the upload lifecycle, the arm, the `joinedEventId` marker, the stranded repair, or the
  enqueue bound (phases 2–5).
- Collapsing `UploadDiscovery.discover` into `CandidateSource.candidates`. Once the cursor is gone the two
  are nearly the same read, but the port still carries `fullEnumeration`, and `SelectionScopedDiscovery`
  wraps it. That is a follow-up, not this change.
- Detecting deletions under a partial grant. That is unchanged from today, where `removedAssetIds` is
  already empty on the scoped path.

## Decisions

### D1 — One change, one PR

The earlier plan was to split this into "add absence-by-diff, cursor intact" followed by "drop the cursor".
It was rejected for two reasons.

1. **The first half would barely run.** With the cursor intact, `fullEnumeration = true` happens only on a
   cold start, an expired token, or a cleared cursor. The diff would almost never fire in the field, so the
   risky moment (the diff running every cycle) lands in the second PR regardless.
2. **The first half has a hole.** It clears the marks and stops writing them, but incremental walks still
   report deletions through `removedAssetIds`. Either `markAbsent` stays for them, which contradicts the
   sweep, or they are ignored, which regresses deletion detection until the next full walk. Closing that
   hole means code, such as an asset-scoped delete from the change feed, that the second half then removes.

Instead, the tasks are ordered as separate reviewable commits: sweep → key-scoped resolve delete → gated
diff → read skip → cursor removal.

### D2 — `Discovery` loses the cursor and keeps `fullEnumeration`

`UploadDiscovery.discover(policy): Discovery(candidates, fullEnumeration)`. The `sinceToken`, `nextToken`
and `removedAssetIds` fields go.

`fullEnumeration` keeps its name, and its KDoc is restated as the one thing the cycle reads it for: *every
asset of the library inside the policy's capture window was returned*. That makes the walk authoritative
for deletion. `IosDiscovery` sets it for `CandidateRead.Readable` and clears it for `NotReadable`.
`SelectionScopedDiscovery` always clears it.

*Alternative, rejected:* rename it to `authoritative`. That is more accurate, but it churns every spec and
test that names the field, for a word the KDoc can carry.

### D3 — Deletion is a presence diff, gated twice

In `decide` (which reads and writes nothing), when `discovery.fullEnumeration` is true:

- **present** = the asset ids of **every candidate the walk returned**, not the admitted set. Presence has
  nothing to do with scope, which is the lesson of the retired retain-live reconcile.
- **rows** = every ledger row, read once (the read the manifest projection already makes).
- **in-window** = `admittedAssetIds(rows, policy)`: the same row-admission derivation the manifest and
  enqueue use. It applies the capture-date bounds and the id-set exclusions, and it rejects a bare row
  (`creationDate` sorts before every real cutoff).
- **delete** = the keys of rows whose asset is in-window and not present, **excluding `REQUESTED` rows**.

`update` deletes those keys before it records anything, so an asset that is gone and then appears in the
same walk cannot occur: presence and deletion come from one walk.

**Why `REQUESTED` is excluded.** A live platform job owns that row. `markTerminal` matches only
`state = 'REQUESTED'`, and the OS-driven ack path resolves a returned job to its row by `destinationPath`.
Deleting the row turns a normal completion into a reported "row moved on" anomaly. The row is deleted by the
first authoritative walk after it settles.

**Why the window must be the policy window, not the fetch window.** The fetch is widened by a day, and a
superset of the window is safe. Deciding by the row's own admission means a raised cutoff moves rows *out*
of the window instead of into the deletion set. That is what keeps the retired prune bug from coming back.

*Alternatives, rejected:*
- Compare `creationDate` to the cutoff in the cycle. A `:test:architecture` guard forbids any consumer from
  comparing a capture date itself, and doing so would duplicate the rule the policy owns.
- Diff against the admitted set. That is the shipped prune bug.
- Asset-scoped delete. It is equivalent for settled rows (an asset's rows share presence and date), but it
  cannot express the `REQUESTED` exclusion.

### D4 — The resolve-failure path deletes by key

`enqueue` deletes exactly the row whose key resolved to nothing. On a full grant that is a departed asset's
needs-a-job row; its settled rows are handled by D3. Under a partial grant it is a de-selected photo's
needs-a-job row: its `COMPLETED` siblings are untouched, and re-selecting the photo re-discovers it.

No authoritative gate is needed here. The path only ever reads rows needing a job, and a deleted
needs-a-job row costs one re-discovery, never a photo.

### D5 — The ledger seam: `deleteKeys` in, `markAbsent`/`markPresent` out, a sweep for old marks

- `LedgerStore.deleteKeys(keys: Collection<String>)` is writer-only (sanctioned only through `LedgerWriter`),
  chunked below the bind-variable limit like `markPresent` was, and dings `changes` when it deleted anything.
- `markAbsent` and `markPresent` are removed from the seam, the writer, the SQL and every fake.
- `LedgerStore.clearAbsenceMarks()` is one idempotent `UPDATE ledgerRow SET absent = 0 WHERE absent = 1`,
  run once per cycle in `settle`, beside `backfillEventId`. It matches nothing on every cycle after the
  first, and it is deleted with the column in phase 2.

The four reads that filter `absent = 0` keep the filter: after the sweep it excludes nothing, and removing it
would be a schema-facing edit that phase 2 makes anyway.

**Why the sweep is needed.** A row an earlier build marked stays invisible to the work read, the manifest
and both status reads forever once nothing can clear it. After clearing, every such row heals itself: a
needs-a-job row whose asset is gone re-enters `enqueue`, fails to resolve, and is deleted by key (D4); a
settled in-window row is deleted by the next authoritative walk (D3), which runs in the same cycle, before
the manifest is published. A bare row that neither path can reach is never projected either.

*Alternative, rejected:* a data-only `9.sqm` carrying the same `UPDATE`. That follows `8.sqm`'s precedent,
but any `.sqm` bumps `Schema.version`, and SQLiter's `migrateIfNeeded` refuses a newer database in any older
binary (`Database version N newer than config version M`, recorded in `4.sqm`). That refusal would hit a
TestFlight tester who reinstalls an earlier build, as well as a revert. The in-Kotlin sweep leaves the
version untouched, so phase 2's migration stays the only one.

### D6 — A full walk reads resources only for assets the ledger does not fully know

In `decide`, an admitted candidate's `resources()` is read **iff** the ledger holds no row for its asset, or
at least one of its rows is bare. Otherwise the asset is already fully recorded: an uploaded resource is
immutable and the ledger keeps no content version, so re-reading it can only produce `AlreadyUploaded` for
every key. A `FAILED` or `DISCOVERED` row is picked up by `enqueue` from the ledger, not from the walk.

**This is only safe if an asset is never left partly recorded.** Today the cycle records each new resource
with its own write, so a process death between a Live Photo's two writes leaves one role with no row. The
next walk would then see the asset with an enriched row and skip it, and the paired video would never upload.
So the cycle's new `DISCOVERED` rows are recorded in **one transaction**: a writer-only
`LedgerStore.recordAllUnlessSettled(entries)` with the same per-row settled guard as `recordUnlessSettled`.

A re-join seed produces bare rows, and those force a read, so a seed that listed only one role of an asset
still re-discovers the other.

*Alternatives, rejected:*
- No skip. That pays 3–110 ms per admitted asset on every cycle: 1–33 s for 300 event photos, inside the
  extension's `process()` deadline on the OS-driven tier.
- Derive the expected roles from facts (for example, add an `isLivePhoto` fact). That adds a fact and an
  extension-matching rule to avoid a transaction the SQL driver already provides.

### D7 — Removing the cursor

- `DiscoveryStore`, `IosDiscoveryStore`, `inMemoryDiscoveryStore`, token archiving in `IosDiscovery`, and
  `DISCOVERY_TOKEN_KEY` are deleted.
- `AppPorts.clearDiscoveryCursor` and the cursor steps in `UploadReconciler`, `ReconfigureEvent` and
  `ResetDeviceState` are deleted. Each existed to force the next walk to be full, and every walk now is.
  `ReconfigureEvent`'s spec'd outcome (a lowered cutoff re-shares newly in-scope photos on the next cycle,
  on both tiers) holds because the next walk's predicate is built from the new cutoff.
- The App-Group value under `discovery.changeToken` is **left in place**, not deleted. A reverted build reads
  it and either resumes from it, since PhotoKit keeps change history forward of any token, or falls back to a
  full enumeration if it has expired. Both are harmless by the same ledger argument that holds today.
  Removing the value would keep the literal (and its `RuntimeIdentityTest` pin) alive for no benefit.

### D8 — The world and the harness

`:test:world`'s token-delta fake becomes a full-enumeration fake over the in-memory gallery. Removals no
longer produce a signal; the next cycle's diff observes them. The operator's **expire-token** lever is
replaced by an **unreadable-walk** lever that returns `fullEnumeration = false` with no candidates, which is
the case the gate exists for. The full-stack harness's "Expire change token" button is removed. No
replacement button is added: the lever is reachable from `World` for tests, and the inspector does not
need it.

### D9 — Incidental cursor text across specs

Seven capabilities (`upload-lifecycle`, `event-link`, `leave-event`, `device-identity`, `gallery-status`,
`photo-selection-policy`, `module-architecture`) name the cursor only as state that is left untouched, as a
precondition's rationale, or, in `module-architecture`, as an example (`DiscoveryStore.loadToken`). Their
deltas restate those requirements without it; behavior does not change. `upload-completion-notify` gets the
same treatment for one scenario that lists "rows marked absent" among the ways a projection shrinks.

Two `sync-ledger` requirements keep their wording on purpose. "Ledger schema migration" records what `6.sqm`
did, which stays true. "The ledger row carries the manifest's presentation detail" says the projection read
excludes marked rows, which it still does; after the sweep it simply excludes nothing.

Several `## Purpose` sections also mention the cursor or the change feed (`sync-ledger`,
`ios-photokit-upload`, `architecture-guards`, `upload-lifecycle`, `leave-event`, `sync-engine`). A delta
cannot carry a Purpose, so those are edited by hand when the change is synced.

## Measured on device (task 7.1)

iPhone12,8 (SE2) / iOS 26.6, rig Debug build of this branch, full photo grant, 2026-09-21. A fresh upload-only
event whose window held seeded `kind=policy` assets (half above the 3 MP floor, so half admitted); the library
held 4,387 assets outside the window. Timings are from the processes' own `debug.log` invocation lines.

**OS-driven tier (extension `process()`), 200 candidates in the window, 100 admitted:**

| | cycle 1, first after join | cycle 2, library fully known |
|---|---|---|
| walk (`platform.discoverResources`) | 103 ms | 108 ms |
| admission + resource reads | **1,785 ms** (100 read, ~18 ms each) | **68 ms** (0 read, 100 skipped) |
| whole `process()` | 5,642 ms (includes 1.67 s re-join listing + reseed of 1,433 rows) | 1,511 ms |

The rest of cycle 2 is the ledger work read and job creation: `resourcesFor` of 16 keys (244 ms) and 16
`createJob` calls at ~36 ms each. Without D6, cycle 2 would have paid the same ~1.8 s of resource reads as
cycle 1, and would keep paying it on every cycle.

**App-driven tier, backlog drain (the completion-driven case in Risks):** 100 uploads drained in ~110 s over
**101 cycles**, each walking 400 in-window candidates (200 admitted, all fully known, 0 read):

| per cycle | min | median | p90 | max |
|---|---|---|---|---|
| walk | 55 ms | 79 ms | 81 ms | 86 ms |
| whole `runCycle` | 123 ms | 184 ms | 191 ms | 284 ms |

So a completion-driven cycle over a fully-known library costs ~80 ms of fetch and ~180 ms end to end on this
device, 17.3 s of cycle time across the whole drain. With resource reads at ~18 ms per admitted asset, the
same drain without D6 would have spent about 3.6 s per cycle, roughly six minutes in total.

**Not covered:** the older-device-under-import-load case (SNAPSYNC-16, iPhone11,2 / iOS 18.7.9) stays
unmeasured; this is one idle SE2.

**An unexplained hang during the first app-driven attempt.** The first attempt (after a no-op reconfigure moved
the device from `photokit` to `url_session`, then a 200-asset seed and a heartbeat) stopped logging mid-cycle
at 07:49:44 and stopped answering the control channel, while the process sat at 0.2–0.4 % CPU. The ledger
afterwards showed that cycle's `DISCOVERED` batch had committed, so the cycle ran past its last logged line.
No stack could be captured (DVT refuses SIGABRT/SIGQUIT, and a sysdiagnose needs a person at the phone).
After a SIGKILL and relaunch, the same drain ran to completion with no recurrence. The cause is not
established, including whether it predates this change.

## Risks / Trade-offs

- **[Cost on an older device under import load is unmeasured]** → D6 bounds the per-cycle resource reads to
  unknown assets. The cycle's audit line reports candidates, resources read, rows deleted and the walk
  duration, so the cost is visible in `debug.log`. The connected device is the SE2; the A12 case stays
  unmeasured unless one is available.
- **[Completion-triggered cycles walk again]** On the app-driven tier every upload completion triggers a
  cycle, and each cycle now walks. Making the ledger the work source (`ios-url-session-upload`, "The producer
  tops up from the ledger, not from the walk's output") ended a walk *per refill*: SNAPSYNC-16 recorded
  6.1–7.2 s per walk, 26 times in two hours. That requirement's *enqueue* still never depends on the walk,
  but the *fetch* is back on every completion. D6 removes the resource reads but not the fetch, and the
  SNAPSYNC-16 figure does not record which of the two dominated. → The device measurement in
  the tasks covers a backlog drain specifically. If the fetch alone is too slow there, the follow-up is a
  walk-less top-up: a completion cannot change the library, so a completion-triggered cycle can skip the
  walk entirely while other triggers still walk. That needs the cycle to know its trigger, which it does
  not today, so it is not built here.
- **[A restored photo re-uploads]** A photo deleted and restored within iOS's 30-day recovery window has lost
  its row, so the restore re-uploads under the same key. The backend re-stores the role idempotently and
  wakes nobody (`api/src/db.ts`, `eventsCompletedBy`). → Accepted: this is the stated cost of storing
  presence instead of remembering absence.
- **[A future predicate clause becomes destructive]** The fetch also excludes screenshots and screen
  recordings. If a later build adds a rule that the predicate can express and that excludes assets which
  already hold rows, those rows vanish from the walk while still in-window by row-admission, and D3 deletes
  them. → A KDoc at `predicateFor` states the constraint, and a cycle test pins that presence is the walk's
  candidate set. The loss itself is bounded: those rows are then excluded from the manifest by the policy
  anyway, and only re-upload suppression is lost, which matters only if the rule is later withdrawn.
- **[Old change-feed marks under a partial grant]** A row an earlier build marked absent from the change
  feed, on a device now under a partial grant, is un-marked by the sweep and can reappear in the manifest,
  because a scoped walk deletes nothing. → Accepted and rare: it needs a deletion and a later downgrade to
  a partial grant. Settled-row deletions under a partial grant are already undetected today.
- **[Adjusted capture dates]** A photo whose date the member changes in Photos keeps its old `creationDate`
  in its rows. If the new date leaves the window, the walk stops returning it while its stale date is still
  in-window, so D3 deletes the rows. That is correct: the photo is no longer in the event. Today it stays
  listed with a stale date.
- **[An in-flight row of a departed asset lingers]** A `REQUESTED` row outlives its asset until the job
  settles. → Accepted: one extra cycle, and it is excluded from the manifest only by the diff that follows.

## Migration Plan

- **Rollout:** one PR, labelled `enhancement` (deletion detection changes what other members see). The merge
  ships to internal TestFlight like any other.
- **Schema:** none. `Schema.version` is unchanged, so every earlier build still opens the database.
- **First launch:** the sweep clears existing marks, and the first authoritative walk deletes in-window
  departed rows before the manifest publishes.
- **Rollback:** revert the PR. The reverted binary sees no marks and every surviving row as present. It
  reads the stale `discovery.changeToken`, or falls back to a full enumeration. Rows this build deleted
  belonged to assets no longer in the library, so nothing needs re-deriving.

## Open Questions

- The app-driven hang recorded under "Measured on device" is unexplained and did not recur. Does it warrant a
  reproduction attempt (with someone at the phone for a sysdiagnose) before merging, or a tracked follow-up?
- The completion-driven walk measured ~80 ms on an idle SE2 (see "Measured on device"), so the walk-less
  top-up (see Risks) is not needed on that evidence. It stays the answer if an older device under load
  proves slow.
