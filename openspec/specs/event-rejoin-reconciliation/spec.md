# event-rejoin-reconciliation Specification

## Purpose

The extension-side gate that runs **before** a (re)joined device uploads anything: it pulls the event's
stored-file listing, seeds the ledger with one `COMPLETED` row per already-stored resource, and only then
enables the producer. A device that re-joins therefore re-uploads **nothing** it has already contributed.

Without it, a device rejoining against an empty ledger — after a delete-and-reinstall (the App Group is
wiped) or a destructive ledger-schema migration — re-enumerates the whole library and re-uploads every
photo that is already sitting in storage. The reconcile is gated on a persisted `joinedEventId` marker
rather than ledger-emptiness, because the extension's process is short-lived and per-cycle: a zero-row join
keyed on emptiness would never settle.

This reconcile is also what keeps ledger-sourced status honest. `sync-status` classifies from the ledger
under a no-deletion-during-an-active-event invariant, and (re)join is the sole point where the ledger and
storage can diverge — seeding closes it.

Decision record: `changes/archive/2026-06-27-add-rejoin-reconciliation`.

Generalized from **the extension** to **the upload tier** (both tiers, reconciling inside the shared
`UploadCycle`) in `changes/archive/2026-07-12-fix-app-driven-upload-lifecycle` — it previously bound only the
iOS ≥26.1 extension, so the app-driven tier shipped with no reconciliation at all.
## Requirements
### Requirement: Reconciliation gate before enabling uploads

The **upload tier** SHALL run a join reconciliation on **its own upload cycle**, before creating any
upload jobs, exactly when an event is configured and its `eventId` differs from a persisted
`joinedEventId` marker. The upload tier is whichever process holds the `LedgerWriter` — the extension
on iOS ≥26.1, the app on iOS 18–26.0. The `joinedEventId` marker — **not** ledger-emptiness — SHALL be the join
signal, persisted across the tier's processes. When the configured `eventId` equals the marker, the
tier SHALL NOT fetch, enumerate, or seed, and SHALL proceed to upload. When no event is configured,
the tier SHALL neither reconcile nor upload.

The reconciliation SHALL be driven from the **shared upload cycle** (`UploadCycle`, `:domain`
`feature/upload`), not from each tier's composition root, and the cycle SHALL require a reconciliation to
be supplied — a tier that supplies none SHALL NOT compile. Reconciliation is therefore reached on
**every** route to a divergent ledger: a fresh join, an event switch, a leave-then-rejoin, and a
delete-and-reinstall (which no provisioning path observes, because a cold relaunch of an
already-joined app performs no provision).

#### Scenario: Marker mismatch triggers a join

- **WHEN** the upload tier runs a cycle with an event configured whose `eventId` differs from the `joinedEventId` marker
- **THEN** a reconciliation runs before any upload job is created

#### Scenario: Marker match skips the join

- **WHEN** the configured `eventId` equals the `joinedEventId` marker
- **THEN** no fetch, enumeration, or seeding occurs and the producer uploads directly

#### Scenario: No event configured does nothing

- **WHEN** no event is configured
- **THEN** the tier neither reconciles nor uploads

#### Scenario: Both tiers reconcile

- **WHEN** a (re)join occurs on iOS 18–26.0 (the app-driven tier) or on iOS ≥26.1 (the OS-driven tier)
- **THEN** the same marker-gated reconciliation runs on that tier's cycle before any upload job is created

#### Scenario: A cycle reconciles without any provision having run in its process

- **WHEN** a membership exists (e.g. re-joined after a reinstall, or saved by the other process) and a cycle runs in a process where no provisioning path ever executed, with no `joinedEventId` marker and an empty ledger
- **THEN** that cycle reconciles against the per-device listing and seeds already-stored resources as `COMPLETED` so none re-upload — the reconciliation is cycle-resident, never provision-gated

### Requirement: Event file list seam

The system SHALL define a per-device file-listing seam whose `list(deviceId)` returns a `Result` of
the **storage keys the device has stored** — each key the bare `<assetId>-<role>.<ext>` — obtained from
the backend **per-device** listing (capability `api-endpoints`) over HTTPS. The source of seed truth is
the device's event-independent stored resources, not any single event. A settable/fake implementation
SHALL exist for tests; the iOS implementation SHALL use an HTTP client against the compile-time
device-facing host.

