# harness world model Specification

## Purpose

A controllable in-memory "world" (`:test:world`) that the REAL platform-agnostic stack — `SyncEngine`
+ `UploadCycle`, `ExtensionReconciler`, `DeviceManifestProducer`, `DownloadController` +
`QueuedPhotoDownloadJobs`, `OwnDeviceGalleryStatusSource` + `LedgerBackedSyncStatusSource`,
`CreateEvent` — runs against, so the whole system (upload AND download) is observable and testable on
JVM + `iosSimulatorArm64` without a device. It provides a backend object store computing the edge's read-models faithfully (drift
accepted, no golden fixture), a Ktor `MockEngine` mini-edge over the four common-Ktor seams,
operator-driven upload/download job fakes, a one-own-plus-injectable-foreign device model, controllable
failure levers, and composition helpers mirroring the extension composition root. Consumed by BOTH the
desktop full-stack harness (`:app:desktop`) and `:test:integration`.

It exists because the code that most needs coverage — the upload cycle's adjudication, the rejoin reconcile,
the download echo-suppression — is exactly the code that ran only inside an iOS extension that cannot be
tested on a simulator. Faking the *execution edge* rather than the logic lets the real stack run anywhere,
which is what makes testing rule 1 (every unit test also runs on the iOS simulator) achievable for
orchestration and not just for pure functions.

Decision record: `changes/archive/2026-07-03-add-harness-world-model`.
## Requirements
### Requirement: Controllable in-memory world module

The system SHALL provide a test-infra Kotlin Multiplatform module `:test:world` that runs the
**real** platform-agnostic stack against controllable in-memory infrastructure: the honest
in-memory port implementations SHALL live in `:adapter:fake` (package `app.snapsync.fake`; spec
`module-architecture`), and `:test:world` SHALL hold the **operator rigging** around them — the
backend store, the mini-edge, the levered fakes (`FakeBackgroundTransfer`,
`FakeDownloadTransport`, `FakePhotoLibraryImporter`, `FakeAlbumManager`,
`MutablePhotoAccessStatusSource`) and the wrappers that own the honest fakes' state cells
(`WorldGallery`, `RecordingDownloadStore`) — per the fake-honesty gate (`architecture-guards`).
The module SHALL declare targets `jvm()` and `iosSimulatorArm64` **only** (no `iosArm64` — it
never links into a shipped framework), so its logic and self-tests execute on **both** JVM and the
iOS simulator per testing rule 1. Its `commonMain` SHALL also host the shared storage-seam
contracts (`LedgerStoreContract`, `DownloadStoreContract`) — a test source set cannot be depended
on across modules, and this is the one test-infra `commonMain` every implementor's test source set
(`:test:world` commonTest for the fakes, `:adapter:generic` `jvmTest`/`iosSimulatorArm64Test` for
the SQLDelight stores) can reach. It SHALL be consumed by **both** the desktop full-stack harness
(`:app:desktop`) and the `:test:integration` module. Nothing in a production `domain`/`adapter`
module's **main** source sets SHALL depend on `:test:world` (the adapter test source sets extending
the contracts are test compilations, so no production edge is introduced).

#### Scenario: Runs on JVM and the simulator

- **WHEN** the module's self-tests are run
- **THEN** they execute on `jvm()` and `iosSimulatorArm64` (the module declares no `iosArm64` target)

#### Scenario: Consumed by both the harness and integration tests

- **WHEN** the desktop harness and `:test:integration` each assemble a world
- **THEN** both reach the same world class over the same `:adapter:fake` doubles, and no production
  main source set gains a dependency back into `:test:world`

#### Scenario: Rigging cannot live in a fake

- **WHEN** an operator lever (a settable cell, a failure switch, an inspection list) is needed on an
  honest `:adapter:fake` double
- **THEN** it is expressed in a `:test:world` wrapper owning the fake's constructor-injected state,
  never as a public member of the fake (the fake-honesty gate fails otherwise)

### Requirement: Backend object store with faithful read-models

