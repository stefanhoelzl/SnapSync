## ADDED Requirements

### Requirement: The world's operator provision loads the share set as a join does

The world's operator `provision()` SHALL itself perform the **join-time ledger load** a real join performs
(capability `join-event`), because it writes the config cell directly and runs no `JoinEvent` and no
`flow/Provision`. It SHALL perform it through the **same** composed share-set load `flow/Provision` runs,
never a world-local re-implementation: when the provision enters a **new** membership (no current event, or a different one) it
SHALL fetch the per-device listing from the mini-edge and, on success, `resetTo` the upload ledger from it —
one bare `COMPLETED` row per stored resource — and on failure `clear()` the ledger. Either way the provision
SHALL complete and SHALL leave no flag, gate, or retry state. A provision that re-provisions the **joined**
event SHALL NOT load, exactly as `flow/Provision`'s `Stay` does not. The load SHALL run **before** the
provision sets the config cell — the order `flow/Provision` uses (capability `join-event`) — so no cycle
the world runs can see the new membership over the previous membership's ledger.

Without this, every fixture that joins through the operator edge would start with a ledger no real join
produces: unloaded after a join, or carrying a previous membership's rows after a switch. The world would then
test a device state that cannot exist.

#### Scenario: A provision into a new membership loads the stored set

- **WHEN** the backend already stores own photos for the world's device and the world provisions an event
  it is not joined to
- **THEN** the ledger is loaded before the config cell is set, and on return the upload ledger holds one
  `COMPLETED` row per stored resource and nothing else, and the next cycle creates no upload job for those
  photos

#### Scenario: A switch through the operator provision replaces the ledger

- **WHEN** the world is joined to one event with rows in its upload ledger and provisions a different event
- **THEN** the ledger holds exactly the per-device listing's rows, and none of the previous membership's
  non-`COMPLETED` rows survive

#### Scenario: Re-provisioning the joined event does not load

- **WHEN** the world provisions the event it is already joined to while the ledger holds `DISCOVERED` or
  `REQUESTED` rows
- **THEN** no listing is fetched and those rows are untouched

#### Scenario: A failed load clears and completes

- **WHEN** the listing fetch fails while the world provisions into a new membership
- **THEN** the provision completes with the config present and the upload ledger empty

## MODIFIED Requirements

### Requirement: Backend object store with faithful read-models

The world SHALL provide an in-memory backend store holding the edge's state: deposited object keys per
device byte-partition (`files/devices/<deviceId>/<filename>`), and the **relational** state the real
backend keeps — events, per-`(eventId, deviceId)` memberships each carrying an `active`/`departed` state,
each membership's asset set, and the device-scoped resources with their `uploaded` flag. From this state it
SHALL compute the edge's read-models **faithfully in behavior** — the per-device file listing
(`GET /files/devices/<id>`), the event-wide union (`GET /events/<id>/files`), and the join-load
listing — where the join-load listing is the **same** per-device read-model the join-time share-set load
consumes (capability `upload-state-reconciliation`). Byte-level fidelity to the real Deno `api/` edge is **NOT** required: drift is **accepted**,
there is **no golden fixture**, and the store SHALL NOT mint real presigned S3 URLs (each `url` is a
synthetic in-memory handle the fake download seams resolve store-direct).

The per-device listing SHALL return one `{filename, url}` entry per resource recorded as uploaded. The
event-union SHALL span a device's memberships whether `active` or `departed`, include an asset **only when
every** resource that asset names is recorded as uploaded, tag each asset with its owning `deviceId`, and
gate on event existence (an unregistered event is absent, not empty).

Membership SHALL be modelled as a state on one membership record. The world SHALL NOT model the retired
active/departed sibling objects, nor resolve membership from object timestamps.

#### Scenario: Per-device listing reflects uploaded resources

- **WHEN** objects are deposited into a device's byte partition and recorded as uploaded, and the
  per-device listing is computed
- **THEN** it returns one `{filename, url}` entry per uploaded resource

#### Scenario: Union includes only complete assets, tagged by device

- **WHEN** a device's membership names an asset whose every resource is recorded as uploaded, and another
  asset with a resource that is not
- **THEN** the union includes the complete asset tagged with its `deviceId` and omits the incomplete one

#### Scenario: A departed member still contributes to the union

- **WHEN** a device's membership state is `departed` and its event still exists
- **THEN** the union still includes the assets it published before leaving

#### Scenario: Unregistered event is absent, not empty

- **WHEN** the union is computed for an event that does not exist
- **THEN** the read-model reports the event absent (a 404-equivalent that surfaces as a failed
  `union` `Result`), distinct from an existing event with no complete assets (an empty array)

