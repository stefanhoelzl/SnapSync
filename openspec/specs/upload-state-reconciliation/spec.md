# upload-state-reconciliation Specification

## Purpose

Ask the backend which resources it already holds **for this device**, and make the local record agree.
That is the whole operation; everything else here is when it runs and what it is allowed to conclude.

It runs at **two** moments. **At the join**, a provision into a new membership — a first join or a switch,
never a re-provision of the joined event — pulls the **device's** stored-file listing and makes the upload
ledger exactly that set, one `COMPLETED` row per already-stored resource. **At every foreground**, the app
asks the same listing which of its in-flight (`REQUESTED`) rows already have their bytes stored, and settles
those through the guarded terminal write. The OS's acknowledgement of an upload can lag its bytes, or never
arrive at all (decision record `changes/archive/2026-09-22-selection-is-the-walk`, D4). A device therefore re-uploads **nothing**
it has already contributed, whichever event it contributed it for: the listing is per-**device** and
event-independent. A listing that cannot be fetched leaves the ledger empty instead and blocks nothing; its
only cost is re-uploading what the backend already holds, idempotently and bounded by the event window.

There is no detection any more. The upload cycle used to compare the configured event with a persisted
`joinedEventId` marker on every run, because a delete-and-reinstall was a membership change no provisioning
path observed. Since the config became an App-Group file, a reinstall is a leave, so every change of
membership is an explicit app action — and the join, which already knows it is one, does the work. The
marker, the in-cycle reconciliation, its seed deferral and the read-only foreground ledger check were
removed in `changes/archive/2026-09-21-join-loads-leave-clears`.

This capability was named `event-rejoin-reconciliation` until
`changes/archive/2026-09-09-name-the-repair-not-the-trigger`, after the one occasion it originally had.
Decision records under `changes/archive/` cite that former name and are deliberately left as written.

Decision records: `changes/archive/2026-06-27-add-rejoin-reconciliation` (the seed),
`changes/archive/2026-07-12-fix-app-driven-upload-lifecycle` (both tiers), and
`changes/archive/2026-09-21-join-loads-leave-clears` (the load moves to the join), and
`changes/archive/2026-09-22-selection-is-the-walk` (the foreground settle of in-flight rows).
## Requirements
### Requirement: Event file list seam

The system SHALL define a per-device file-listing seam whose `list(deviceId)` returns a `Result` of
the **resources the device has stored** — each carrying its storage key, the bare
`<assetId>-<role>.<ext>`, **and the `assetId` the backend reported** — obtained from
the backend **per-device** listing (capability `api-endpoints`) over HTTPS. The source of seed truth is
the device's event-independent stored resources, not any single event. A settable/fake implementation
SHALL exist for tests; the iOS implementation SHALL use an HTTP client against the compile-time
device-facing host.

The backend answers in **identity terms** — `assetId`, `role`, and the resource's **capture filename** —
and mints no `url`. The seam SHALL therefore **recompose** each key through the shared `uploadKey`
builder, so the one definition of the storage layout stays in `model/` and the seam's callers keep
receiving keys. It SHALL also hand back the reported `assetId` beside each key, so its caller seeds the
ledger's `assetId` from the backend's statement rather than by parsing the key it was just given. (Before
this change the seam returned bare keys and the reconciler re-parsed each one — the drift this sentence
closes.) The recomposition is exact even when the capture name is unavailable or is itself a
storage key, because only the value's extension is consumed.

The response SHALL be decoded **strictly**: `assetId`, `role` and `filename` are all required, and `role`
SHALL be decoded as the closed `ResourceRole` vocabulary rather than as an opaque string. This is not
defensive typing. The previous listing shape also carried a field named `filename`, and it carried the
**storage key** where this one carries the **capture name** — so a lenient decode accepts either and
silently means the opposite, seeding capture names as ledger keys, leaving every real key unseeded, and
re-uploading the device's entire library on the next join with no error anywhere.

Strict about the fields it NAMES, tolerant of the ones it does not: **unknown fields SHALL still be
ignored**, so a backend that adds one requires no client release. The two are not in tension and the
distinction is the whole design — requiring `assetId` and `role` is what makes a listing this build cannot
read fail loudly, while ignoring an added field is what stops a backend addition costing a device its
entire dedup set. A reader who takes "strict" to mean "rejects anything unexpected" has the rule backwards
in the direction that breaks working devices.