The world SHALL provide an in-memory backend object store holding the edge's state: deposited object
keys per device byte-partition (`files/devices/<deviceId>/<filename>`), one device manifest per
`(eventId, deviceId)`, and a registered-event marker set. From this state it SHALL compute the edge's
read-models **faithfully in behavior** — the per-device file listing (`GET /files/devices/<id>`), the
event-wide union (`GET /events/<id>/files`), and the reconcile-seed listing — where the reconcile-seed
listing is the **same** per-device read-model consumed by the rejoin reconciler. Byte-level fidelity to
the real Deno `backend/` edge is **NOT** required: drift is **accepted**, there is **no golden
fixture**, and the store SHALL NOT mint real presigned S3 URLs (each `url` is a synthetic in-memory
handle the fake download seams resolve store-direct). The per-device listing SHALL return one
`{filename, size, url}` entry per stored object. The event-union SHALL include an asset **only when
every** resource named by that asset's manifest entry is present in its device's byte partition, tag
each asset with its owning `deviceId`, and gate on event-marker presence (an unregistered event is
absent, not empty).

#### Scenario: Per-device listing reflects deposited objects

- **WHEN** objects are deposited into a device's byte partition and the per-device listing is computed
- **THEN** it returns one `{filename, size, url}` entry per deposited object

#### Scenario: Union includes only complete assets, tagged by device

- **WHEN** a device's manifest names an asset whose every resource `key` is present in that device's
  partition, and another asset with a missing resource
- **THEN** the union includes the complete asset tagged with its `deviceId` and omits the incomplete one

#### Scenario: Unregistered event is absent, not empty

- **WHEN** the union is computed for an event with no registered marker
- **THEN** the read-model reports the event absent (a 404-equivalent that surfaces as a failed
  `union` `Result`), distinct from a registered event with no complete assets (an empty array)

#### Scenario: The reconcile seed reads the per-device listing

- **WHEN** the rejoin reconciler seeds already-stored photos for a device
- **THEN** it consumes the world's per-device listing read-model — the same one the backend serves, exposed once

### Requirement: MockEngine mini-edge over the four common-Ktor seams

The world SHALL expose a Ktor `MockEngine`-backed `HttpClient` — a "mini-edge" — that answers the
app-side metadata calls by dispatching on HTTP method + request path against the backend object store,
so the **real** common-Ktor seams run unmodified against it. The mini-edge SHALL route
`GET /files/devices/<id>` (per-device listing), `GET /events/<id>/files` (event-union; a `404` when the
event marker is absent), `POST /events` (a `201` `{ eventId, name, createdAt }` that registers the
marker), and `PUT /events/<id>/devices/<id>` (a `200` that deposits the manifest into the store), and
SHALL answer any unmatched request `404`. The same `HttpClient` SHALL be injected into the real
`HttpDeviceFilesSource`, `HttpEventUnionSource`, `HttpEventCreation`, and the module's common
`HttpEnrollment`, mirroring the extension composition root's single shared client.

#### Scenario: Real seams round-trip against the mini-edge

- **WHEN** the real `HttpDeviceFilesSource`, `HttpEventUnionSource`, and `HttpEventCreation` are
  each given the mini-edge client and invoked
- **THEN** each parses a well-formed response computed from the backend object store (the listing, the
  union, and a minted event id respectively)

#### Scenario: A manifest PUT lands in the store

- **WHEN** the common `HttpEnrollment` PUTs a manifest to `/events/<id>/devices/<id>` via the
  mini-edge
- **THEN** the manifest is deposited into the store and subsequently participates in the union
  completeness computation

#### Scenario: Event creation registers the marker

- **WHEN** `POST /events` is answered
- **THEN** a canonical event id is minted, the response is `201 { eventId, name, createdAt }`, and the
  event marker is registered so a subsequent union read is gated in (not 404)

### Requirement: Operator-driven, inspectable upload-job lifecycle

The world SHALL provide a fake `BackgroundTransfer` that models the OS upload-job lifecycle as an
operator-driven, **inspectable** queue implementing all six seam methods (`fetchRetryJobs`,
`fetchAckJobs`, `retryJob`, `acknowledge`, `discoverResources`, `createJob`). `createJob` SHALL enqueue
a PENDING job and return `CREATED`, unless a **settable job-limit** is reached (returning
`LIMIT_EXCEEDED`) or a forced create-failure is set (returning `FAILED`). An operator **complete**
action SHALL deposit the job's object key into the backend object store **store-direct** (byte transfer
is not routed through ktor) and move the job to the acknowledge bucket, so the next cycle records it
`COMPLETED`. An operator **fail** action SHALL move the job to the retry bucket carrying a chosen engine
`UploadError` (`Network`, `Http`, `Cancelled`, or `Unknown`), driving the real engine retry chain with
an incremented attempt. The queue's pending/retry/acknowledge buckets and per-job attempt SHALL be
inspectable so tests assert the lifecycle, not only the final outcome.

#### Scenario: Complete deposits the object and the ledger records COMPLETED

