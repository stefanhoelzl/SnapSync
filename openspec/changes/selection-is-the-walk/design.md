## Context

This is phase 7 of the upload-path concept audit. Phase 5 (`changes/archive/2026-09-22-both-uploaders-active`,
D1–D11) stands unchanged: both uploaders are active, every write happens after the act, the extension
creates only under `GRANTED`, and nothing is cancelled except at a leave. Phase 8 ("manifest versions") runs
in parallel and is out of scope.

What the tree does today, as it bears on this change:

- **Scope derivation.** `model/SelectionScope.kt`: `selectionScope(permission, snapshot)` returns
  `Scoped(snapshot ?: emptyList())` under `LIMITED`. An unread snapshot (`null`, the value
  `AppCore.latestSelectionSnapshot` holds until the first read) is therefore indistinguishable from an
  empty selection. `PermissionAwareCandidateSource` already keeps the two apart for the status total and
  explains why (`SNAPSYNC-14`/`-16`). Its KDoc then claims the collapse on the upload side is safe because
  "a scoped discovery … deletes nothing". That claim is false (next point).
- **Deletion under `LIMITED` already happens, on one path.** `SelectionScopedDiscovery.discover` hard-codes
  `fullEnumeration = false`, so the walk's presence diff never runs. But `UploadCycle.createChunk` resolves
  each admitted `DISCOVERED` row through `SelectionScopedDiscovery.resourcesFor`. Under `Scoped` that answers
  from the snapshot, and a key it lacks is deleted as "its asset is gone". So de-selected `DISCOVERED` rows
  are deleted today, while `COMPLETED` and `REQUESTED` rows survive. With an unread snapshot (`Scoped(empty)`)
  **every** admitted `DISCOVERED` row is deleted. The cycle has no short-circuit on zero candidates, and
  nothing orders the first upload cycle after the first selection read.
- **The in-flight exemption.** `UploadCycle.departedKeys` filters out `REQUESTED` rows.
- **Failure paths.** `acknowledgePresented` and `recreateRetrySpent` skip a job only when its row is *done*;
  a job with **no** row falls through to `adjudicateFailure`. That calls `engine.handle(UploadFailed)`, which
  records the key back to `DISCOVERED` through an upsert that is guarded only against done rows. The row
  comes back **bare**, with its `assetId` parsed from the key and no `creationDate`. The `fetchRetryJobs`
  loop has no skip at all: it adjudicates, calls `retryJob`, and records `REQUESTED` again.
- **PhotoKit adapter.** `IosPhotoKitUploadPlatform.resolveKey` finds a job's key only through its row
  (`entryForDestination`), falling back to the v1 last path segment. A v2 job whose row was pruned is
  counted as "unrecoverable" and logged at `Error` (it reaches Bugsink). It is still acknowledged. On the
  drain path, a retry-spent failure is emitted to the cycle for re-creation whether or not its row exists.
- **URLSession adapter.** `recordTerminal` → `markTerminal` → a `Warn` line when the write applies to no
  row. `drainTerminals` and `fetchRetryJobs` return nothing, so no failure from this tier reaches the cycle.
- **Listing.** `HttpDeviceFilesSource.list(deviceId)` returns `StoredResource(key, assetId)`, and
  `ShareSetLoad` uses it at a join. `upload-state-reconciliation` forbids the cycle from fetching it.
- **Enqueue.** `RESOLVE_CHUNK = 4`; `enqueue` → `eligible.chunked(4)` → `createChunk`.

Measurements this design rests on (SE2, iOS 26.6, 2026-09-22, rig build of phase 5; handed over, not
re-derived):

- Under a full grant, the extension's bytes landed 1–4 s after `createJob`. The acknowledgement arrives
  only at the extension's next invocation.
- Downgrade: 4 uploads were queued offline under `GRANTED`, then access narrowed to `LIMITED` (2 of the 4
  inside the selection), then the network returned. All 4 objects landed within ~30 s. A forced extension
  cycle was presented 0 jobs. The rows stayed `REQUESTED` and the status read `Syncing` indefinitely.