The backend answers in **identity terms** — `assetId`, `role`, and the resource's **capture filename** —
and mints no `url`. The seam SHALL therefore **recompose** each key through the shared `uploadKey`
builder, so the one definition of the storage layout stays in `model/` and the seam's callers keep
receiving keys. The recomposition is exact even when the capture name is unavailable or is itself a
storage key, because only the value's extension is consumed.

The response SHALL be decoded **strictly**: `assetId`, `role` and `filename` are all required, and `role`
SHALL be decoded as the closed `ResourceRole` vocabulary rather than as an opaque string. This is not
defensive typing. The previous listing shape also carried a field named `filename`, and it carried the
**storage key** where this one carries the **capture name** — so a lenient decode accepts either and
silently means the opposite, seeding capture names as ledger keys, leaving every real key unseeded, and
re-uploading the device's entire library on the next rejoin with no error anywhere.

Strict about the fields it NAMES, tolerant of the ones it does not: **unknown fields SHALL still be
ignored**, so a backend that adds one requires no client release. The two are not in tension and the
distinction is the whole design — requiring `assetId` and `role` is what makes a listing this build cannot
read fail loudly, while ignoring an added field is what stops a backend addition costing a device its
entire dedup set. A reader who takes "strict" to mean "rejects anything unexpected" has the rule backwards
in the direction that breaks working devices.

The seam SHALL surface failures as a failed `Result` (never a thrown error to the caller), so the join can
reduce them into state — and it SHALL **distinguish a decode failure from a transport failure**. A
transport failure is transient and is retried by deferring the cycle; a decode failure is permanent, will
not heal by retrying, and SHALL be reported at `Error` severity rather than absorbed into the same
deferral. Collapsing the two leaves a device deferring uploads forever behind a warning that reads exactly
like a slow network.

Reading the backend's record rather than the byte store means a resource the backend has not recorded is
not listed. That is the correct direction for this seam: seeding a row as `COMPLETED` for bytes the backend
cannot vouch for would suppress an upload that never happened.

#### Scenario: Successful listing returns the device's stored keys

- **WHEN** the backend returns the device's stored resources in identity terms
- **THEN** `list(deviceId)` yields a success `Result` carrying one recomposed `<assetId>-<role>.<ext>` key
  per stored resource

#### Scenario: A response missing identity is refused rather than misread

- **WHEN** the backend returns entries carrying a `filename` but no `assetId` or `role`
- **THEN** the decode fails and no key is produced, rather than seeding the `filename` values as keys

#### Scenario: A role outside the closed vocabulary is refused

- **WHEN** an entry names a role that is not a member of `ResourceRole`
- **THEN** the decode fails

#### Scenario: Transport failure yields a retryable failed Result

- **WHEN** the backend request fails with a network error, a non-2xx status, or a timeout
- **THEN** `list(deviceId)` yields a failed `Result` marked as transient, and does not throw to the caller

#### Scenario: A decode failure is permanent and is reported

- **WHEN** the response cannot be decoded into the expected shape
- **THEN** `list(deviceId)` yields a failed `Result` distinguishable from a transport failure, and the
  condition is reported at `Error` severity rather than only warned about once per cycle

#### Scenario: An unrecorded resource is not seeded

- **WHEN** the backend holds no record for a resource
- **THEN** the listing omits it, so the reconciler does not seed a `COMPLETED` row that would suppress a
  needed upload

### Requirement: Join reconciliation seeds already-stored photos as completed

A triggered reconciliation (in the extension) SHALL: fetch the **per-device** file listing
(`list(deviceId)`); **`resetTo`** (atomic clear-and-seed) the ledger to exactly one `COMPLETED` row
per stored resource, each keyed by the **recomposed** `<assetId>-<role>.<ext>` key, carrying the
`assetId` the listing reported and the **configured `eventId` as provenance** (`sync-ledger`, "Event
provenance and the backfill sweep" — the seed is this join's own write, so no seeded row is ever a
pre-provenance sentinel row); **clear the discovery cursor** to force a full re-enumeration; and **on
success set the `joinedEventId` marker** to the configured `eventId`. The seed records no timestamp.