- **WHEN** the operator completes a created job and the next upload cycle runs
- **THEN** the object key is present in the backend store and the ledger holds a `COMPLETED` row for it

#### Scenario: Fail drives the real retry chain

- **WHEN** the operator fails a created job with a chosen `UploadError` and the next cycle runs
- **THEN** the engine answers `Retry`, the job is re-created, and its attempt count increments

#### Scenario: Job-limit defers the cycle

- **WHEN** the job-limit is set below the number of `Work` resources in a cycle
- **THEN** `createJob` returns `LIMIT_EXCEEDED`, the cycle returns `PROCESSING`, and the discovery
  cursor does not advance

### Requirement: Token-delta discovery feed driven by the in-memory gallery

The world's fake `BackgroundTransfer.discoverResources(sinceToken)` SHALL derive its change feed from the
in-memory gallery (`InMemoryRawAssetSource` mapped through the real resource fan-out). Adding an asset
SHALL surface it as a new `Resource` in `Discovery.resources`; removing an asset SHALL surface its id in
`Discovery.removedAssetIds`; and an operator **expire-token** action SHALL return
`Discovery.fullEnumeration = true` carrying the whole current key-set (the routine token-expiry path),
so the real cycle reconciles via `retainAssets`.

#### Scenario: Adding an asset yields a new resource

- **WHEN** an asset is added to the in-memory gallery and discovery runs
- **THEN** `Discovery.resources` carries that asset's resources

#### Scenario: Removing an asset yields a removed id

- **WHEN** an asset is removed and discovery runs
- **THEN** `Discovery.removedAssetIds` carries its id and the cycle prunes its ledger rows

#### Scenario: Expiring the token forces a full enumeration

- **WHEN** the operator expires the token and discovery runs
- **THEN** `Discovery.fullEnumeration` is `true` with the whole key-set, and the cycle reconciles via
  `retainAssets`

### Requirement: Operator-driven download seams exercising echo-suppression

The world SHALL fake the download **execution edge** — `DownloadTransport` — and compose the **real**
`QueuedPhotoDownloadJobs` over it, rather than faking `PhotoDownloadJobs` wholesale. Faking the layer above
the orchestration would leave the real bounded in-flight window, transfer-description codec, URL guard, and
transfer-integrity check unexercised by every world test and by `:test:integration` — the world's whole
premise is that the real stack runs against it, and the download half was the one place it did not.

The fake transport SHALL record started transfers inspectably, and an operator **stage** action SHALL
deliver a finish for each in-flight transfer through the real jobs, carrying a `TransferOutcome` the
operator chooses. A default outcome SHALL describe an ordinary healthy transfer. A non-staged download SHALL
simply remain PENDING for retry — there is **no** `DownloadError` type and no terminal transfer-failure
state; a transfer whose outcome the real jobs reject leaves its resource un-staged, which **is** that
pending-for-retry state rather than a new one.

Because the transfer now runs through the real jobs, the world SHALL be constructed with the **driver's**
`CoroutineScope` — the `worldTest` scope in tests, the inspector's in the desktop harness.
`QueuedPhotoDownloadJobs` requires one at construction, and a world-owned scope would outlive its caller,
leak staging work between worlds, and be unjoinable. The world SHALL NOT offer a scope-free fallback path,
because two ways to drive downloads is a second one that can rot or lie.

An operator action SHALL be complete when it returns. The real `onStaged` is not a suspend seam — in
production it is invoked from the platform's delegate thread and must hop into a coroutine — so the world
SHALL await the staging work it launched before the stage action returns. Otherwise every download
assertion in the world becomes a race, which is the opposite of what an operator-driven harness is for.

`PhotoLibraryImporter.import` SHALL import the asset into the in-memory gallery (so it enters gallery
enumeration) and mark the download store imported, so the imported asset's id enters `suppressedLocalIds()`;
a settable import-failure SHALL yield `ImportResult.Failed`. Because the real `UploadCycle.suppressedAssetIds`
and `OwnDeviceGalleryStatusSource` consult that suppression set, a foreign asset that is downloaded and
imported SHALL NOT be re-uploaded by the own-device cycle.

#### Scenario: A downloaded-and-imported asset is suppressed from re-upload

- **WHEN** a foreign asset is discovered via the union, staged, and imported into the gallery, and the
  own-device upload cycle then runs
- **THEN** the imported asset appears in `suppressedLocalIds()` and the cycle creates no upload job for
  it (echo-suppression holds)