#### Scenario: The join-time load reads the per-device listing

- **WHEN** a provision into a new membership loads the ledger with the photos the backend already stores for
  a device
- **THEN** it consumes the world's per-device listing read-model — the same one the backend serves, exposed once

### Requirement: Device model — one own device plus injectable foreign devices

The world SHALL fix exactly **one** own `deviceId` — the id used by the upload cycle, the edge upload
provider, the join-time share-set load, and own-device status — and SHALL allow **injecting** any number of foreign
devices, each with its own deposited byte objects and device manifest. The event-union SHALL return
foreign devices' complete assets (each tagged by `deviceId`), and the download controller (configured
with `myDeviceId` = the own device) SHALL skip own-device assets by id, so a foreign device's assets
flow through download → import → suppression while the own device's uploads never echo back.

#### Scenario: A foreign device's complete assets appear in the union

- **WHEN** a foreign device with deposited objects and a manifest is injected
- **THEN** its complete assets appear in the event-union, tagged with the foreign `deviceId`

#### Scenario: The own device's assets are not re-downloaded

- **WHEN** the union also contains the own device's assets
- **THEN** the download controller skips them by `deviceId` (client-side, the union being identity-blind)

### Requirement: Failure levers

The world SHALL expose controllable failure levers that drive the real stack's failure paths: a
**backend-offline** switch flipping the per-device listing and event-union routes to `502` (driving the
join-time load's failure path — the ledger cleared and the join completed regardless — and the download
union-failure path), the **job-limit** (`LIMIT_EXCEEDED`),
a **per-job `UploadError`** on the upload retry chain, an **import failure** (`ImportResult.Failed`),
and a **gallery-enumeration failure** — the own-device walk that computes the status total `N` throwing
as a platform walk can.

The enumeration lever is **one-shot**, arming the next walk only, because the state it creates is a
*transient* platform failure and a latched one could not show the recovery that follows. It exists
because the total distinguishes *not counted* (`null`) from a counted `0` (capability `gallery-status`),
and only a failing walk reaches the first: without it a test cannot assert that a walk which could not
run leaves the total unknown — and leaves the screen neutral — rather than collapsing to a `0` that
reads as "everything shared".

It SHALL additionally expose an import that **suspends after writing its marker** and resumes with an
outcome the test chooses, because that — not a report about it — is the state `SNAPSYNC-9` lives in
(capability `photo-download`), in **two** variants that differ in what the photo library can see:

- **suspended before the commit** — the marker is written and the asset is **not** created, so a presence
  lookup answers *absent* about a transaction that is still open. Acting on that answer is the reported
  defect.
- **suspended after the commit** — the marker is written, the asset **is** created, and only the report is
  missing, so a presence lookup answers *present*. This is the shape a process death leaves behind, and the
  only one adjudication can recover, since *present* is the verdict that settles a row against the marker it
  already holds.

Both leave an unconfirmed row and both keep the ref claimed. The world must be able to hold either state
open, drive other triggers against it, and only then resolve it.

Holding the transaction open is what a report about it cannot do. A lever that merely *returns* an
abandonment lets a test observe the aftermath, but never lets a second trigger run **while** the transaction
is live — which is the interleaving the defect occurs in, and the one the download controller's claim exists
to close.

A lever SHALL NOT settle the state it exists to create: while suspended it writes no confirmation, clears no
marker, and reports no outcome, because all three are things the completion callback does and supplying any
of them would erase the very state under test. Resuming it SHALL drive the real completion path for the
outcome chosen — landing the asset and settling the row against the marker it holds on success, or clearing
that marker on failure — so a test can reach the recovery as well as the defect.

The world SHALL also expose an **attempt cap** that raises once a ref has been imported more times than a
test permits. An unbounded re-selection of one ref is a live-lock, and a live-lock in a test is a hang; a
hang names no defect and proves nothing, so the cap converts it into an assertion failure that names the
count.

#### Scenario: Backend-offline leaves upload status untouched and fails the union

- **WHEN** the backend-offline switch is set and the status source refreshes and the download
  controller reconciles
- **THEN** own-device upload status is unaffected — it is ledger-backed and issues no storage read, so
  there is no last-good set to keep and nothing to go stale — and the download union read
  returns a failed `Result` (no partial import)

#### Scenario: A join while the backend is offline blocks nothing

- **WHEN** the backend-offline switch is set and the world provisions into a new membership over a gallery
  whose photos the backend already stores, and a cycle then runs with the switch cleared
- **THEN** the provision completes with the config present and the upload ledger empty — no flag, gate, or
  retry state is left behind — and the cycle re-uploads the in-window photos to the same destinations

#### Scenario: Each lever drives its real path