Seeding from the listing's reported `assetId` rather than by re-parsing the key is the direction that
cannot drift: the backend now states identity, so the client has no reason to recover it from a string it
just composed.

The clear is essential: it drops stale/phantom rows — e.g. a `REQUESTED` row left by a prior cycle whose
upload job never materialized, which the engine would otherwise read as in-flight and skip re-creating
forever — leaving the ledger as exactly the device's stored files. Because the byte store is
device-global and event-independent, this clear-and-seed both **restores** dedup after a reinstall
(the seed repopulates every globally-stored resource as `COMPLETED`) and **preserves** it across an
event switch (the global listing re-seeds the same files `COMPLETED`, carrying the **new** event's
id — which is what keeps a switch's provenance truthful without any sweep). A resource that is not in the
device's byte store is absent from the listing, is not seeded, and is uploaded idempotently by the
producer (last-write-wins). Setting the marker on success — even when zero rows were seeded — settles
the join so it does not re-trigger.

A **confirmed-successful** listing SHALL be treated as **authoritative** — whether it reports every,
some, or **none** of the ledger's prior `COMPLETED` files. The `resetTo` seeds exactly the stored
files, so any file the listing omits (a subset deletion, or a full storage reset that returns an empty
listing) is not seeded and is re-uploaded by the producer. In particular, an **empty** listing while
the ledger still holds `COMPLETED` rows means the objects were **deleted from storage** and SHALL
re-baseline the ledger to empty (re-uploading everything), NOT defer: a successful empty listing cannot
be a stale/transient read, because (a) an upload confirms its bytes before the job succeeds (capability
`api-endpoints` never returns `2xx` for an unconfirmed upload), (b) the storage LIST reflects
writes and deletes immediately (read-after-write consistent), and (c) the list endpoint never returns a
`2xx` for a failed or partial listing (capability `api-endpoints`: a failure is `502`, surfaced
to the reconciliation as a fetch failure, not an empty array). An **untrustworthy** signal — a transport
error or a timeout — SHALL still defer (see "Extension defers uploads until the seed succeeds"), leaving
the ledger, cursor, and marker untouched so the next cycle retries. A **decode** failure SHALL defer
likewise, but SHALL NOT be reported as a transient condition: it will not heal by retrying, so it is
surfaced as the permanent fault it is. The ledger is thus reset only ever on an authoritative listing.

#### Scenario: A stored resource is seeded completed

- **WHEN** the per-device listing reports resources `(a1, primary, IMG_0001.JPG)` and `(a1, live, IMG_0001.MOV)`
- **THEN** the ledger holds a `COMPLETED` row keyed `a1-primary.jpg` and one keyed `a1-live.mov`, each
  carrying the reported `assetId` and the configured event's id as provenance, and the marker is set

#### Scenario: The reset drops stale/phantom rows

- **WHEN** the ledger holds a non-`COMPLETED` row (e.g. a `REQUESTED` row from a prior cycle whose job never materialized) for a resource absent from the per-device listing and a reconciliation seeds
- **THEN** the `resetTo` clears that stale row, so the ledger holds exactly one `COMPLETED` row per listed resource and nothing else

#### Scenario: Reinstall restores dedup from an empty ledger

- **WHEN** the ledger is empty (a reinstall) and the device's byte store already holds prior resources
- **THEN** the clear-and-seed sets a `COMPLETED` row for every stored resource, so the producer re-uploads none of them

#### Scenario: A zero-row join still settles

- **WHEN** the per-device listing returns no resources for a device with an empty byte store and no
  `COMPLETED` rows in the ledger
- **THEN** no rows are seeded but the `joinedEventId` marker is set, so the next cycle does not re-reconcile

#### Scenario: A storage reset (empty listing against a non-empty ledger) re-baselines and re-uploads

- **WHEN** the per-device listing returns **empty** (a genuine storage reset) but the ledger already
  holds `COMPLETED` rows
- **THEN** the reconciliation `resetTo`s the ledger to empty, clears the cursor, and sets the marker, so
  the producer re-uploads every resource — it does **not** defer (a successful empty listing is
  authoritative, never a transient)

#### Scenario: A partial storage deletion re-uploads only the missing files

- **WHEN** the per-device listing reports a strict subset of the ledger's prior `COMPLETED` files (some
  objects were deleted from storage)