- `resourcesFor` costs ~4.5 ms per request + ~3.45 ms per photo. For 100 photos: batch 1 took 0.80–0.94 s,
  batch 4 0.46 s, batch 16 0.37 s.

## Goals / Non-Goals

**Goals:**

- Under `LIMITED` the selection is the gallery. What it holds may be shared (subject to the policy), and
  what leaves it leaves the event's manifest in the same cycle that sees it leave.
- Removal applies to in-flight rows too, on every authoritative walk.
- An unread selection never reads as an empty one anywhere on the upload path.
- No settle or retry path can bring back a row the walk removed.
- A `REQUESTED` row whose bytes the backend already stores settles at the next foreground, whether or not
  iOS ever acknowledges the job.
- Enqueue resolves one row at a time.

**Non-Goals:**

- Manifest versioning or ordering between the two publishing processes (phase 8).
- Deleting any stored byte. A removed photo's bytes stay until the nightly sweep finds them unreferenced
  (capability `scheduled-cleanup`).
- Retracting a photo that other members already imported. Their copies are in their own libraries.
- A pull-to-refresh gesture, or any new UI.
- Running the backend settle in the extension.
- Anything in phase 5's design beyond these items.

## Decisions

### D1 — A read selection snapshot is an authoritative walk; an unread one is a distinct state

`SelectionScope` gains `Unread`. `selectionScope(LIMITED, null)` returns `Unread`, and
`selectionScope(LIMITED, list)` returns `Scoped(list)`. `SelectionScopedDiscovery` answers:

- `Scoped` → `Discovery(candidatesFromResources(snapshot), fullEnumeration = true)`. Presence is the
  snapshot's whole candidate set, taken before admission, like a library walk. The snapshot takes no
  predicate, so no capture-window narrowing is needed for presence to be complete.
- `Unread` → it **throws** from both `discover` and `resourcesFor`. It never answers with an empty list.
  An empty answer from `resourcesFor` means "gone" to the enqueue, so any answer here deletes rows, while
  a throw only fails one cycle.

The throw is a backstop, not the mechanism. The mechanism is the entry gate. `appAdmission` takes the
scope, or equivalently whether the snapshot has been read, and answers `Withheld` under `LIMITED` while the
snapshot is unread. The cycle then settles narrowly (it records and acknowledges presented jobs), reads
nothing, creates nothing and publishes nothing, exactly as it does without usable access. Both the scope and
the admission read the one `latestSelectionSnapshot` cell. It only ever goes from `null` to a list within a
process (a grant change in Settings terminates the app), so admission and discovery cannot disagree within a
cycle.

*Alternatives.* (a) Keep `Scoped(empty)` and make only `discover` non-authoritative when unread. Rejected:
the enqueue's resolve still deletes every `DISCOVERED` row, which is today's bug. (b) Wait inside the cycle
for the first snapshot (`filterNotNull().first()`, as the status refresh does). Rejected: a cycle that
blocks on an observer can hold the single-flight pump indefinitely. Withholding costs one idle cycle, and
the observer's first emission triggers the next one. (c) Make "selection read" a new gate outcome.
Rejected: it is exactly `Withheld`'s contract, so a new outcome would restate it.