- **WHEN** the job-limit, a per-job `UploadError`, or an import failure is armed and the corresponding
  cycle runs
- **THEN** the real orchestration responds (deferred cycle, engine retry re-creating the job, or a
  non-terminal import failure respectively)

#### Scenario: A suspended import holds the guarded state open

- **WHEN** the before-commit suspending lever is armed and an asset is imported
- **THEN** the row carries a marker, the library holds no such asset, nothing has been reported, and a
  presence lookup answers *absent* — and that remains true until the test resumes it

#### Scenario: A suspension after the commit is recoverable by adjudication

- **WHEN** the after-commit suspending lever is armed and an asset is imported
- **THEN** the row carries a marker, the asset IS in the library, nothing has been reported, and a presence
  lookup answers *present* — so a later pass settles the row without creating a second asset

#### Scenario: Other triggers run while a transaction is live

- **WHEN** an import is suspended and a reconcile, a staged-resource callback, a leave or a switch is
  driven
- **THEN** each completes without waiting for the suspended import

#### Scenario: Resuming with success settles what it created

- **WHEN** a suspended import is resumed with a successful outcome
- **THEN** the asset appears in the library, the row is settled against the marker it holds, and the
  ref's claim is released

#### Scenario: Resuming with failure clears its own marker

- **WHEN** a suspended import is resumed with a failed outcome
- **THEN** the marker it wrote is cleared, the asset stays importable, and no unconfirmed row is left

#### Scenario: A runaway drain fails rather than hangs

- **WHEN** one ref is imported more times than the attempt cap permits
- **THEN** the importer raises, naming the count, so the test reports a failure rather than hanging

#### Scenario: A repeat import is distinguishable from the first

- **WHEN** the world's importer creates a second asset for a ref it has already imported
- **THEN** that asset carries a **different** created identifier, as the photo library mints one per
  request — so a test asserting on identifiers or on asset counts can observe a duplicate rather than
  mistaking it for the original

#### Scenario: A failed enumeration leaves the total un-counted

- **WHEN** the enumeration lever is armed and the status sources are refreshed
- **THEN** the refresh does not throw, the own-device total remains **not counted** rather than `0`, the
  cheap ledger and download reads still complete, and the next refresh — the lever being one-shot —
  produces a real count

### Requirement: Real-stack composition helpers

The world SHALL assemble its upload cycle through the **same shared composition the device tiers
call** — `uploadCore` (`:domain` `compose/`, spec `module-architecture` "One shared composition") over
the world's fakes — not through a world-local mirror of a composition root: the world supplies its
in-memory ports (`ConfigReader` over the config cell and the `membershipUnreadable` lever, the fake
`BackgroundTransfer`, the fake `UploadDiscovery`, the `:adapter:generic:fake`
ledger/discovery/manifest stores, the mini-edge HTTP seams) and `uploadCore` builds the real
`SyncEngine` + `EdgeUploadRequestProvider` + `UploadCycle` + `DeviceManifestProducer`
graph, exactly as it does for the device roots. The world composes **no** upload reconciler and **no**
joined-event marker, because `uploadCore` has neither: the upload ledger is loaded at a join and cleared at a
leave (capability `upload-lifecycle`), not reconciled inside a cycle. The app-side graph — download, status, membership, creation,
the command bundle — SHALL come from the composed `AppCore` (see "The world composes the app graph through
snapSyncApp"). Only the platform edges (`BackgroundTransfer`, `UploadDiscovery`, `DownloadTransport`,
`PhotoLibraryImporter`), the storage seams, and the HTTP client SHALL be fakes; everything above them SHALL
be the shipped production code.

#### Scenario: The composed upload path exercises the real cycle

- **WHEN** the world's `uploadCore`-assembled cycle is invoked
- **THEN** the real `SyncEngine`, `EdgeUploadRequestProvider`, and `UploadCycle` run, and only the job
  platform, library discovery, discovery store, ledger backend, and HTTP client are fakes

#### Scenario: A wiring difference from production is impossible

- **WHEN** the world and a device tier each assemble an upload cycle
- **THEN** both call the same `uploadCore` function over different port implementations, so the world
  cannot carry gate, manifest, or policy wiring production lacks (or vice versa)

#### Scenario: Production seams are not duplicated