- **THEN** the `resetTo` seeds only the still-stored files `COMPLETED`, and the producer re-uploads
  exactly the omitted (deleted) files, leaving the still-stored ones untouched

#### Scenario: A decode failure defers without pretending to be transient

- **WHEN** the listing cannot be decoded into the expected shape
- **THEN** the ledger, cursor and marker are untouched and uploads are deferred, and the condition is
  reported as permanent rather than logged as a retryable fetch failure

#### Scenario: A not-yet-stored resource re-uploads idempotently

- **WHEN** a resource is absent from the per-device listing (never uploaded)
- **THEN** it is not seeded and the producer uploads it (any already-present resource overwritten last-write-wins)

### Requirement: Seeded rows are skipped by the producer

The filenames seeded from the per-device listing SHALL be byte-identical to the keys the upload
producer derives for those resources — both the listing and the producer name a resource by the same
bare `<assetId>-<role>.<ext>` from the **shared gallery enumeration**, with no event scoping — so a
seeded `COMPLETED` row's key matches a later `ResourceChanged` for the same resource. Because an
uploaded resource is immutable, the engine treats any `COMPLETED` key as `AlreadyUploaded` regardless
of content, so the producer never re-uploads a seeded resource. Because the seed source is the
**device-global** listing, this skip now holds **across events**: a resource uploaded under one event
is re-seeded `COMPLETED` by the clear-and-seed and skipped after a switch to any other event, never
re-uploaded.

#### Scenario: Seeded resource is skipped by the producer

- **WHEN** the producer later enumerates a resource that the join seeded `COMPLETED`
- **THEN** the producer's decision is `AlreadyUploaded` and it creates no upload job

#### Scenario: Skip holds across an event switch

- **WHEN** a resource uploaded under one event is seeded `COMPLETED` and the device switches to a different event
- **THEN** the producer still decides `AlreadyUploaded` for that resource and re-uploads nothing already in the device byte store

### Requirement: Event switch versus re-join

The upload tier SHALL compare the configured `eventId` to the persisted `joinedEventId` marker. When
they **match** (a relaunch or re-provision of the already-joined event) the switch is a no-op: no
seed, no cursor clear, no re-projection, no marker write. When they **differ** — an event switch, a
reinstall with no marker, or a fresh provision — the tier SHALL **`resetTo`** (atomic clear-and-seed)
the ledger from the per-device listing; **clear the discovery cursor** to force a full re-enumeration;
**re-project** the device manifest (`device.json`) from the re-baselined ledger to the **new** event's
storage path; and set the `joinedEventId` marker to the configured `eventId`. The
clear-and-seed makes the ledger exactly the device's stored files — dropping stale/phantom rows —
while the device-global listing re-seeds the same files `COMPLETED`, so nothing already stored
re-uploads; the cursor clear re-enumerates to find genuinely-unstored work (the App-Group cursor
survives an app upgrade, so without it a re-join would scan incrementally and find nothing).

After a **leave** (config absent), **no** lifecycle path SHALL clear the ledger
(`upload-lifecycle`, "Upload producer seam has no destructive verb"). The tier SHALL clear
the `joinedEventId` marker **only**, on its next cycle, while **keeping** the ledger intact (it is
device-global and valid across events), so a subsequent provision of any event runs a fresh
reconciliation without losing dedup.

The property this defends is **dedup**: the ledger's `COMPLETED` rows are device-global and stay true
across a leave, a switch, and a re-join, so clearing them would re-upload every already-stored resource on
the next join (`sync-ledger`). The **discovery cursor is not dedup state** — a tier's `stop()` may clear it
as a repair for its own mechanism (`upload-lifecycle`), and this reconciliation clears it itself whenever
it re-baselines. Either way the cost is one full re-enumeration that finds nothing new, because the ledger
it did not touch still knows what is stored.