#### Scenario: A non-staged download stays pending

- **WHEN** an enqueued download is not staged by the operator
- **THEN** its resource remains PENDING for retry and no terminal failure is recorded

#### Scenario: Import failure is surfaced without a terminal state

- **WHEN** the operator arms an import failure and import runs
- **THEN** `import` returns `ImportResult.Failed` and the asset remains importable

#### Scenario: The real download orchestration runs against the world

- **WHEN** downloads are enqueued and the operator stages them
- **THEN** they pass through the real `QueuedPhotoDownloadJobs` — its window, description codec, URL guard
  and integrity check — and only the `DownloadTransport` is fake

#### Scenario: An operator-forced bad transfer is not staged and stays pending

- **WHEN** the operator stages a transfer with a non-2xx or short-read outcome
- **THEN** the resource is not staged, no import is attempted against it, and it remains PENDING for retry
  rather than entering a terminal failure state

### Requirement: Device model — one own device plus injectable foreign devices

The world SHALL fix exactly **one** own `deviceId` — the id used by the upload cycle, the edge upload
provider, the reconciler, and own-device status — and SHALL allow **injecting** any number of foreign
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
reconcile-seed failure path and the download union-failure path), the **job-limit** (`LIMIT_EXCEEDED`),
a **per-job `UploadError`** on the upload retry chain, and an **import failure** (`ImportResult.Failed`).

#### Scenario: Backend-offline leaves upload status untouched and fails the union

- **WHEN** the backend-offline switch is set and the status source refreshes and the download
  controller reconciles
- **THEN** own-device upload status is unaffected — it is ledger-backed and issues no storage read, so
  there is no last-good set to keep and nothing to go stale — and the download union read
  returns a failed `Result` (no partial import)

#### Scenario: Each lever drives its real path

- **WHEN** the job-limit, a per-job `UploadError`, or an import failure is armed and the corresponding
  cycle runs
- **THEN** the real orchestration responds (deferred cycle, engine retry with incremented attempt, or a
  non-terminal import failure respectively)

### Requirement: Real-stack composition helpers

The world SHALL assemble its upload cycle through the **same shared composition the device tiers
call** — `uploadCore` (`:domain` `compose/`, spec `module-architecture` "One shared composition") over
the world's fakes — not through a world-local mirror of a composition root: the world supplies its
in-memory ports (`ConfigReader` over the config cell and the `membershipUnreadable` lever, the fake
`BackgroundTransfer`, the `:adapter:fake` ledger/discovery/manifest/marker stores, the mini-edge HTTP
seams) and `uploadCore` builds the real `SyncEngine` + `EdgeUploadRequestProvider` + `UploadCycle` +
`ExtensionReconciler` + `DeviceManifestProducer` graph, exactly as it does for the device roots. The
app-side graph — download, status, membership, creation, the command bundle — SHALL come from the
composed `AppCore` (see "The world composes the app graph through snapSyncApp"). Only the platform
edges (`BackgroundTransfer`, `DownloadTransport`, `PhotoLibraryImporter`), the storage seams, and the
HTTP client SHALL be fakes; everything above them SHALL be the shipped production code.

#### Scenario: The composed upload path exercises the real cycle

- **WHEN** the world's `uploadCore`-assembled cycle is invoked
- **THEN** the real `SyncEngine`, `EdgeUploadRequestProvider`, and `UploadCycle` run, and only the job
  platform, discovery store, ledger backend, and HTTP client are fakes

#### Scenario: A wiring difference from production is impossible

- **WHEN** the world and a device tier each assemble an upload cycle
- **THEN** both call the same `uploadCore` function over different port implementations, so the world
  cannot carry gate, reconcile, manifest, or policy wiring production lacks (or vice versa)

#### Scenario: Production seams are not duplicated

