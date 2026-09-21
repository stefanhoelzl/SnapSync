## Context

This is phase 3 of a seven-phase rework of the upload path. Phases 0, 1, 2 and 6 have shipped. Phase 1
(`changes/archive/2026-09-21-always-full-enumerate`) made every walk a full enumeration, re-reads any asset
with a bare row, and deletes by presence diff only over an authoritative walk (`Discovery.fullEnumeration`).
Phase 6 added the event-album gather, which reads the ledger's manifest rows through the membership's
policy.

Today the ledger is device-global and never cleared (`sync-ledger`, "Lifecycle transitions never clear the
ledger"). Every upload cycle compares the configured event with a `joinedEventId` marker in App-Group
`NSUserDefaults`. On a mismatch, `UploadReconciler` fetches the per-device listing and `resetTo`s the ledger
from it. A failed fetch defers the cycle (`CycleOutcome.SeedDeferred`). The marker existed because the cycle
had to detect a membership change nobody announced, which the spec justified by a delete-and-reinstall "no
provisioning path observes". Since the config became an App-Group file, a reinstall is a leave
(`upload-state-reconciliation`, "Reinstall means the device left the event"). Every remaining change of
membership is therefore an explicit app action: a join, a switch or a leave.

`UploadLedgerAudit` is the read-only foreground check. It compares the ledger's landed rows with the
listing, and it reads the marker to skip a pending rejoin. It is the only reader of
`LedgerState.bytesBelievedStored`, which therefore goes with it (D7).

Two process facts constrain everything below:

- **The single record-writer.** On iOS ≥26.1 the upload extension is the ledger's only `LedgerWriter`; the
  app holds a `LedgerStore` for aggregates and the reset family (`clear`, `resetTo`, `demoteRequested`). On
  iOS 18–26.0, and on ≥26.1 under a partial grant, the app holds the writer. `sync-ledger` already allows a
  non-writer holder to reset the store.
- **`flow/` may not touch a port** (the flow-no-ports gate), and `Provision` transcribes into
  `architecture/flows/` under a closed grammar.

Measured (phase 1, SE2 / iOS 26.6): the per-device listing costs **~1.67 s for 1,433 rows**.

## Goals / Non-Goals

**Goals:**

- The upload ledger is the **current membership's share set**: empty while unjoined, loaded at a join,
  cleared at a leave and at a switch.
- Delete the marker, the in-cycle reconciliation and the audit, with nothing replacing the marker.
- A failed listing fetch blocks nothing.
- Every reader that decides something — including status — counts only what the membership admits.
- Own photos loaded at a join still reach the event album.

**Non-Goals:**

- Replacing `UploadArm` or moving photo permission into the cycle's entry gate (phase 4). This phase *calls*
  `UploadArm.onLeave()` / `onProvision()`; it does not change the arm.
- The stranded repair (phase 5).
- The download store. Handle-carrying rows stay permanent (`download-store`); nothing here clears them.
- Downward repair. The backend never deletes assets from an active event (`scheduled-cleanup` deletes whole
  events), so a `COMPLETED` row whose bytes vanished mid-membership has no cause. The listing is a dedup
  seed and nothing else.
- `FileLogWriter`'s silent give-up when its file cannot be opened (a known, unrelated observation).

## Decisions

### D1 — Every provision into a new membership clears, then loads

At a provision whose event differs from the joined one — a first join or a switch — the app **fetches the
per-device listing**. On success it calls `resetTo(listing)`: one bare `COMPLETED` row per stored resource,
in one atomic transaction. On failure or timeout it calls `clear()`. Either way the ledger holds nothing from
before the provision.

The design session had settled on a plain load (mark the listing `COMPLETED` over whatever is there), on the
argument that leftover rows are inert because every deciding reader applies the policy. That argument covers
**out-of-window** leftovers, but not a leftover `COMPLETED` row **inside** the new window. Such a row arises
on a device that left an event under the old contract (which kept the ledger), and whose bytes the sweep then
collected. It also arises after a leave whose best-effort clear failed. Either way it suppresses a needed
upload forever, with no error — the invisible failure the mission singles out. Clearing at the join closes
both cases with no extra concept. The user chose this over a one-time upgrade clear.

The seam is widened to hand back the `assetId` the backend reports beside each recomposed key, so the load
seeds each row's `assetId` from the backend's statement. `upload-state-reconciliation` already required
this, but `DeviceFilesSource.list` returned bare keys and the reconciler re-parsed them.

The listing fetch is bounded (15 s). The measured cost is 1.67 s at 1,433 rows, and uploads are bounded by
a window of at most 30 days, so the fetch is small. A transport failure is logged at `Warn`. A decode failure
(`DeviceListingShapeException`) is logged at `Error`, because it will not heal. That is the same split
`UploadReconciler` makes today, and `DeviceFilesSource` keeps it.

**Alternative rejected — keep the load in the cycle, gated on ledger-emptiness.** A cycle process is
short-lived, so an empty listing would never settle, and the cycle would refetch on every run. At the join it
runs once by construction.

**Alternative rejected — a durable "load owed" bit.** A failed load's only cost is re-uploading what the
backend already holds: idempotent overwrites of `(deviceId, assetId, role)`, bounded by the window. That cost
is small and visible, so it does not justify retry state that could strand a device behind a gate.

### D2 — Where the load lives, and when it runs

The rule lives in a new `feature/membership` use-case, `ShareSetLoad`, over the `LedgerStore` and
`DeviceFilesSource` ports. A second small use-case, `MembershipEntry`, owns the order of entering a new
membership — on a switch, stop the previous membership's uploads, then the backend leave; then the load.
`compose/` builds one `enterMembership: suspend (previousEventId: String?) -> Unit` effect over it and hands
that to `flow/Provision` (the flow cannot touch a port). Feature blindness holds: `membership` names only
ports, `model/` and injected lambdas.

`Provision`'s order becomes:

1. **Transition** — `switchDecision(active, next)` gains a third answer:
   - `Stay` — the same event is being re-provisioned, so nothing is torn down or loaded;
   - `Join` — no current membership;
   - `LeavePrevious(previous)` — a switch.

   `Join` and `LeavePrevious` share a sealed supertype, `SwitchDecision.Enter`, carrying `previousEventId`
   (`null` for a join), so the flow branches twice — `is Enter` and `Stay` — exactly as it did before this
   change. The flow tier's complexity ceilings (cyclomatic 4, cognitive 2, ten parameters) admit no more.
2. **Leave the previous membership** (a switch only): `MembershipEntry` **stops uploads**
   (`uploadArm.onLeave()`) and then fires the best-effort backend leave, awaited.
3. **Load** (every `Enter`, never `Stay`): `MembershipEntry` runs `ShareSetLoad` — fetch the per-device
   listing, then `resetTo(listing)` on success or `clear()` on failure or timeout (15 s). It never blocks the
   provision. Steps 2 and 3 are one flow call, `enterMembership(previousEventId)`.
4. **Save** the config.
5. Refresh status → `uploadArm.onProvision()` → ensure album → downloads + push (concurrent, awaited;
   unchanged).

The load runs **before** the save. That order is forced by the flow transcriber's closed grammar
(`architecture-diagrams`): a flow may `when` over a feature-returned sealed result whose branches are each a
single call, and holds no local variable except a leading guard. After the save, re-asking `switchDecision`
would always answer `Stay`, so a post-save load is not expressible without widening the grammar.

Load-before-save is also the safer order: no cycle (for example an app-driven pump running concurrently) can
ever see the **new** membership over the previous membership's ledger. A crash between the load and the save
leaves either (first join) an unjoined device holding a loaded ledger, which the next join clears anyway, or
(switch) the previous membership over a reloaded ledger, whose next walk simply re-records its work
(`DISCOVERED` rows are re-found by the walk; stored bytes are already `COMPLETED`). Neither loses a photo. The
load also runs **before** `onProvision`, so the first cycle the arm starts already sees the seed. On iOS ≥26.1
a first join has no registered extension until `onProvision`, so the load cannot race a cycle.

`Stay` must not load. `JoinEvent` short-circuits `AlreadyJoined`, but other routes reach `Provision` for the
joined event (a create routed into the join gate, the control channel's join). A `resetTo` there would drop
the `DISCOVERED`/`REQUESTED` rows of a live membership without stopping anything.

### D3 — A switch stops first; a leave stops, then clears

A switch is a leave followed by a join. D2 gives it the stop, and the join's clear-then-load gives it the
clear. `LeaveEvent`'s order becomes: **stop uploads → clear the upload ledger → clear config → notify the
backend (fire-and-forget)**. All four steps stay best-effort and independent.

Stop comes first so that no mechanism starts new work against rows about to vanish. Clearing the ledger comes
before clearing the config so that a device which has left never shows a stale share set. It is not needed
for correctness, because the next join clears anyway (D1).

**A completion that arrives after the clear** (a background `URLSession` task or PhotoKit job already in
flight) finds no row. `entryForDestination` answers nothing, the outcome is acknowledged and discarded, and
the bytes are on the backend with no row. The next join's listing includes them, so the load marks them
`COMPLETED`. If that load fails they re-upload idempotently. Nothing is lost and nothing loops.

**A cycle already running in the extension when the app clears** checked membership once at its start, so it
may still write rows after the clear. Whether deregistering the extension ends a cycle that is already
running is a device question the code cannot settle, and this design does not depend on the answer. The
leftovers it can leave are `DISCOVERED`/`REQUESTED` rows (a record write never records `COMPLETED`). The next
join's clear removes them. Until then they are outside any membership, so no deciding reader acts on them.

This puts an upload stop and a ledger-reset effect (both inside `enterMembership`) into a **provision**, which `upload-lifecycle` ("Upload
producer seam has no destructive verb") forbids today. The reversal is deliberate and is stated in that
capability's delta. The arm itself is untouched; phase 4 rewrites these transitions, and the effects here
are what it will rebind.

### D4 — The cycle loses its reconcile port and its seed deferral

`UploadCycle` drops the `reconcile` constructor parameter, the not-joined branch's `reconcile(null)`, the
phase-0 seed, and `CycleOutcome.SeedDeferred`. A contributing membership goes straight from the entry gate
to the direction gate. `uploadCore`/`UploadPorts` drop `deviceFiles` and `joinedMarker`. Both roots and
`UrlSessionUploadController` stop constructing an `IosJoinedEventMarker`.

`UploadRecordPorts` keeps `ledger` and `files` (the join load needs both) and drops `joinedMarker`. Its KDoc
claim that every use is a read becomes false, and is rewritten: the app uses the bundle for the aggregates
read and for the reset family at membership transitions.

### D5 — Status counts over the admitted set `N` counts

`aggregates()` counts every row. After D1 the ledger holds everything this device ever uploaded for any
event, and today the reconciler's whole-listing seed does the same. The classification caps `pending` at the
remainder, and the cap can mask pending in-window photos behind historical completions ("in sync" while
work remains).

**Decision:** `completed` and `pending` are counted over the **same asset set `N` counts**.
`OwnDeviceGalleryStatusSource.refresh` already materialises the admitted own-asset set, the one it counts
for `N`. It publishes that set (normalized `assetId`s) beside the count. The ledger gains a sibling read,
`assetProgress()`: one query answering, per `assetId`, whether every row of that asset is done. It is the
per-asset collapse `aggregates()` already performs, before counting. `aggregates()` itself is unchanged,
because it keeps two callers that are not status: the extension's "pending > 0 ⇒ PROCESSING" re-invocation
and the diagnostic dump. Status counts:

- `completed`: admitted assets whose rows are all done;
- `pending`: admitted assets with a not-done row.

An admitted asset with no row yet is neither, and stays in the remainder.

Why this over folding rows through `admittedAssetIds(rows, policy)`:

- **No policy derivation on the poll.** The counts poll ticks every 2 s while work is pending. Deriving the
  policy reads the download store and the album-exclusion reader, which touches PhotoKit. That would be a
  library read per tick, and the limited-access rules confine library reads to the cold-launch baseline and
  observer emissions.
- **Bare seeded rows count at once.** Row-based admission excludes a bare row (its empty date sorts before
  every cutoff), so for the first cycle after every join, status would under-count what is already stored.
  Anchored to the gallery's set, a seeded `COMPLETED` row counts as soon as `N` is counted.
- **`completed ≤ N` becomes structural**, not a clamp. The remainder cap stays as a guard. It no longer
  decides anything on a healthy device.

`assetProgress()` is **one** round-trip, so `completed` and `pending` stay mutually consistent
(`sync-status`). The fold is a set intersection in Kotlin. Its size is bounded by the admitted set, which is
the in-window library: hundreds of assets, not the whole ledger.

Until `N` has been counted, the admitted set is unknown, and status keeps its existing un-counted behaviour
(`gallery-status`: not counted is not zero).

### D6 — Album: place when a bare row is healed

On iOS ≥26.1 the walk that dates seeded rows runs in the extension, so the app cannot order the gather after
it. Seeding only walked assets is not possible from the app either, because the app does not walk there.

**Decision:** in the decide stage, the cycle already computes which admitted assets have a bare row (the
`fullyKnown` complement it reads). For a `saveToAlbum` membership, the update stage places those assets —
the admitted ones whose bare row this walk is filling — in one best-effort add, issued **before** the
backfill writes their detail, in whichever process runs the cycle. The order matters for the same reason
enqueue-time placement precedes job creation. Once its detail is written a row is no longer bare, so a
process death between filling it and placing it would leave the photo unplaced by this path until some
later opt-in act runs a gather. Placed first, a death before the backfill leaves the row bare, and the next
walk places it again as a harmless no-op. The extension already builds an `AlbumCoordinator`. A repeat add is a
measured no-op, so a later gather re-adding them is harmless.

This is a **second** own-photo placement moment beside first enqueue. `event-album`'s gather scenario "a
carried-over photo is gathered at join" changes meaning: a carried-over photo now arrives as a loaded bare
row and is placed when healed, not by the provision gather.

### D7 — Delete `UploadLedgerAudit`

It reads the marker this phase deletes. It has also been subtly wrong since phase 1: its "unlisted" count
includes assets whose rows the walk deleted because they left the library, and its `Info` line says "the next
walk re-uploads them", which is false for those. The detector's question — does the backend lose rows the
ledger records as landed? — is not re-homed here. `UploadForeground.check` goes with it, and
`UploadForeground` collapses to the pump. `LedgerState.bytesBelievedStored` loses its only reader and is
deleted too; an axis nothing reads is a classification that can only drift, and `sync-ledger` records that a
future backend comparison re-introduces it as its own decision.

### D8 — The orphaned marker key is removed once

Every existing device holds `rejoin.joinedEventId` in App-Group `NSUserDefaults`. The app removes the key on
process start. `removeObjectForKey` is idempotent and costs nothing when the key is absent, so "once" needs
no bookkeeping. The removal lives beside the other App-Group constants in `:adapter:ios:ext-safe`. The literal **stays
pinned** in `RuntimeIdentityTest`, exactly once, at the removal site. A drifted literal would make the
removal a silent no-op (`removeObjectForKey` on a misspelled key does nothing, and no test, log or device
notices), and the rollback protection below would quietly be gone. The pin is what fails that build.

The reason is rollback, not tidiness (see Migration Plan).

## Risks / Trade-offs

- **[A failed join load re-uploads the stored set]** → Bounded by the event window and idempotent at the same
  destinations. The load is logged at `Warn` (transport) or `Error` (decode). No gate means no stuck device.
- **[A switch now stops in-flight uploads]** → Before this change a switch deliberately left transfers alone.
  Stopping costs a re-upload of what was in flight (on ≥26.1, deregistering drops the OS's jobs). This is
  bounded, and the next join's listing reconciles whatever did land.
- **[A switch or join now takes the listing round-trip]** → ~1.7 s at the largest measured device, bounded at
  15 s, and awaited inside the provision. The join surface already waits on the details fetch and the
  enrollment PUT.
- **[Leftover rows from an extension cycle running at leave]** → They are cleared at the next join (D1), and
  no deciding reader acts on them in between. They also do not reach status (D5), because status counts only
  admitted assets.
- **[Deeper single-membership assumption]** → Named in the proposal against the named future of concurrent
  multi-event membership.
- **[Status needs `N` before it can count]** → This was already true of the classification, which needs `N`.
  The new dependency is that `completed`/`pending` are also `null`-until-counted rather than a
  whole-ledger number. That is the same "not counted is not zero" rule `gallery-status` already enforces.

## Migration Plan

- **No schema change, no backend change.** The listing route exists and is unchanged.
- **No staged ship.** An already-joined device updates with its ledger intact and correct. The cycle simply
  stops consulting the marker; nothing gates it. The ledger becomes "the current share set" at that device's
  next leave or switch.
- **A device unjoined at update time** may still hold a retained ledger from the old contract. D1's
  join-time clear removes it before anything reads it as a membership's share set.
- **Rollback (revert this change).** The old build's cycle compares the configured event with the marker.
  Because D8 removed the key, the old build finds no marker. It runs its reconciler once and `resetTo`s from
  the listing. That is correct whatever this build left behind: an empty ledger after a leave, or a loaded one
  after a join. Had the key been left in place, a device that left and rejoined the same event under this
  build would read a matching marker, skip the seed, and re-upload its whole window. That is the case D8
  exists for. With no schema involved, the revert is otherwise a plain code revert.

## Open Questions

- Whether deregistering the OS-driven extension ends a cycle already running in it. This design does not
  depend on the answer (D3), and it is left as a device measurement, not settled here.