#### Scenario: Re-provision of an already-joined event is a no-op

- **WHEN** the configured `eventId` equals the `joinedEventId` marker
- **THEN** no seed, no cursor clear, no re-projection, and no marker write occur; the ledger and cursor are unchanged

#### Scenario: A different event resets-and-seeds and clears the cursor

- **WHEN** the configured `eventId` differs from the marker
- **THEN** the ledger is `resetTo` (clear-and-seed) from the per-device listing, the discovery cursor is cleared, `device.json` is re-projected from the re-baselined ledger to the new event path, and the marker is set — with the global listing re-seeding the same files `COMPLETED` so nothing already stored re-uploads

#### Scenario: A reinstall restores via the same clear-and-seed

- **WHEN** the marker is absent and the ledger is empty (a reinstall) for a configured event
- **THEN** the `resetTo` from the per-device listing restores the `COMPLETED` rows, the cursor is cleared, and the marker is set

#### Scenario: Leaving clears the marker but keeps dedup

- **WHEN** the user has left an event (config absent) and the upload tier next runs
- **THEN** the tier clears the `joinedEventId` marker **only** and keeps the ledger intact, so provisioning any event afterward runs a fresh reconciliation and re-uploads nothing already stored

#### Scenario: No lifecycle transition wipes the ledger

- **WHEN** a leave, an event switch, a re-provision, a permission change, or a direction change occurs on either tier
- **THEN** the ledger is never cleared by that transition; only a triggered reconciliation's `resetTo` ever re-baselines it

#### Scenario: A cleared cursor costs a re-enumeration, not a re-upload

- **WHEN** a transition leaves the discovery cursor cleared — by a tier's own `stop()` repair, or by this reconciliation's re-baseline
- **THEN** the next cycle enumerates the whole in-scope library and creates **no** upload job for anything already `COMPLETED`, because dedup lives in the ledger, not in the cursor

### Requirement: Upload tier defers uploads until the seed succeeds

When a reconciliation is triggered, the upload tier SHALL fetch and seed **before** creating any upload
jobs that cycle. If the listing fetch fails, the tier SHALL create no upload jobs that cycle and SHALL
leave the `joinedEventId` marker **unset**, so it retries on its next cycle. There SHALL be no
user-facing join-failure state and no re-scan-to-retry affordance — retries are the tier's own cadence,
and status meanwhile comes from the ledger read.

The device-listing fetch SHALL be bounded by an **explicit timeout** (`withTimeout`), mirroring the
device-manifest guard, so a hung network call cannot stall an OS-scheduled cycle (on the OS-driven
tier) or a `BGProcessingTask` window (on the app-driven tier). A timeout SHALL be treated **identically
to a failed fetch**: no rows are seeded, the ledger and cursor are left untouched, the `joinedEventId`
marker stays unset, and the next cycle retries. Only the network `LIST` is bounded — the subsequent
`resetTo(seeds)` remains a single atomic, un-timed transaction.

#### Scenario: The seed precedes any upload

- **WHEN** a reconciliation is triggered on a cycle, on either tier
- **THEN** the seed completes before any upload job is created that cycle

#### Scenario: A fetch failure defers without settling

- **WHEN** the listing fetch fails during a triggered reconciliation
- **THEN** no upload jobs are created, the `joinedEventId` marker stays unset, and the next cycle retries

#### Scenario: A listing timeout defers without settling

- **WHEN** the device-listing fetch does not return within its bounded timeout
- **THEN** it is treated exactly as a failed fetch — no seed, the ledger and cursor untouched, the marker unset — and the next cycle retries

### Requirement: Reinstall means the device left the event

A reinstalled device SHALL read **definitively not joined** — a reinstall is a leave. The config
file lives in the App Group and an App-Group container dies with the install, so a reinstalled
device runs the leave-side reconciliation, uploads nothing, and rejoins only by scanning the invite
again. Nothing besides the file SHALL be consulted to reach that conclusion.