The `sync-ledger` rule "never pruned by the selection policy" still holds. The *selection* is **presence**
(which photos exist, from the app's point of view), not *policy* (which existing photos are admitted).
A raised cutoff, a direction turned off, or an origin exclusion still removes nothing.

### D2 — An authoritative walk deletes in-flight rows too

`departedKeys` drops the `state != REQUESTED` filter. Both kinds of authoritative walk (library and read
snapshot) remove an absent asset's in-window rows whatever their state. The job keeps running. Its guarded
terminal write (`markTerminal`, which applies only to a `REQUESTED` row) then finds no row and applies to
nothing. The bytes land and are listed in no manifest.

This also covers the full-library case: a photo deleted from the library mid-upload now leaves the
manifest immediately instead of after its job settles. That matches the user's intent ("the images are
uploaded, but they should not end up in the manifest"), and it is safe because of D3.

*Alternative.* Keep the exemption and have the terminal write delete the row. Rejected: that needs a
second guarded write keyed on "no longer present", and presence is a fact only the walk has.

### D3 — A presented job whose row is gone is answered and forgotten

The rule: **no settle or retry path may write a row for a key the ledger does not hold.** It is enforced in
two places.

1. **The PhotoKit adapter** is the only transport that hands jobs to the cycle. It confirms the row before
   emitting a job: the destination-path route already requires one, and the v1 last-segment fallback is
   emitted only if a row exists for that key (a key read on `TransferRecord`, which already carries the
   destination read; a read, not a record operation). A job with no row is **pruned**, not unrecoverable.
   In both the `.retry` and the `.acknowledge` set it is acknowledged in place, nothing is written, it is
   not emitted, and it is logged at `Info` ("terminal for a pruned row"). `Error`-severity "unrecoverable"
   stays for a destination the classifier cannot map at all, which remains a real fault. Without this,
   every de-selection or library deletion under a full grant would raise a Bugsink event.
2. **The cycle** skips any row-less job it is handed on every path — `acknowledgePresented`,
   `recreateRetrySpent`, and the `fetchRetryJobs` loop — before `adjudicateFailure`: no engine event, no
   `retryJob`, no `createJob`. For PhotoKit this is a second guard behind (1). For the world's fakes, and
   for any future transport, it is the only guard. `adjudicateFailure` keeps its blank-key guard.

A row-less job on the `.retry` path is therefore acknowledged by the adapter and never reaches the loop,
which is why the loop may simply skip one. If a transport ever did hand one over, skipping it would leave
it un-acknowledged. The adapter's rule is what prevents that, and a test pins it.

The URLSession tier is unchanged: nothing from it reaches the cycle. Its "applied to NO row" line moves
from `Warn` to `Info`, because a pruned row is now a routine outcome. (`Warn` is only a breadcrumb, so this
changes log noise, not reporting.)

### D4 — The foreground settles in-flight rows from the per-device listing

A new use-case, `feature/upload` `StoredUploadSettle` (working name), runs over the `DeviceFilesSource`,
`LedgerStore` and device-id ports:

1. read the pending rows (`pendingResources()`). If none exist, stop: no request is made;
2. `list(deviceId)`, bounded like the join-time load (15 s). On failure or timeout, log at `Warn`, or at
   `Error` for a `DeviceListingShapeException`, and stop. There is no retry and no state;
3. for each pending key the listing contains, call `markTerminal(key, TerminalOutcome.COMPLETED)`.

The guard does the narrowing. `markTerminal` applies only to a `REQUESTED` row, so a listed `DISCOVERED`
row is untouched, as is a row that settled meanwhile. A later OS acknowledgement then finds a settled row
and does nothing. No new ledger operation is added. `markTerminal` already dings `changes`, so the status
poller and read-models pick up the settle.

**Where it runs.** It is one more launch in `flow/Foreground`, next to `pumpUploads`, reached as a
`compose/`-built effect (flow-no-ports), in the app process only. It is **not** sequenced behind the pump.
The pump awaits a whole cycle, measured at up to 774 s after a long suspension (`SNAPSYNC-16`), and this
settle exists precisely to correct the status on return. The handoff said the settle runs "on the app's
writer lane". What that phrase is meant to protect holds without serialization, because the settle writes
only through the guarded `markTerminal`. That is the same non-writer write the URLSession delegate already
makes concurrently with a cycle. It sits inside the cycle's decide/update licence ("the platform's own
delegate reaches storage only through the guarded `markTerminal`"). Interleaving it with D2's deletion is
harmless in either order. It does not run in the extension: under `GRANTED` the extension is acknowledged
directly, and under `LIMITED` it is withheld and would be presented nothing to reconcile anyway.

**It never marks a photo done without stored bytes.** A listing entry exists only after a completed `PUT`:
the backend confirms bytes before the job succeeds, and the listing is read-after-write consistent
(capability `upload-state-reconciliation`, the join-time load's authority argument). A listed key whose
bytes came from an *earlier* upload of the same key is still correctly `COMPLETED`: keys are
`(assetId, role)` of the original, and the object is identical.

*Alternatives.* (a) Inside the cycle. Rejected: `upload-state-reconciliation` keeps "the upload cycle
SHALL NOT fetch the per-device listing", and the cycle runs in both processes. (b) Also on a silent push or
after every cycle. Rejected for now: foreground is when the member looks at the status, and each extra
trigger is another request. Revisit if a stuck `REQUESTED` row is observed on a device that is not being
looked at.

### D5 — Batch size 1

`RESOLVE_CHUNK`, `chunked` and `createChunk`'s slice are deleted. For each admitted row the cycle places
it, resolves it (`resourcesFor(setOf(key))`) and creates its job, stopping the pass at the first
`LIMIT_EXCEEDED`. Album placement moves with it to one row at a time, so it still happens before the row's
job is created (`event-album`). At 0.80–0.94 s per 100 photos against 0.46 s at batch 4, it costs a few
hundred ms per hundred photos and only under `GRANTED`. The user chose it for simplicity. Nothing is wasted
on a refusal: the pass stops before resolving the next row.

### D6 — Capabilities left unchanged, and why

- `sync-status`: `N` under `LIMITED` is already the selection-scoped count, and progress is the per-asset
  read intersected with it. D4 is what un-sticks the measured `Syncing` state. No requirement changes.
- `photo-selection-policy`: the selection is not a policy rule (D1). The policy still filters the
  selection unchanged.
- `sync-engine`: failure adjudication is unchanged. The skip in D3 sits before the engine is consulted.
- `event-album`: placement semantics are unchanged (D5 changes only the granularity).
- `photo-download`: echo suppression keys on downloaded local ids, which never become own rows, so a
  de-selection or a re-selection changes nothing there.

## Risks / Trade-offs

- **[A mistaken de-selection withdraws a photo from the event]** → This is intended (the user's decision).
  Re-selecting lists it again and re-uploads the same object idempotently. Copies other members already
  imported are unaffected.
- **[`LIMITED → GRANTED` re-uploads every previously de-selected photo]** → Accepted. The first full walk
  records them `DISCOVERED` and re-`PUT`s identical objects. D4 then settles any that are still `REQUESTED`
  on the next foreground. Nothing else keys on the removed rows (echo suppression and the album gather are
  unaffected).
- **[A removed in-flight photo's bytes are stored but unlisted]** → The bytes are referenced by no manifest
  and reclaimed by the nightly sweep after its retention floor (capability `scheduled-cleanup`).
- **[The selection read never arrives (an observer failure)]** → The app's cycle stays withheld under
  `LIMITED`. Today it would run against an empty scope and upload nothing either, so this is no regression,
  and it no longer deletes rows while it waits.
- **[The settle and a cycle interleave]** → Both write only through statements whose guard is in the
  statement (`markTerminal` / `deleteKeys`). Every order leaves a correct row or no row.
- **[A `.retry` job skipped by the cycle stays un-acknowledged (error 50008)]** → Only a transport that
  emits a row-less job could cause it. D3(1) forbids that for PhotoKit, and an adapter test pins "a pruned
  row is acknowledged in place and not emitted".
- **[Batch 1 is slower under a full grant]** → Measured at a few hundred ms per 100 photos; accepted.

## Migration Plan

There is no schema, backend or config change, so shipping is an ordinary build. On first launch of the new
build under `LIMITED`, the first cycle after the selection read deletes the rows of every photo outside the
selection, including `COMPLETED` rows kept by the old rule. That is the intended retraction, published in
that cycle's manifest.

**Rollback** is a revert of the build. The old build finds a ledger that is only smaller, with no new state
or column. Under `LIMITED` it does not bring the deleted rows back (it never walks the library there).
Under `GRANTED` it rediscovers them and re-uploads the same objects idempotently. Retractions already
published stay retracted until then. Nothing strands a device.

## Open Questions

- D4 runs the settle beside the pump rather than after it. The handoff said "on the app's writer lane"; I
  read that as protecting the write discipline, which the guarded write already provides. If the intent was
  literal serialization, the settle can queue behind the pump at the cost of the 774 s latency above.
- Whether the v1 last-segment fallback in the PhotoKit adapter still has any job to serve is not measured.
  This change keeps it and only adds the row check.
