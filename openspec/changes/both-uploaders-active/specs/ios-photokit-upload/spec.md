## MODIFIED Requirements

### Requirement: Device manifest projection and side-channel upload

The extension SHALL project the current event's `device.json` from the **upload ledger's `COMPLETED`
rows** (capability `sync-ledger`), which carry the manifest's presentation detail (per `device-manifest`:
per asset its `assetId` and `creationDate`, and per resource its `role`, `contentType`, `key` (the
storage object name), and `filename` (the human capture name)). It SHALL keep **no** second durable
structure of manifest entries: the ledger is the only record the projection reads, so there is nothing
that can drift out of step with it.

On each cycle the extension SHALL **project** those rows to the current event's `device.json` — keeping
exactly the assets the membership's selection policy admits by capture date (capability
`photo-selection-policy`), applying that one policy rather than a date comparison of its own — and **PUT
it synchronously, in-cycle**, to `<host>/events/<eventId>/devices/<deviceId>` with `Content-Type:
application/json` — **not** over a background `URLSession`, and **not** through the `SyncEngine` or the
`createJob` path. The extension SHALL NOT be the only writer of `device.json`: the app's cycle projects the
same ledger through the same policy and publishes the same way (capability `device-manifest`), on this tier
as on every other. Each write is a complete, self-contained full-state snapshot (no read-modify-write), so
**the last write wins**. A crossed pair — one cycle PUTs snapshot S1, the other S2, S1's PUT lands last and
S2's marker lands last — can leave the server one snapshot behind with the marker claiming it current, until
the next ledger change re-PUTs; that is accepted as bounded and self-healing (decision record:
`changes/both-uploaders-active`). It MAY skip the PUT when the projection is
**byte-identical** to the last written `device.json` **for the same event**, recorded in the App-Group
`last-uploaded.json` marker — event-keyed, so a switch to a new event never compares equal to the prior
event's write and skips the new event's still-absent document. Because the resource field names (`key`,
`filename`) are part of that snapshot content, a build that changes them produces a projection that
differs from any previously-stored snapshot, so the first cycle on the new build re-PUTs `device.json`
with the new names — no special one-shot flag is needed. A process kill mid-PUT is benign —
`device.json` is write-only in v1 and the next cycle re-projects and re-PUTs, so the loss is caught and
converges. There are no `PENDING`/`DONE` manifest markers and no `handleEventsForBackgroundURLSession`
manifest wiring in the app; the only manifest state either process reads is that same event-keyed marker,
read and written by its own cycle.

#### Scenario: An already-uploaded asset stays listed without a new job

- **WHEN** the extension discovers asset `A`, and the engine answers `AlreadyUploaded` for every one
  of `A`'s resources (its keys are already `REQUESTED`/`COMPLETED`)
- **THEN** no job is created, and `A`'s already-`COMPLETED` rows are still projected into `device.json`,
  so an already-uploaded asset never drops out of the manifest

#### Scenario: Each cycle projects the ledger and PUTs device.json synchronously

- **WHEN** a `process()` cycle finishes its discovery and the projection differs from the last write
- **THEN** the extension projects the ledger's `COMPLETED` rows to the current event's `device.json` and
  PUTs it synchronously, in-cycle, to `<host>/events/<eventId>/devices/<deviceId>` (`Content-Type:
  application/json`), with no background `URLSession` task and no engine or job-creation involvement,
  each resource carrying `key` (the storage object name) and `filename` (the human capture name)

#### Scenario: Unchanged projection skips the PUT

- **WHEN** a cycle's projection is byte-identical to the last written `device.json`
- **THEN** the extension MAY skip the PUT for that cycle

#### Scenario: Field-name change re-PUTs the manifest once

- **WHEN** the first cycle runs on a build that renamed the resource fields to `key`/`filename` and a
  previously-stored `device.json` uses the old field names
- **THEN** the projection is no longer byte-identical to the stored snapshot, so the extension re-PUTs
  `device.json` with the new field names (clean cutover, no separate backfill)