This requirement previously recorded a two-stage migration, and records it now as history rather
than contract. **Stage 1** (migration step 11a through the finale) kept a read-only legacy-Keychain
fallback behind the file read: a definitively-missing file consulted the pre-11a item, resurrected a
found membership into the file, and only file-missing **and** item-absent read as a leave. The ship
model forced it — the migration branch reached `main` as ONE merge, so at update time the entire
joined installed base consisted of pre-11a devices whose config file had never existed, and deleting
the fallback in that same merge would have read every joined device as left: a silent, fleet-wide
logout. **Stage 2** was the designated post-ship change that deleted the fallback and retired the
config pair's runtime-identity pin; it has landed, and this requirement is its collapse. The
per-device migration it performed is complete and is not repeated: a device that never ran a
post-11a build before uninstalling is simply not joined.

Stage 2's gate was *"a production soak — every active joined device has executed at least one read
on a ≥13b build"*. It was discharged by **distribution**, not telemetry (there is none — SnapSync has
no accounts): the fallback shipped in `74d2b848` (step 11a, 2026-07-18) and the finale in `94f0bfe5`
(step 13b, 2026-07-19), **both ancestors of the `v0.1` tag**, and `v0.1` (2026-07-21) is the first
App Store release — so every public install of SnapSync, ever, has been a ≥13b build. The migrating
read also sat in `FileBackedConfigStore`'s constructor, which both composition roots build, so any
process start of such a build migrated the membership without the user opening the app. The residual
population was internal TestFlight installs predating 2026-07-18 that had started no process since;
they read as not joined and re-scan. Decision record:
`changes/archive/…-retire-legacy-config-fallback` D1.

**The absence classifier is now solely load-bearing for this decision.** While the fallback existed,
a *wrong* `Missing` — a read error misclassified into the not-found class — was caught: the fallback
found the legacy item, answered joined, and the device stayed joined. With the fallback gone there is
no second opinion, so `isConfigFileAbsence` (the `NSError` domain/code classifier in
`:adapter:ios:ext-safe`) is the only thing standing between a misclassified read failure and an
**uncaught logout** — marker cleared, ledger clear-and-seeded, discovery cursor reset, screen back on
the setup gate, with no error raised anywhere and nothing to undo it. Its whitelist SHALL therefore
stay closed (`else` answers "not absent"), and widening it SHALL be treated as changing the leave
decision itself, not as an error-handling detail (capability `event-link` states the same rule at the
seam).

No stronger reinstall detector (e.g. an install-scoped marker distinguishing reinstall from update)
SHALL be introduced: the App-Group file's own lifetime **is** the detector, and a second one could
only disagree with it (decision record: `changes/archive/migrate-config-to-app-group-file` D5;
`changes/archive/2026-07-19-complete-architecture-migration` D4 records the ship-at-once reasoning
that produced the staging).

#### Scenario: A reinstall reads as not joined and uploads nothing

- **WHEN** the app is deleted and reinstalled (App-Group ledger and config file wiped) — even on a
  device whose pre-11a legacy Keychain item survived the uninstall — and relaunched
- **THEN** the first cycle reads definitively-not-joined with nothing else consulted, runs the
  leave-side reconciliation, uploads nothing, and the device rejoins only by scanning the invite
  again

#### Scenario: A surviving legacy Keychain item resurrects nothing

- **WHEN** a read finds no config file on a device that still holds the legacy
  `app.snapsync.config`/`eventconfig` item from a pre-11a build
- **THEN** the item is not read, no membership is resurrected, and the read reports no config

#### Scenario: An update in place keeps the membership

- **WHEN** a joined device updates to a build carrying this change (its App-Group config file
  present, as any post-11a process start left it)
- **THEN** the read answers from the file, the membership survives, no leave-side reconciliation
  runs, and the `joinedEventId` marker stays intact

#### Scenario: An unreadable config is still not a leave

- **WHEN** a cycle's config read fails for any reason outside the not-found error class — notably a
  protected-file read before first unlock
- **THEN** the read reports unreadable, the cycle skips, the `joinedEventId` marker is left intact,
  and the next cycle retries; the loss of the fallback narrows what may read as absent, never widens
  it