- **WHEN** the world composes the manifest path
- **THEN** its `Enrollment` port is `:adapter:generic:app`'s `HttpEnrollment` over the injected mini-edge
  client — the world carries no copy of any production adapter (the step-10 death of the world's
  byte-identical `HttpEnrollment` closed the deletion ledger's last row)

### Requirement: Faithful leave composition helper

The world SHALL provide a `leave()` composition helper that runs the **real** leave edge —
`DownloadController.onLeaveOrSwitch()` (cancel in-flight transfers, prune non-terminal download rows),
the best-effort backend leave notify (`DELETE /events/<eventId>/devices/<deviceId>` against the world's
mini-edge), then **clearing the upload ledger**, then clearing the config cell — while **retaining**
imported foreign photos and the download store's rows on the device side. The upload ledger is cleared
exactly as the real leave clears it (capability `leave-event`): after a leave it holds no share set, so a
later provision starts from the join-time load and nothing from before it. It SHALL NOT be modelled by rebuilding the world
(which would forge the outcome and wrongly discard imported photos). The backend leave SHALL mutate the
world's state exactly as the real backend does — the membership's state becomes `departed` and nothing
else moves — so integration tests can assert **both** the device outcome (join cleared, upload ledger empty, imports
retained)
and the **world** outcome (the membership departed, its assets still in the union, the event and every
byte still present because reclamation belongs to the nightly sweep alone). Because
clearing the config cell is reactive, the status projection SHALL leave the joined layer
without any world rebuild, and re-provisioning the same event afterwards SHALL still find the previously
imported foreign assets suppressed (real cross-event dedup).

#### Scenario: Leave keeps imported photos, clears the join, and notifies the backend

- **WHEN** a foreign asset has been downloaded and imported, and `leave()` is then invoked
- **THEN** the real `onLeaveOrSwitch()` runs, the backend leave is dispatched to the mini-edge, the upload
  ledger and the config cell are cleared, and the imported asset remains enumerable in the gallery

#### Scenario: Leave clears the upload ledger but not the download store

- **WHEN** own photos have uploaded (their rows `COMPLETED`) and a foreign asset has been imported, and
  `leave()` is then invoked
- **THEN** the upload ledger holds no row, while the imported asset's download row is still present and its
  local id is still suppressed

#### Scenario: Re-provisioning after leave still suppresses the import

- **WHEN** the same event is re-provisioned after `leave()`
- **THEN** the previously imported foreign asset is still in `suppressedLocalIds()` and the own-device
  cycle does not re-upload it, and the own photos stored before the leave are loaded `COMPLETED` from the
  per-device listing, so the cycle does not re-upload them either

#### Scenario: Leaving as the last active device reaps the event in the world

- **WHEN** `leave()` is invoked for the world's own device when it is the event's last active member
- **THEN** the mini-edge deletes the event tree and garbage-collects the device's byte partition, and the world's backend read-models show the event and its objects gone

### Requirement: The world composes the real cycle rather than mirroring its assembly

The world SHALL drive an upload cycle by constructing the real cycle and invoking it, supplying the same
ports a composition root supplies. It SHALL NOT re-implement the roots' assembly — the membership decision,
the engine construction, and the hook wiring — in harness code.

A hand-written mirror of a composition root drifts from it, and drifts silently: before the app-driven
tier's since-retired leave-side reconciliation was fixed, the world **already reconciled** on its mirrored
path while the real tier did not. A mirror that is more correct than production is worse than one that is wrong, because it stays
green while the defect ships. What the world may keep is what the roots keep — translation from its own
in-memory state into the shared decision's arguments — plus a tier's genuinely tier-specific residue, which
it SHALL name as such (the OS-invoked tier's pending→processing requeue).

#### Scenario: The world's cycle is the real cycle
- **WHEN** the world runs an upload cycle
- **THEN** the cycle that runs is the shared upload cycle, reaching its entry decision through the same
  read the real tiers use

#### Scenario: The world cannot invent a membership the real tiers require
- **WHEN** the world runs a cycle with no joined event
- **THEN** no cutoff is substituted on its behalf; the cycle takes its not-joined outcome, as a real tier
  would

### Requirement: The world can model an unreadable membership

The world SHALL be able to present its membership as **unreadable**, distinctly from absent, so the skip
outcome (capability `upload-lifecycle`) is reachable from tests over the world.

This is the state a real device reaches on a background wake before first unlock, and it is the state three
shipped bugs have turned on. A world whose membership is a nullable cell can express only joined or absent,
so the outcome that matters most is the one no test can reach — the harness models the states that work and
omits the state that breaks.

#### Scenario: An unreadable membership is distinct from an absent one
- **WHEN** the world's membership is set unreadable and a cycle runs
- **THEN** the cycle skips, and the ledger and object store are untouched

#### Scenario: An absent membership takes the not-joined outcome
- **WHEN** the world's membership is cleared and a cycle runs
- **THEN** the cycle takes its not-joined outcome and writes nothing: it fetches no listing and resets no
  ledger, because clearing the ledger belongs to the leave, not to a cycle