#### Scenario: A kill mid-PUT is caught next cycle

- **WHEN** the extension process is killed while the synchronous `device.json` PUT is in flight
- **THEN** the partial write is discarded and the next cycle re-projects the ledger and re-PUTs
  `device.json`, converging without any cross-process marker

#### Scenario: Both processes' cycles publish, and the last write wins

- **WHEN** on iOS ≥26.1 under a full grant the app's cycle and the extension's cycle each publish
  `device.json` for the same event
- **THEN** each PUT is a full-state snapshot projected from the one ledger, the server holds whichever
  landed last, and any snapshot it is behind by is replaced by the next cycle that observes a ledger change

### Requirement: Extension registration is a disable→enable toggle

**On iOS ≥26.1** the app SHALL register the background-upload extension with a **disable→enable toggle**
whenever it registers it — forced at a **join** (a first join or a switch's join, after the share-set load),
and at a launch, a permission change or a development uploader-switch change only when the extension is
registrable (iOS ≥26.1 and a `GRANTED` grant) and the OS reads **no** record under `GRANTED`
(`upload-lifecycle`, "Membership transitions reconcile the upload mechanisms in one tested place") — `setUploadJobExtensionEnabled(false)` then `setUploadJobExtensionEnabled(true)` — rather than a bare enable. The system's `AssetResourceUploadJobConfiguration` is keyed by bundle id and **persists across app delete/reinstall and device reboot**; a stale record (e.g. left by a differently-signed build) makes a bare `enable(true)` fail with `PHPhotosError 3202` ("existing configuration record"), after which the system never launches the extension. The leading `enable(false)` deletes the stale record so `enable(true)` re-creates it cleanly for the currently-installed extension. On iOS 18–26.0 there is no such OS toggle and the extension is never registered; the app's uploader alone uploads there (see `ios-url-session-upload`).

The registration SHALL **span the membership**: it is made at the join wherever the extension is registrable
— a download-only membership included, whose extension cycle declines on the selection policy exactly as the
app's does — and SHALL NOT be removed by a reconfigure, a permission change, a launch, or a re-provision of the
already-joined event. It SHALL be removed only at a **leave** (a switch's leave included) and by a development
uploader switch turning the extension off. A disable wipes every in-flight OS job of the record, and no API
surfaces a vanished job; confining the disable to the leave, whose ledger clear follows it, and to a compared
register that runs only when the OS reads no record (so there are no jobs to wipe), is what leaves no
`REQUESTED` row orphaned in any shipped sequence (the uploader switch exists only in rig builds, and the jobs
its disable wipes are that test's intent). The ritual therefore carries **no repair** — there is no ledger
write in it.
Decision record: `changes/both-uploaders-active` (D5).

A stale record that still **reads** enabled is repaired only at the next join: the launch reconcile compares
rather than forcing, so the extension's in-flight jobs survive app launches (`upload-lifecycle`, "Launch
reconciles by comparison; only a join forces the repair").

The registration change SHALL be made through a **port** in `:domain` `ports/`, named for the need, whose
iOS adapter — the only implementation that calls `PHPhotoLibrary.setUploadJobExtensionEnabled` or
`isUploadJobExtensionEnabled` — lives in `:adapter:ios:app-only`, because only the app process ever
registers. The mechanism that performs the ritual SHALL hold no platform call of its own, and SHALL therefore live in `:domain` `feature/upload` beside the
app-driven tier's mechanism, named for the need rather than for the platform. This is the ports law applied where it was not: the call sat in
`:app:ios`, which is wiring-only and gated at `CyclomaticComplexMethod` threshold 2, so it could report the
platform's raw facts but could hold no decision about them. Behind a port, the ritual
and every arm of the outcome classification become executable on any host that can implement the port,
including JVM.

The registration record is OS state that this repo does not own, exactly as the upload-job queue is. Where
a target's host cannot hold such a record, the port's binding for that target answers in its place; see
"The upload-job subsystem binding is fixed by the compilation target".

#### Scenario: Stale registration is replaced, not rejected
- **WHEN** the app registers the extension on a grant on iOS ≥26.1 and a configuration record already exists
- **THEN** the existing record is deleted and a fresh one is inserted (no `3202` rejection), and the system can launch the extension

#### Scenario: The mechanism holds no platform call
- **WHEN** the mechanism that performs the disable→enable ritual is compiled
- **THEN** it names no platform API at all — the registration change and its read-back are reached through
  the registration port, and it touches no ledger port — so it compiles for every
  target the platform-free core does

#### Scenario: The ritual is executable off a device
- **WHEN** the ritual runs against a port implementation that reports a pre-existing configuration record
- **THEN** the leading disable reports that a record existed and was removed, the enable reports success,
  and the sequence is asserted without a physical device

#### Scenario: Deregistering is the disable alone
- **WHEN** the extension is deregistered — on a leave (a switch's leave included), or by a development
  uploader switch turning the extension off
- **THEN** the registration is removed (or, under a partial grant, the attempt is refused) and the
  deregistration itself touches no ledger row; on a leave, the leave's own ledger clear follows it

#### Scenario: A download-only join registers like any join
- **WHEN** a member joins with a download-only membership on iOS ≥26.1 under a full grant
- **THEN** the join's forced disable→enable runs, and each OS launch of the extension declines on the
  selection policy and reports `SKIPPED`

#### Scenario: Only a leave deregisters
- **WHEN** the membership is reconfigured in any direction, the photo grant changes, or the app launches,
  while the extension is registered
- **THEN** no disable is issued, so the extension's in-flight jobs survive; a register is attempted only
  when the extension is registrable and the OS reads no record under `GRANTED`

### Requirement: Re-provision resets sync state

On a **valid event-link (re)scan**, the host app SHALL re-provision the (possibly new) event
by persisting the config and reconciling the uploaders through the tier-neutral lifecycle
(`upload-lifecycle`). The registration steps below are **this tier's** (iOS ≥26.1) and SHALL NOT be applied
below 26.1, where there is no OS registration record to re-create (see `ios-url-session-upload`,
"App-driven lifecycle").

A **switch** (a re-provision into a different event) is a leave followed by a join (capabilities
`upload-lifecycle`, `join-event`). On this tier it SHALL run in the app, in this order: the leave transition
**deregisters** the extension (the disable alone — see "Extension registration is a disable→enable toggle")
and cancels the app's transfers, the provision's **join-time load** re-baselines the ledger — it fetches the
per-device file listing (capability `api-endpoints`) and **`resetTo`s** (atomic clear-and-seed) the ledger
to one already-uploaded row per stored file, or `clear()`s it when the fetch fails — through the app's
`LedgerStore`, with no `LedgerWriter` (see `ios-app-shell`, "The app resets the upload ledger at membership
transitions on every tier"), the new config is then saved, and only then does the join transition
re-register the extension (the
disable→enable toggle, forced). A first join takes the same load before the first registration. The extension
therefore never re-baselines the ledger itself: it constructs no join marker and runs no in-cycle
reconciliation, and its first cycle after the re-register already reads the loaded ledger.

The device-global listing seeds the stored files as already-uploaded, so **nothing already stored
re-uploads**, while the clear drops every row from before the provision and the cycle's walk, a full
enumeration like every walk, finds genuinely-unstored work. The extension's next cycle **re-projects**
the re-baselined ledger to the **new** event's `device.json` path. Rows seeded from the listing are
**bare** (a filename carries no capture date) and are therefore not listed until the walk backfills their
manifest detail; a bare row is always re-read by the walk (capability `sync-ledger`, "A walk re-reads only
the assets the ledger does not fully know"). The app decodes the event link only to gate this on a
valid payload; the authoritative decode/validate/persist still happens in the shared container intent.

A re-provision of the **already-joined** event (`SwitchDecision.Stay`) SHALL NOT reset the ledger — only a
provision that changes the membership loads it — and SHALL make **no registration call at all**: it is a
no-op for uploads. A re-scan changes nothing about the membership, so the extension's in-flight jobs SHALL
survive it. The stale-record repair does not need it: a reinstall wipes the config with the App Group, so a
reinstalled device always arrives as a real join, whose forced toggle repairs the record. The rule lives in
the membership entry, which alone reaches the join transition and which a `Stay` never runs (decision record:
`changes/both-uploaders-active`, D5).

#### Scenario: Valid re-scan re-baselines and re-projects to the new event
- **WHEN** a valid `https://<link domain>/join#…` event link is opened for a different event on iOS ≥26.1
- **THEN** the app disables the extension, `resetTo`s the ledger from the per-device file listing, saves
  the new config, and re-registers the extension (disable→enable), and the extension's next
  cycle re-projects `device.json` from that ledger to the new event path

#### Scenario: Already-stored photos do not re-upload on a switch
- **WHEN** the device switches to an event whose photos are already present in its device
  byte-partition (capability `api-endpoints`)
- **THEN** the join-time load's clear-and-seed seeds them as already-uploaded and the extension creates no
  new upload jobs for them

#### Scenario: Invalid event link does not re-provision
- **WHEN** an opened URL fails config decoding
- **THEN** no re-provision occurs (the ledger and the config are untouched)

#### Scenario: A re-scan of the joined event leaves uploads untouched
- **WHEN** a valid event link for the **already-joined** event is opened on iOS ≥26.1 while extension jobs
  are in flight
- **THEN** `setUploadJobExtensionEnabled` is not called, the ledger is not reset, and the in-flight jobs keep
  running and record their outcomes

#### Scenario: The disable→enable toggle is confined to this tier
- **WHEN** the app re-provisions an event on iOS 18–26.0
- **THEN** `setUploadJobExtensionEnabled` is not called, and the join transition arms only the app's uploader

### Requirement: Discovery prunes ledger rows for deleted assets

The extension SHALL record that an asset has left the library by **deleting** its ledger rows, and only on
the evidence capability `sync-ledger` defines ("Deletion is a presence diff over an authoritative walk"): an
authoritative walk that did not return the asset, for rows inside the membership's capture window that no
live job owns; or, for a single row that still needs a job, its key resolving to nothing. Each deletion is
one ledger transaction with its guard in the statement (capability `sync-ledger`), so it stays safe while the
app's cycle holds a `LedgerWriter` over the same ledger at the same time. No remote object is deleted: nothing on the device deletes an
uploaded object, and reclamation belongs entirely to the nightly sweep (capability `scheduled-cleanup`).
The one-way model is unchanged.

Deleting keeps the ledger honest about what still exists on device and, critically, removes a row left
non-`COMPLETED` by an asset deleted mid-upload. That row would otherwise keep `pending > 0` forever and hold
the extension in the perpetual `processing` re-invocation loop (see "Cap-aware creation and tri-state
processing result"). Because the device manifest is projected from those same rows, one deletion also makes
the next projected `device.json` stop listing the departed asset: there is no second structure to keep in
step.

The walk is narrowed by the membership's own selection policy, so "not returned" means gone from the
library **only inside the policy's capture window**. That is why the deletion is judged per row by the
policy's row admission and never by the admitted candidate set. The retired reconcile backstop was supplied
the policy-**admitted** set, so raising a capture cutoff discarded the `COMPLETED` rows of photos that were
still present. Judged by presence and row admission, a raised cutoff moves those rows out of the window
instead, and they survive. A membership whose direction excludes upload never reaches the walk at all: its selection policy admits
nothing, and the cycle declines on that before walking.

Deletion is now exhaustive for a full grant: a deletion is observed by the first authoritative walk after
it, whenever that is, with no token to expire. A re-added asset (for example, recovered from "Recently
Deleted") SHALL be discovered as new work and re-uploaded under its same keys. Its rows are gone, so nothing
suppresses the upload. The backend re-stores the role idempotently and wakes nobody (capability
`api-endpoints`). No `DELETED` state is introduced and the upload decision is unchanged.

#### Scenario: A departed asset's rows are deleted by the next walk
- **WHEN** asset `L` has in-window `COMPLETED` rows and an authoritative walk does not return it
- **THEN** the extension deletes those rows before recording the walk's discoveries, so `L` contributes to
  neither `pending` nor `completed` and the next projected `device.json` omits it

#### Scenario: Mid-upload deletion lets the extension rest
- **WHEN** an asset deleted before its upload completed leaves a `DISCOVERED` row
- **THEN** its key resolves to nothing at enqueue and that row is deleted, the ledger reaches no pending
  rows, and `process()` can return `completed` instead of looping on `processing`

#### Scenario: A walk deletes nothing outside its window
- **WHEN** an authoritative walk completes and the ledger holds rows, outside the membership's capture
  window, for an asset the walk did not return
- **THEN** those rows are kept: the walk is policy-narrowed, so an asset's absence from it is not evidence
  that the asset left the library

#### Scenario: A narrowed scope costs no ledger rows
- **WHEN** the membership's capture cutoff is raised past an already-uploaded asset still in the library,
  and a full enumeration runs
- **THEN** that asset's `COMPLETED` rows survive, so lowering the cutoff again re-lists it with no byte
  re-uploaded

#### Scenario: Re-added asset re-uploads
- **WHEN** an asset whose rows a walk deleted reappears in the library (for example, recovered from
  "Recently Deleted")
- **THEN** the next walk records its resources `DISCOVERED`, their upload is re-created under the same
  keys, and the next projection lists it again

### Requirement: The registration cannot be changed under a partial grant

The OS-driven tier SHALL create nothing while the containing app holds a partial (`.limited`) photo grant,
and the extension SHALL NOT be registrable there, because a partially-granted process **cannot change its
upload-job registration in either direction**.

Forcing proof: `setUploadJobExtensionEnabled` is refused with `PHPhotosErrorAccessUserDenied` (3311) for
both `false` and `true` — measured on device (SE2 / iOS 26.6, 2026-08-24 and 2026-08-25; decision record
`changes/archive/2026-08-25-collapse-upload-tier-seam`, D11 and D11b). The **enable** was reached only by
pinning the OS-driven mechanism under a partial grant through a development mechanism override (since
replaced by the development uploader switch), which no shipped build can supply; in production an enable is
never attempted there, because the extension is registrable only under `GRANTED`.

An earlier probe (SE2 / iOS 26.5, 2026-07-20;
`changes/archive/2026-07-20-accept-limited-photo-access/PROBE-FINDINGS.md`) measured that with real
pending work and the extension re-registered twice under `.limited`, the OS issued **zero** `process()`
invocations over 22 minutes, then invoked the extension **within seconds** of the grant returning to full.
That observation stands. The mechanism it was read as — *"registration succeeds and lies, with no error
and no callback"* — is **contradicted by measurement**: the call site discarded its `Boolean` and
`NSError` at the time, so "succeeds" described a return value nobody read and "no error" meant none was
looked for. A registration that could not be created explains those 22 minutes at least as economically.
Because that probe is not re-runnable, this SHALL be stated as the asserted mechanism being contradicted,
never as a claim about what that probe observed.

Evidence limits, stated so a reader can tell what would falsify this: one device, one OS point release,
and an enable reached through a development pin rather than a path a user can take. Expiry trigger:
re-evaluate at the iOS 27 GM re-assessment (~Sept 2026, the existing
`PHBackgroundResourceUploadJobExtension` trigger) — the constraint MUST be re-measured against the async
protocol before assuming it persists.

Consequently, under `LIMITED` the extension is not registrable (the registration fact is `GRANTED`-only,
capability `upload-lifecycle`), so no join, launch or permission change writes the registration there; only
a leave's disable reaches the platform, and its refusal is reported, not fatal. The app's cycle creates the
jobs instead, because it creates under every usable grant (capability `limited-photo-access`). A `LIMITED`
membership relying on this tier would be a silent no-op: the screen would sit at "Synchronization pending…"
indefinitely, which is exactly the failure mode this requirement exists to prevent.

A registration that **survives** a downgrade to a partial grant SHALL **create nothing**, by the extension's
own gate. The OS does NOT leave it alone — measured on the SE2, iOS 26.6, 2026-09-21: with a surviving record
under `LIMITED`, `process()` ran four seconds after a new photo joined the selection, and the gate withheld
it. That supersedes this requirement's earlier reading that such a record is inert because the OS does not
invoke it. The extension reads the grant in its own process and
withholds under anything but `GRANTED` ("The extension withholds its cycle without a full grant"): it
acknowledges the jobs the OS presents and records their outcomes, and creates none. The app SHALL NOT
deregister it on the downgrade — the disable would be refused, and a deregistration wipes jobs — and a return
to a full grant is a permission change whose compared register re-registers through the disable→enable ritual
only if the record reads absent. Under a **full** grant a deregistration happens only at a leave or through a
development uploader switch turning the extension off. Decision record: `changes/both-uploaders-active`.

**Measured (SE2, iOS 26.6, 2026-09-22):** jobs a surviving registration queued under a full grant **survive** a
`GRANTED → LIMITED → GRANTED` round trip. Four extension jobs were created under `GRANTED`, access was narrowed to
`.limited` within about a minute, and a withheld `process()` forced about 45 s later was presented **none** of
them — their rows stayed `REQUESTED`, and neither uploader touched them. After access returned to `GRANTED` the
first OS invocation of the extension presented all four as succeeded, and its guarded write recorded them
`COMPLETED`. Whether the bytes moved during the `.limited` interval or only after is not established; either way
nothing was lost or duplicated. While access stays `.limited` those rows stay `REQUESTED`, which is the accepted
exposure (`changes/both-uploaders-active`). ⏰ Re-measure at the next iOS major.

#### Scenario: A limited grant never waits on the extension
- **WHEN** photo access is `LIMITED` and an upload-inclusive membership has pending work
- **THEN** no upload waits on a `process()` invocation — the app's cycle creates the jobs

#### Scenario: A downgrade to a partial grant cannot deregister
- **WHEN** photo access transitions from `GRANTED` to `LIMITED` while the extension is registered
- **THEN** no deregistration is attempted (it would be refused with `PHPhotosErrorAccessUserDenied`), the
  configuration record survives, and the app's uploader is armed regardless

#### Scenario: A surviving registration creates nothing
- **WHEN** a registration survives a downgrade to a partial grant and the OS invokes the extension — which
  it does (measured, iOS 26.6)
- **THEN** the extension withholds at its gate: it acknowledges and records the jobs presented, creates no
  job and publishes no manifest, while the app's cycle creates jobs for `DISCOVERED` rows only

#### Scenario: An enable under a partial grant is refused too
- **WHEN** `setUploadJobExtensionEnabled(true)` is called under a `LIMITED` grant (as measured, through a
  development pin; no shipped transition makes this call)
- **THEN** the call is refused with `PHPhotosErrorAccessUserDenied` and no configuration record is created

### Requirement: A failed extension-registration change is reported, not discarded

`PHPhotoLibrary.setUploadJobExtensionEnabled` returns a `Boolean` and takes an `NSError**`. Both SHALL be
captured. A registration change that fails SHALL be reported with the error's domain and code, not
discarded.

This matters because the failure is otherwise **invisible and terminal**: if enabling fails, the extension is
never registered, the OS never launches it, no upload cycle ever runs, and the screen sits at
"Synchronization pending…" indefinitely with no error anywhere — in the log, on the screen, or in crash
reporting. The mechanism's failure mode is silence, which is precisely the case "Absence is never silent"
(spec `module-architecture`) exists to refuse.

A failing **enable**, and any failure whose error is not one of the **expected cases enumerated below**,
SHALL be logged at `Error` severity, so `crash-reporting` carries it as field telemetry rather than leaving
it knowable only by attaching to a device. The enumeration is **closed and measured**: a code is expected
only once a device measurement shows it arising on an ordinary path, and widening it is a change to this
requirement.

The **leading disable** of the disable→enable ritual SHALL NOT be treated as a failure when it reports
`PHPhotosError` **3201** ("Unable to find the configuration"). On any clean device there is no configuration
record to remove, so that outcome is the expected result of a first registration — measured twice on an SE2
(iOS 26.6). Raising on it would place a reporting event on every first join of every fresh install, burying
the signal this requirement exists to surface in noise the requirement itself created.

A **disable** that reports `PHPhotosErrorAccessUserDenied` (**3311**) SHALL likewise not be treated as a
failure. Under a partial photo grant the platform refuses the change outright ("The registration cannot be
changed under a partial grant"), so this is the expected outcome of an ordinary, supported user action —
switching Photos to Limited Access — and it recurs on every leave taken while that grant is held (the leave
is the only transition that disables). It SHALL be reported **below `Error`**, so no reporting event is raised, and at a severity
that still reaches the device log and the diagnostic dump, because the app's model of the registration is
knowingly wrong afterwards even though the surviving record creates nothing (its extension withholds, and
after a leave finds no membership). This is what `crash-reporting`
requires of any condition that is routine, expected, and self-healing.

An **enable** that reports **3311** SHALL remain at `Error`, and SHALL be reported as its own outcome
naming the cause rather than collapsing into the generic failure. The two directions have opposite
consequences: a refused disable leaves a record whose extension creates nothing and costs nothing, while a refused enable means no
registration exists, the OS never launches the extension, and nothing else reports it. Reporting them
identically would hide the terminal case behind the routine one.

The disable's own return SHALL be used as evidence rather than only as an error check: a disable that
**finds** a record returns `true` with no error, so the write distinguishes "there was a registration" from
"there was not" as a side effect of doing its job — a distinction the read-back cannot reliably make.

Both call sites SHALL go through one helper. `setUploadJobExtensionEnabled` serves both `start()` and
`stop()`, and checking one call but not the other would be a deliberate blind spot. The classification
SHALL remain a decision of the tested `:domain` `model/` classifier, which carries the severity as a
property of the outcome, so the call site renders without branching (`module-architecture`, "Shells are
wiring only").

#### Scenario: Enabling the extension fails
- **WHEN** `setUploadJobExtensionEnabled(true)` returns `false`
- **THEN** the failure is logged at `Error` severity with the error's domain and code, and reaches crash
  reporting as an event

#### Scenario: The fresh-install disable is not a failure
- **WHEN** the leading disable of the ritual runs on a device with no configuration record and returns
  `false` with `PHPhotosError` 3201
- **THEN** the outcome is logged at debug severity and raises no reporting event

#### Scenario: A refused disable under a partial grant is not a failure
- **WHEN** a disable returns `false` with `PHPhotosError` 3311 because the app holds a partial photo grant
- **THEN** the outcome is logged below `Error` severity, raises no reporting event, and still appears in
  the device log and any diagnostic dump

#### Scenario: A refused enable under a partial grant stays an error
- **WHEN** an enable returns `false` with `PHPhotosError` 3311
- **THEN** the outcome is logged at `Error` severity as a distinct outcome whose message names the partial
  grant as the cause, and reaches crash reporting as an event

#### Scenario: A disable that finds a record says so
- **WHEN** the leading disable runs on a device that already holds a configuration record
- **THEN** it returns `true` with no error, and that outcome is recorded as evidence that a registration
  existed

#### Scenario: Both halves go through the same check
- **WHEN** either `start()` or `stop()` changes the registration
- **THEN** the same helper captures the return and the error for both

### Requirement: The extension withholds its cycle without a full grant

The extension SHALL read the photo grant **in its own process** and pass it to its cycle's entry gate as the
admission answer (`upload-lifecycle`, "The upload cycle owns its entry decision"): it SHALL admit exactly
under `GRANTED` and **withhold** under `LIMITED`, `DENIED` and `NOT_DETERMINED`.

A withheld cycle SHALL acknowledge the terminal jobs the OS presented and record their outcomes — the
acknowledgement obligation whose omission makes the system report `50008`, discard the outstanding jobs and
defer the extension — but SHALL re-create no retry, create no job, walk nothing, and
publish **no** device manifest. It SHALL report `SKIPPED` at routine severity.

It SHALL NOT reuse the selection policy's decline — the `policy.contributes` decline a download-only
membership takes — which publishes an **empty** manifest: that is honest for a
direction that permanently excludes upload, and a grant is temporary. Publishing empty on a revoked or
undetermined grant would remove this device's photos from every member's view the moment the grant flipped.

The decision SHALL be made before the membership's selection policy is built, so a `NOT_DETERMINED` grant never
reaches the denylisted-album read that would present the permission dialog.

The grant read SHALL be the same `PHAuthorizationStatus` → `PermissionStatus` mapping the app process uses, held
once in `:adapter:ios:ext-safe` (whose `Photos` import the extension-safety gate allows) and delegated to by the
app-only permission adapter — one mapping, not a copy.

#### Scenario: A downgraded grant withholds the extension's cycle
- **WHEN** the OS invokes the extension while photo access is `LIMITED`
- **THEN** it acknowledges the presented jobs, creates no job, walks nothing, writes no manifest, and reports
  `SKIPPED`

#### Scenario: A withheld cycle does not blank the event union
- **WHEN** the extension withholds on a device whose manifest lists uploaded photos
- **THEN** that manifest is left as it was, so the photos stay visible to every member

#### Scenario: An undetermined grant raises no dialog in the extension
- **WHEN** the OS invokes the extension while photo access is `NOT_DETERMINED`
- **THEN** the cycle withholds before any album structure is read, and no permission prompt is issued

## REMOVED Requirements

### Requirement: Extension owns the single ledger writer

**Reason**: The premise is removed. On iOS ≥26.1 under a full grant both processes are active uploaders and
both hold a `LedgerWriter` over the App-Group ledger at once; the ledger's writer rule is code ownership —
each write is the running cycle's record family, a transport's guarded `markTerminal`, or a named
reset-family use case, one transaction with its guard in the statement — not process exclusivity. Enforcing
one writer is what made every hand-off between the uploaders cancel or orphan in-flight work. Decision
record: `changes/both-uploaders-active` (D1, D2).

**Migration**: The writer rule lives in `sync-ledger` and `module-architecture`; which process creates under
which grant lives in `upload-lifecycle` (the extension under `GRANTED` only, the app under `GRANTED` or
`LIMITED`). Overlap between the two cycles is made harmless by write-after-act and the guarded
`markTerminal`, not prevented.

### Requirement: Re-registering the extension demotes orphaned REQUESTED rows

**Reason**: Nothing orphans a `REQUESTED` row any more, so there is nothing to repair. The registration spans
the membership and is disabled only at a leave (whose ledger clear follows) or by a development uploader
switch; a compared register runs only when the OS reads no record, so there are no jobs to wipe; a
re-provision of the joined event makes no registration call. `LedgerStore.demoteRequested` and its off-main
helper are deleted with the ritual's demote. Decision record: `changes/both-uploaders-active` (D5, D10).

**Migration**: None for data (no schema change). The join keeps the disable→enable toggle for the `3202`
stale-record repair, without a ledger write ("Extension registration is a disable→enable toggle"). A job the
OS loses silently leaves its row `REQUESTED`; that exposure is accepted (never observed).