### Requirement: Reconcile backfills the event window onto pre-existing memberships

A reconciliation SHALL backfill the event's **window and retention** fields onto a membership stored
**before** they existed — one that carries no `endsAt` or no `deletesAt`. When the configured
`EventConfig` lacks either of them, the upload tier SHALL fetch the event details
(`GET /events`) and, on a successful response, **backfill and persist** the membership with `endsAt` from
the fetched event and `deletesAt` from the fetched event's derived delete-by (capability
`api-endpoints`). Each field SHALL be filled only when **absent**, and both SHALL ride in a **single
whole-config save** so two rewrites cannot lose each other's field.

The membership's own capture-date **ceiling** (`maxPhotoDate`) is **not** among the backfilled fields: it
is required on every persisted membership (capability `join-event`), so a config that decoded at all
already carries a concrete ceiling and there is nothing absent to fill.

Legacy events (whose `endsAt` was the server-fixed `startsAt + 30d` backstop) are thereby capped at their
30-day mark — accepted: for a short-lived-event product a post-30-day capture is almost certainly not an
event photo.

Until a membership is backfilled — for example while the details fetch is unavailable — an **absent**
`endsAt` SHALL leave the "Event ended" marker (capability `sync-status-screen`) unreached, and an
**absent** `deletesAt` SHALL be treated as **never reached**, so the self-leave (capability
`leave-event`) cannot fire on a membership that has not yet learned its deadline. Both defaults fail
toward keeping data and keeping the membership.

A details fetch that returns **404** (the event is already gone) SHALL **skip** the backfill and leave the
membership's fields absent — there is nothing to backfill from a deleted event, and the membership
otherwise reconciles unchanged. Note that this is the reconcile path only: whether that same `404` tears
the membership down is the separate two-witness rule of capability `leave-event`, and a membership with
no backfilled `deletesAt` can never satisfy it.

The backfill SHALL write only the new window and retention fields onto the config; it SHALL NOT alter the
`eventId`, `name`, the capture-date range (`minPhotoDate`, `maxPhotoDate`), `direction`, or
`saveToAlbum`, and it is not a switch (no ledger reset, no cursor clear).

#### Scenario: A legacy membership is backfilled to the event end and its deadline
- **WHEN** a reconciliation runs for a membership stored before this change (no `endsAt`, no
  `deletesAt`) and `GET /events` returns the event with an `endsAt` and a `deletesAt`
- **THEN** the membership is persisted, in one save, with that `endsAt` and that `deletesAt`, its
  existing `maxPhotoDate` untouched

#### Scenario: A membership missing only the deadline is backfilled
- **WHEN** a reconciliation runs for a membership that already carries `endsAt` but no `deletesAt`, and
  `GET /events` succeeds
- **THEN** only `deletesAt` is filled, and the membership's `endsAt` and capture-date range are left
  unchanged

#### Scenario: Before backfill an absent deadline is never reached
- **WHEN** a membership missing `deletesAt` reconciles while `GET /events` is unavailable, so no backfill
  is written
- **THEN** the absent `deletesAt` is treated as never reached, so no self-leave can fire, and the next
  reconciliation retries the backfill

#### Scenario: A 404 skips the backfill
- **WHEN** a reconciliation runs for a membership missing the window fields and `GET /events` returns
  `404` (the event is already gone)
- **THEN** no backfill is written, the fields stay absent (the deadline stays unreached), and the
  reconciliation otherwise proceeds unchanged

### Requirement: Reconcile no longer backfills an absent ceiling

The reconcile path SHALL NOT carry an absent-ceiling backfill or an unbounded-until-backfilled allowance,
because the capture-date ceiling is now required on every persisted membership (capability `join-event`).
Any membership that reaches this change's build already carries a concrete ceiling (backfilled by
`decouple-event-window-from-lifetime` before this change deploys). The reconcile continues to refresh the
event name and other membership details unchanged; it simply has no absent ceiling to fill.

#### Scenario: No absent-ceiling branch remains

- **WHEN** the reconcile path is inspected
- **THEN** it contains no branch that treats an absent capture-date ceiling as unbounded or backfills one