The seam SHALL surface failures as a failed `Result` (never a thrown error to the caller), so the join-time
load (see "A join loads the ledger from the per-device listing") can reduce them into state — and it SHALL
**distinguish a decode failure from a transport failure**. A transport failure is transient and is reported
at `Warn`; a decode failure is permanent, will not heal on a later join, and SHALL be reported at `Error`
severity rather than absorbed into the same warning. Both reach the same outcome at the join — the load
clears instead of seeding — but collapsing their reports leaves a device silently re-uploading its whole
stored set on every join behind a warning that reads exactly like a slow network.

Reading the backend's record rather than the byte store means a resource the backend has not recorded is
not listed. That is the correct direction for this seam: seeding a row as `COMPLETED` for bytes the backend
cannot vouch for would suppress an upload that never happened.

#### Scenario: Successful listing returns the device's stored keys

- **WHEN** the backend returns the device's stored resources in identity terms
- **THEN** `list(deviceId)` yields a success `Result` carrying, per stored resource, one recomposed
  `<assetId>-<role>.<ext>` key and the `assetId` the backend reported for it

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
  condition is reported at `Error` severity rather than only warned about

#### Scenario: An unrecorded resource is not seeded

- **WHEN** the backend holds no record for a resource
- **THEN** the listing omits it, so the join-time load does not seed a `COMPLETED` row that would suppress
  a needed upload

### Requirement: Seeded rows are skipped by the producer

The filenames seeded from the per-device listing SHALL be byte-identical to the keys the upload
producer derives for those resources — both the listing and the producer name a resource by the same
bare `<assetId>-<role>.<ext>` from the **shared gallery enumeration**, with no event scoping — so a
seeded `COMPLETED` row's key matches a later `ResourceChanged` for the same resource. Because an
uploaded resource is immutable, the engine treats any `COMPLETED` key as `AlreadyUploaded` regardless
of content, so the producer never re-uploads a seeded resource. Because the seed source is the
**per-device, event-independent** listing, this skip holds **across events**: a resource uploaded under one
event is seeded `COMPLETED` again by the join-time load of any later membership — after a switch, or after
a leave and a later join — and skipped there, never re-uploaded. The leave's clear costs no dedup, because
the next join's load restores every stored resource from the backend's record.

#### Scenario: Seeded resource is skipped by the producer

- **WHEN** the producer later enumerates a resource that the join seeded `COMPLETED`
- **THEN** the producer's decision is `AlreadyUploaded` and it creates no upload job

#### Scenario: Skip holds across an event switch

- **WHEN** a resource uploaded under one event is stored on the backend and the device switches to a different event, whose join-time load succeeds
- **THEN** the load seeds that resource `COMPLETED`, the producer decides `AlreadyUploaded` for it, and nothing already in the device byte store re-uploads

#### Scenario: Skip holds across a leave and a later join

- **WHEN** a device leaves an event (its ledger cleared) and later joins another, whose join-time load succeeds
- **THEN** every resource the backend holds for the device is seeded `COMPLETED` again and none of them re-uploads

### Requirement: Reinstall means the device left the event