- **WHEN** the world composes the manifest path
- **THEN** its `Enrollment` port is `:adapter:generic`'s `HttpEnrollment` over the injected mini-edge
  client — the world carries no copy of any production adapter (the step-10 death of the world's
  byte-identical `HttpEnrollment` closed the deletion ledger's last row)

### Requirement: The world composes the app graph through snapSyncApp

The world SHALL hold the app-side graph as a real `AppCore` produced by the **same** `snapSyncApp`
composition the iOS app shell calls (spec `module-architecture`, "One shared composition"),
constructed over an `AppPorts` whose ports are the world's fakes and mini-edge seams and whose
shell-supplied lambdas are the world's operator surface: `provision` writes the config cell,
`notifyLeave` is the real backend DELETE seam, `onEventMinted` is a settable routing hook (default:
provision the minted event directly; the desktop inspector points it at the status host's
pending-join gate), and `uploadProducer` is inert (nothing auto-runs — the operator plays the OS).
The world's exposed download controller, status sources, creation status, join use-case, and
user-tap command bundle SHALL be `AppCore`'s instances — never world-local rebuilds — so a wiring
difference between the harness and the app shell is impossible rather than undetected.

Two named deviations are permitted, each an operator-synchronicity concern and nothing else: the
world MAY re-install the composed `downloadJobs.onStaged` hook with an **identical body plus Job
retention** (so `stageAllDownloads` is complete on return), and the world's operator `leave()` MAY
remain a synchronous faithful edge beside the bundle's production-ordered leave (whose backend
notify is fire-and-forget by design); tests driving the bundle's leave await the backend outcome.

#### Scenario: The harness's app graph is the production graph

- **WHEN** the world harness or an integration test fires a user-tap command (create, commit-join,
  leave)
- **THEN** the command runs through `AppCore.userCommands` — the same compose-built bundle the iOS
  shell injects — over the world's ports, and its effects land in the world's fakes and mini-edge

#### Scenario: The world cannot rebuild what the composition owns

- **WHEN** the world or its inspector needs a status source, download controller, or join use-case
- **THEN** it reads the composed `AppCore`'s instance; no second assembly of a feature graph exists
  in harness code

### Requirement: Integration tests assert UiState and world outcomes

The `:test:integration` module SHALL consume `:test:world` and `:ui:presentation` (re-homed from
`:domain:presentation` at migration step 9) to assert both the
projected `UiState` **and** world outcomes from world mutations and cycle invocations — not `UiState`
alone. World outcomes SHALL include: objects landed in the backend store (the per-device listing grows),
ledger rows reaching `COMPLETED`, and foreign photos imported into the in-memory gallery. This is the
testing-rule-3 seam ↔ UI-state integration surface, now spanning the real upload/download execution edge
rather than injected `SyncEvent`s alone, and it SHALL run on JVM and `iosSimulatorArm64`.

#### Scenario: A completed upload advances both UiState and the store

- **WHEN** an asset is added, its job created and completed, and the cycle plus a status refresh run
- **THEN** the projected `UiState` reaches `Joined(SyncHealth.InSync)` **and** the object is present in
  the per-device listing with a `COMPLETED` ledger row

#### Scenario: A foreign download imports and is observable

- **WHEN** a foreign device's complete asset is reconciled, staged, and imported
- **THEN** the imported asset is present in the in-memory gallery and (via suppression) is not
  re-uploaded, and the outcome is assertable at the store/gallery level alongside `UiState`

### Requirement: Faithful leave composition helper

The world SHALL provide a `leave()` composition helper that runs the **real** leave edge —
`DownloadController.onLeaveOrSwitch()` (cancel in-flight transfers, prune non-terminal download rows),
the best-effort backend leave notify (`DELETE /events/<eventId>/devices/<deviceId>` against the world's
mini-edge), then clearing the config cell and the joined-event marker — while **retaining** imported
foreign photos and the ledger on the device side. It SHALL NOT be modelled by rebuilding the world
(which would forge the outcome and wrongly discard imported photos). The backend leave SHALL mutate the
world's object store through the same mini-edge cascade a real backend runs (rename to `.left.json`,
last-active-member reap, reference-checked GC), so integration tests can assert **both** the device
outcome (join cleared, imports retained) and the **world** outcome (the device's manifest renamed
departed; the event tree and freed byte partition removed when it was the last active member). Because
clearing the config cell is reactive, the status projection SHALL leave the joined layer
without any world rebuild, and re-provisioning the same event afterwards SHALL still find the previously
imported foreign assets suppressed (real cross-event dedup).

#### Scenario: Leave keeps imported photos, clears the join, and notifies the backend

- **WHEN** a foreign asset has been downloaded and imported, and `leave()` is then invoked
- **THEN** the real `onLeaveOrSwitch()` runs, the backend leave is dispatched to the mini-edge, the config cell and joined-event marker are cleared, and the imported asset remains enumerable in the gallery

#### Scenario: Re-provisioning after leave still suppresses the import

- **WHEN** the same event is re-provisioned after `leave()`
- **THEN** the previously imported foreign asset is still in `suppressedLocalIds()` and the own-device
  cycle does not re-upload it

#### Scenario: Leaving as the last active device reaps the event in the world