A reinstalled device SHALL read **definitively not joined** — a reinstall is a leave. The config
file lives in the App Group and an App-Group container dies with the install — the upload ledger with
it — so a reinstalled device holds no membership and no ledger, uploads nothing, and rejoins only by
scanning the invite again, whose join loads the ledger from the per-device listing (see "A join loads the
ledger from the per-device listing"). Nothing besides the file SHALL be consulted to reach that conclusion.

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
**uncaught logout** — uploads stopped, screen back on the setup gate, and a re-scan that
re-provisions as a fresh join, with no error raised anywhere and nothing to undo it. Its whitelist SHALL therefore
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
- **THEN** the first cycle reads definitively-not-joined with nothing else consulted, uploads
  nothing, and the device rejoins only by scanning the invite again

#### Scenario: A surviving legacy Keychain item resurrects nothing

- **WHEN** a read finds no config file on a device that still holds the legacy
  `app.snapsync.config`/`eventconfig` item from a pre-11a build
- **THEN** the item is not read, no membership is resurrected, and the read reports no config

#### Scenario: An update in place keeps the membership

- **WHEN** a joined device updates to a build carrying this change (its App-Group config file
  present, as any post-11a process start left it)
- **THEN** the read answers from the file, the membership survives, and the ledger is left intact — the
  update neither clears nor reloads it

#### Scenario: An unreadable config is still not a leave

- **WHEN** a cycle's config read fails for any reason outside the not-found error class — notably a
  protected-file read before first unlock
- **THEN** the read reports unreadable, the cycle skips, the ledger is left intact, and the next cycle
  retries; the loss of the fallback narrows what may read as absent, never widens it

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
`saveToAlbum`, and it is not a switch (no ledger reset).

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

### Requirement: A join loads the ledger from the per-device listing

The **app** SHALL load the upload ledger from the backend's record of this device at a provision whose
event **differs from the joined one** — a first join (no current membership) or a switch (a different event
is joined) — so a new membership starts with nothing from before it and re-uploads nothing the backend
already holds. The load SHALL:

1. fetch the **per-device** file listing (`list(deviceId)`, see "Event file list seam"), bounded by an
   explicit **15-second** timeout;
2. on a **successful** listing, call **`resetTo`** (atomic clear-and-seed, capability `sync-ledger`) with
   exactly one `COMPLETED` row per stored resource, each keyed by the **recomposed**
   `<assetId>-<role>.<ext>` key and carrying the `assetId` the listing reported. Every seeded row is
   **bare** (no manifest detail) and carries no timestamp and no event;
3. on a **failed** fetch — a transport failure, a decode failure, or the timeout — call **`clear()`**.

Either way the ledger holds nothing from before the provision. The clear is not incidental: a `COMPLETED` row
left from before the membership — on a device that left an event under the earlier contract, which kept
the ledger, or after a leave whose best-effort clear failed — would suppress a needed in-window upload
forever, with no error. Only the network fetch is bounded; the `resetTo` and `clear()` are single atomic
storage operations and are not timed.

The load SHALL run **once per membership change**, in the app process, on **every** iOS version:
`resetTo` and `clear()` are reset-family operations owned by this use case, not record operations of a
cycle's writer (capability `sync-ledger`, "Reader and writer capability split"). It SHALL run **before** the
membership's config is saved (and, on a switch, after uploads are stopped) and **before** either uploader is
registered or armed for it, so no cycle can ever see the new membership over the previous membership's ledger and the first
cycle the arm starts already sees the seed. A crash between the load and the save leaves either (first join)
an unjoined device holding a loaded ledger, which the next join clears anyway, or (switch) the previous
membership over a reloaded ledger, whose next walk simply re-records its work (`DISCOVERED` rows are
re-found by the walk; stored bytes are already `COMPLETED`); neither loses a photo. The rule lives in a `feature/membership`
use-case over the `LedgerStore` and `DeviceFilesSource` ports; `flow/Provision` reaches it only as an injected
effect (the flow-no-ports gate).

A re-provision of the **already-joined** event SHALL NOT load, clear, or reset anything: a `resetTo` there
would drop the `DISCOVERED` and `REQUESTED` rows of a live membership without stopping the uploaders whose
jobs own them.

A **confirmed-successful** listing SHALL be treated as **authoritative** — whether it reports every, some, or
**none** of the device's resources. A successful **empty** listing seeds nothing, and cannot be a transient
read: an upload confirms its bytes before the job succeeds, the storage listing is read-after-write
consistent, and the list endpoint never answers `2xx` for a failed or partial listing (capability
`api-endpoints`: a failure is `502`, surfaced as a fetch failure). A resource the listing omits is not
seeded and is uploaded idempotently by the producer (last-write-wins at the same destination).

A **transport** failure or timeout SHALL be logged at `Warn`. A **decode** failure SHALL be logged at
`Error`: it will not heal on a later join, and it reaches crash reporting as the permanent fault it is.

Seeding from the listing's reported `assetId` rather than by re-parsing the key is the direction that
cannot drift: the backend states identity, so the client has no reason to recover it from a string it
just composed. Because the listing is per-**device** and event-independent, the seed covers everything the
device has stored for any event; the rows outside the new membership's window stay bare and inert, since
every deciding reader applies the membership's policy (capability `photo-selection-policy`). A bare seeded
row inside the window is re-read and dated by the next walk (capability `sync-ledger`, "A walk re-reads only
the assets the ledger does not fully know").

#### Scenario: A stored resource is seeded completed

- **WHEN** a first join's per-device listing reports resources `(a1, primary, IMG_0001.JPG)` and
  `(a1, live, IMG_0001.MOV)`
- **THEN** the ledger holds exactly a `COMPLETED` row keyed `a1-primary.jpg` and one keyed `a1-live.mov`,
  each carrying the reported `assetId` and no manifest detail

#### Scenario: A switch replaces the previous membership's rows

- **WHEN** the device switches events while the ledger holds rows of the previous membership, including a
  `DISCOVERED` row and a `REQUESTED` row for resources absent from the listing, and the listing fetch succeeds
- **THEN** the `resetTo` leaves exactly one `COMPLETED` row per listed resource and nothing else

#### Scenario: A leftover completed row cannot suppress an upload

- **WHEN** the ledger holds a `COMPLETED` row, left from before the membership, for a resource the backend
  no longer holds, and a join's listing fetch succeeds without it
- **THEN** the row is gone after the load, and the producer uploads that resource when the walk admits it

#### Scenario: A successful empty listing clears the ledger

- **WHEN** a join's listing fetch succeeds and reports no resources
- **THEN** the ledger is empty after the load, and the join proceeds

#### Scenario: A failed fetch clears instead of seeding

- **WHEN** a join's listing fetch fails with a transport error or does not return within 15 seconds
- **THEN** the load calls `clear()`, logs at `Warn`, and the ledger holds nothing from before the provision

#### Scenario: A decode failure clears and is reported as a fault

- **WHEN** a join's listing cannot be decoded into the expected shape
- **THEN** the load calls `clear()` and logs at `Error`, not as a retryable fetch failure

#### Scenario: A re-provision of the joined event loads nothing

- **WHEN** a provision names the event the device has already joined
- **THEN** no listing is fetched, and the ledger is neither cleared nor reset

#### Scenario: The load precedes arming

- **WHEN** a first join or a switch provisions, on iOS 18–26.0 or on iOS ≥26.1
- **THEN** the app runs the load before saving the config and before registering or arming either uploader,
  so the first cycle for the membership, in either process, sees the loaded ledger

#### Scenario: A not-yet-stored resource uploads idempotently

- **WHEN** a resource is absent from the per-device listing
- **THEN** it is not seeded, and the producer uploads it when the walk admits it (an already-present object
  is overwritten last-write-wins)

### Requirement: A failed load blocks nothing

A failed join-time load SHALL block nothing. There SHALL be **no** flag recording that a load is owed, **no**
gate on the upload cycle, **no** retry of the load, and **no** user-facing failure state or re-scan-to-retry
affordance: the join completes and uploading proceeds against the cleared ledger.

The stated cost is that the device **re-uploads what the backend already holds** for the resources the
membership admits — idempotent overwrites of the same objects (the destination is
`(deviceId, assetId, role)`), bounded by the event window. That cost is small and visible, so it SHALL NOT
be traded for retry state that could strand a device behind a gate.

#### Scenario: The join completes after a failed load

- **WHEN** a join's listing fetch fails
- **THEN** the membership is saved, the upload mechanism is armed, and no failure state is shown

#### Scenario: A failed load costs idempotent re-uploads

- **WHEN** a join's load failed and the backend already holds some of the membership's admitted resources
- **THEN** the next cycles upload those resources again to the same destinations, and nothing re-seeds the
  ledger from the listing to recover them: no later cycle fetches it, and the foreground settle ("Foreground
  settles in-flight rows the backend already stores") only settles rows that already have a job in flight

### Requirement: The upload cycle does not detect membership changes

The upload cycle SHALL NOT detect a change of membership, and SHALL NOT fetch the per-device listing. Every
change of membership is an explicit app action — a join, a switch, or a leave — and each one sets the
ledger itself: a join or a switch loads it (see "A join loads the ledger from the per-device listing") and a
leave clears it (capability `leave-event`). There SHALL be **no** persisted `joinedEventId` marker, **no**
reconciliation port on the cycle, **no** seed deferral outcome, and **no** leave-side ledger or marker write
on the cycle's not-joined path.

The detection existed because a delete-and-reinstall of an already-joined app was once thought to leave a
membership that no provisioning path observed. It no longer can: the config is an App-Group file that dies
with the install (see "Reinstall means the device left the event"), so a reinstall holds no membership, and
rejoining means scanning an invite, which provisions.

A cycle that runs in a process where no provision ever executed SHALL upload against the ledger as it finds
it. That is correct by construction: whichever process ran the membership's provision loaded that ledger in
the shared App-Group store.

#### Scenario: A cycle never fetches the listing

- **WHEN** the upload cycle runs in either process, joined or not
- **THEN** it makes no per-device listing request and reads no join marker

#### Scenario: A contributing membership goes straight to its work

- **WHEN** a cycle runs for a joined, contributing membership
- **THEN** it proceeds from its entry gate to its admission and the membership's selection policy with no
  reconciliation step and no deferral outcome between them

#### Scenario: The not-joined path writes nothing

- **WHEN** a cycle runs with no membership configured
- **THEN** it uploads nothing and writes neither the ledger nor any marker

### Requirement: Foreground settles in-flight rows the backend already stores

On every foreground entry, the **app** SHALL ask the backend which resources it stores for this device, and
SHALL record `COMPLETED` for every `REQUESTED` row whose key the listing contains. It SHALL:

1. read the ledger's pending rows, and make no request when there are none;
2. fetch the **per-device** listing (`list(deviceId)`, see "Event file list seam"), bounded by the same
   15-second timeout as the join-time load;
3. for each pending key the listing contains, record `COMPLETED` through the guarded terminal write
   (`markTerminal(key, COMPLETED)`, capability `sync-ledger`), which applies only while the row is still
   `REQUESTED`.

It SHALL write nothing else. It SHALL NOT touch a `DISCOVERED` or `COMPLETED` row, SHALL NOT record `FAILED`,
SHALL NOT create or delete a row, and SHALL NOT seed or reset the ledger. It SHALL mark nothing done without
the backend listing the key's stored bytes. A listing entry exists only after a completed upload of that
key (see "A join loads the ledger from the per-device listing" for why a successful listing is
authoritative). Bytes stored by an earlier upload of the same key are the same object, so they settle the
row just as well.

A failed or timed-out fetch SHALL be logged, at `Warn` for a transport failure or timeout and at `Error`
for a listing this build cannot decode. It SHALL change nothing, and SHALL leave no flag, retry or gate
behind: the next foreground asks again.

**Why.** An upload's bytes can land long before the OS acknowledges the job, and sometimes the
acknowledgement never reaches this ledger at all. Under a full grant the PhotoKit extension learns of a
completion only at its next invocation. After a downgrade to a partial grant it is withheld and is presented
nothing: measured on an SE2 (iOS 26.6, 2026-09-22), four objects landed within ~30 s while their rows stayed
`REQUESTED` and the status read `Syncing` until full access returned. The backend is the one party that
knows, and the status the member looks at on foreground is what this corrects. A later acknowledgement then
finds a settled row, and its guarded write applies to nothing.

The settle SHALL run in the app process only, as a trigger step of its own. It SHALL NOT run inside the
upload cycle (see "The upload cycle does not detect membership changes") and SHALL NOT be sequenced behind
the upload pump, which can await a single cycle for many minutes after a long suspension. It needs no
serialization with a cycle, because its one write is the guarded terminal write the platform's own
callbacks already make beside a running cycle. It SHALL NOT run in the upload extension. Decision record:
`changes/selection-is-the-walk` (D4).

#### Scenario: A stored in-flight upload settles at foreground
- **WHEN** the app enters the foreground while the ledger holds a `REQUESTED` row whose key the per-device
  listing contains
- **THEN** that row becomes `COMPLETED`, and the status counts it as done

#### Scenario: A row without stored bytes is left alone
- **WHEN** a `REQUESTED` row's key is absent from the listing
- **THEN** the row stays `REQUESTED`

#### Scenario: Only in-flight rows are settled
- **WHEN** the listing contains the key of a `DISCOVERED` row
- **THEN** the row stays `DISCOVERED`, and no other state is written

#### Scenario: Nothing pending, nothing fetched
- **WHEN** the app enters the foreground with no pending ledger rows
- **THEN** no listing request is made

#### Scenario: A failed listing changes nothing
- **WHEN** the listing fetch fails or times out
- **THEN** no row changes, the failure is logged, and the next foreground tries again

#### Scenario: A late acknowledgement after the settle is a no-op
- **WHEN** a row the settle recorded `COMPLETED` is later acknowledged by the OS as succeeded
- **THEN** the guarded write applies to nothing and the row stays `COMPLETED`

#### Scenario: The extension never settles from the listing
- **WHEN** the upload extension runs a cycle
- **THEN** it makes no per-device listing request