- **WHEN** `leave()` is invoked for the world's own device when it is the event's last active member
- **THEN** the mini-edge deletes the event tree and garbage-collects the device's byte partition, and the world's backend read-models show the event and its objects gone

### Requirement: Mini-edge leave cascade

The `:test:world` mini-edge SHALL answer `DELETE /events/<eventId>/devices/<deviceId>` with the same
cascade the real backend runs over its in-memory object store: rename the active manifest to
`<deviceId>.left.json` (fresh write time), then, if no active member remains under
`events/<eventId>/devices/` (resolved by the last-write-wins rule over sibling write times), delete the
event tree and, for each freed device that appears in no surviving event, delete its
`files/devices/<deviceId>/` objects and its `devices/<deviceId>.json` config. Its union and notify
read-models SHALL apply the same active/departed last-write-wins resolution, so departed devices remain
in the union but are excluded from notify fan-out. The cascade SHALL be idempotent under repeated calls.

#### Scenario: Mini-edge renames then reaps

- **WHEN** the mini-edge receives `DELETE /events/<eventId>/devices/<deviceId>` for the last active member
- **THEN** it renames the manifest to `.left.json`, deletes the event tree, and GCs the orphaned device's bytes and config

#### Scenario: Mini-edge union keeps a departed device, notify drops it

- **WHEN** a device is departed (its winning sibling is `<deviceId>.left.json`) while the event has other members
- **THEN** the mini-edge union includes that device's assets and the mini-edge notify fan-out excludes it

### Requirement: The world's event marker carries a start date

The world's backend object store SHALL model the event marker as `{ eventId, name, createdAt, startsAt }`
— the same four fields the real marker carries (capability `event-creation`) — and its registration seam
SHALL accept a `startsAt` so a test or the harness operator can register an event that has **already
started**, **has not started yet**, or started in the **distant past**.

The mini-edge's `POST /events` SHALL read `startsAt` from the request body and SHALL reject a request
whose `startsAt` is absent or non-canonical with `400`, exactly as the real backend does — the mini-edge
being a faithful edge, not a lenient one. Its `GET /events/:eventId` SHALL return `startsAt` in the
marker body, and SHALL synthesize it from `createdAt` for a marker registered without one, mirroring the
real backend's legacy-marker read.

The world's canned `createdAt` deliberately carries **milliseconds** so the world is not "cleaner than
production". `startsAt` SHALL be the opposite: it SHALL be canonical (second-precision, no fraction),
because that is exactly what the real backend guarantees, and a world that emitted a fractional
`startsAt` would make the join gate's no-normalization path untestable.

#### Scenario: The world registers an event with a start date
- **WHEN** a test registers an event in the world with a given `startsAt`
- **THEN** `GET /events/:eventId` through the mini-edge returns that `startsAt` in the marker body

#### Scenario: The mini-edge rejects a non-canonical startsAt on create
- **WHEN** a `POST /events` reaches the mini-edge with an absent or non-canonical `startsAt`
- **THEN** it responds `400` and registers no event, faithfully to the real backend

#### Scenario: A world event registered without a start date synthesizes one
- **WHEN** an event is registered in the world with no `startsAt` and its details are fetched
- **THEN** the mini-edge returns `startsAt` equal to that marker's `createdAt`

#### Scenario: A not-yet-started world event uploads nothing
- **WHEN** the world holds an event whose `startsAt` is in the future, a device joins it, and the
  operator invokes an upload cycle over a gallery of photos
- **THEN** no object lands in the world's store and the ledger gains no entry — the floor admitting
  nothing (capability `photo-selection-policy`)

### Requirement: The world composes the real cycle rather than mirroring its assembly

The world SHALL drive an upload cycle by constructing the real cycle and invoking it, supplying the same
ports a composition root supplies. It SHALL NOT re-implement the roots' assembly — the membership decision,
the leave-side reconciliation, the engine construction, and the hook wiring — in harness code.

A hand-written mirror of a composition root drifts from it, and drifts silently: before the app-driven
tier's reconciliation was fixed, the world **already reconciled** on its mirrored path while the real tier
did not. A mirror that is more correct than production is worse than one that is wrong, because it stays
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
- **THEN** the cycle skips, the joined-event marker is intact, and the ledger, discovery cursor, and
  object store are untouched

#### Scenario: An absent membership still drives the leave path
- **WHEN** the world's membership is cleared and a cycle runs
- **THEN** the leave-side reconciliation runs and the joined-event marker is cleared

