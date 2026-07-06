# harness world model Specification

## Purpose

A controllable in-memory "world" (`:test:world`) that the REAL platform-agnostic stack — `SyncEngine`
+ `UploadCycle`, `ExtensionReconciler`, `DeviceManifestProducer`, `DownloadController`,
`OwnDeviceCompletedAssetsSource` + `ListingSyncStatusSource`, `CreateEvent` — runs against, so the
whole system (upload AND download) is observable and testable on JVM + `iosSimulatorArm64` without a
device. It provides a backend object store computing the edge's read-models faithfully (drift
accepted, no golden fixture), a Ktor `MockEngine` mini-edge over the four common-Ktor seams,
operator-driven upload/download job fakes, a one-own-plus-injectable-foreign device model, controllable
failure levers, and composition helpers mirroring the extension composition root. Consumed by BOTH the
desktop full-stack harness (`:app:desktop`) and `:test:integration`. Authoritative design:
docs/design.md §2.4 (status projection), §3.2/§3.3 (discovery, flow), §5.1 (desktop harness), §6
(testing rules).
## Requirements
### Requirement: Controllable in-memory world module

The system SHALL provide a test-infra Kotlin Multiplatform module `:test:world` that assembles the
**real** platform-agnostic stack (`SyncEngine` + `UploadCycle`, `ExtensionReconciler`,
`DeviceManifestProducer`, `DownloadController`, `OwnDeviceCompletedAssetsSource` +
`ListingSyncStatusSource`, `CreateEvent`) against controllable in-memory infrastructure. The module
SHALL declare targets `jvm()` and `iosSimulatorArm64` **only** (no `iosArm64` — it never links into a
shipped framework), so its logic and self-tests execute on **both** JVM and the iOS simulator per
testing rule 1. Its fakes and composition helpers SHALL live in `commonMain` (reusable infrastructure,
following the repo's `InMemory*`/`Mutable*` convention), and it SHALL be consumed by **both** the
desktop full-stack harness (`:app:desktop`) and the `:test:integration` module. Nothing in a
production `domain`/`capability` module SHALL depend on `:test:world` (the dependency edge is
one-directional, so no module cycle is introduced).

#### Scenario: Runs on JVM and the simulator

- **WHEN** the module's self-tests are run
- **THEN** they execute on `jvm()` and `iosSimulatorArm64` (the module declares no `iosArm64` target)

#### Scenario: Consumed by both the harness and integration tests

- **WHEN** the desktop harness and `:test:integration` each assemble a world
- **THEN** both reach the same `commonMain` fakes and composition helpers, and neither introduces a
  dependency from a production module back into `:test:world`

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

#### Scenario: Reconcile-seed listing is the per-device listing

- **WHEN** the rejoin reconciler and own-device status completeness each read a device's stored files
- **THEN** both consume the same per-device listing read-model (the world exposes it once)

### Requirement: MockEngine mini-edge over the four common-Ktor seams

The world SHALL expose a Ktor `MockEngine`-backed `HttpClient` — a "mini-edge" — that answers the
app-side metadata calls by dispatching on HTTP method + request path against the backend object store,
so the **real** common-Ktor seams run unmodified against it. The mini-edge SHALL route
`GET /files/devices/<id>` (per-device listing), `GET /events/<id>/files` (event-union; a `404` when the
event marker is absent), `POST /events` (a `201` `{ eventId, name, createdAt }` that registers the
marker), and `PUT /events/<id>/devices/<id>` (a `200` that deposits the manifest into the store), and
SHALL answer any unmatched request `404`. The same `HttpClient` SHALL be injected into the real
`HttpDeviceFilesSource`, `HttpEventUnionSource`, `HttpEventCreationClient`, and the module's common
`HttpDeviceManifestUploader`, mirroring the extension composition root's single shared client.

#### Scenario: Real seams round-trip against the mini-edge

- **WHEN** the real `HttpDeviceFilesSource`, `HttpEventUnionSource`, and `HttpEventCreationClient` are
  each given the mini-edge client and invoked
- **THEN** each parses a well-formed response computed from the backend object store (the listing, the
  union, and a minted event id respectively)

#### Scenario: A manifest PUT lands in the store

- **WHEN** the common `HttpDeviceManifestUploader` PUTs a manifest to `/events/<id>/devices/<id>` via the
  mini-edge
- **THEN** the manifest is deposited into the store and subsequently participates in the union
  completeness computation

#### Scenario: Event creation registers the marker

- **WHEN** `POST /events` is answered
- **THEN** a canonical event id is minted, the response is `201 { eventId, name, createdAt }`, and the
  event marker is registered so a subsequent union read is gated in (not 404)

### Requirement: Operator-driven, inspectable upload-job lifecycle

The world SHALL provide a fake `UploadJobPlatform` that models the OS upload-job lifecycle as an
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

The world's fake `UploadJobPlatform.discoverResources(sinceToken)` SHALL derive its change feed from the
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

The world SHALL provide fake `PhotoDownloadJobs` and `PhotoLibraryImporter` seams. `PhotoDownloadJobs`
SHALL record enqueued downloads inspectably; an operator **stage** action SHALL resolve a resource's
synthetic `url` against the backend store and drive the controller's staging callback, while a
non-staged download SHALL simply remain PENDING for retry — there is **no** `DownloadError` type and no
terminal transfer-failure state. `PhotoLibraryImporter.import` SHALL import the asset into the in-memory
gallery (so it enters gallery enumeration) and mark the download store imported, so the imported asset's
id enters `suppressedLocalIds()`; a settable import-failure SHALL yield `ImportResult.Failed`. Because
the real `UploadCycle.suppressedAssetIds` and `OwnDeviceCompletedAssetsSource` consult that suppression
set, a foreign asset that is downloaded and imported SHALL NOT be re-uploaded by the own-device cycle.

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
status keep-last-good path and the download union-failure path), the **job-limit** (`LIMIT_EXCEEDED`),
a **per-job `UploadError`** on the upload retry chain, and an **import failure** (`ImportResult.Failed`).

#### Scenario: Backend-offline keeps last-good status and fails the union

- **WHEN** the backend-offline switch is set and the status source refreshes and the download
  controller reconciles
- **THEN** the listing-backed status keeps its last-good completed set and the download union read
  returns a failed `Result` (no partial import)

#### Scenario: Each lever drives its real path

- **WHEN** the job-limit, a per-job `UploadError`, or an import failure is armed and the corresponding
  cycle runs
- **THEN** the real orchestration responds (deferred cycle, engine retry with incremented attempt, or a
  non-terminal import failure respectively)

### Requirement: Real-stack composition helpers

The world SHALL provide composition helpers that assemble the real stack against the world's fakes for a
given world state, mirroring the extension composition root (`UploadExtensionRoot.process()`): the
upload-cycle path (real `SyncEngine` + `EdgeUploadRequestProvider` + `UploadCycle`, driven by a
`process()`-shaped runner: reload config → reconcile → build config → run cycle), the reconcile +
manifest path (real `ExtensionReconciler` over `HttpDeviceFilesSource` + real `DeviceManifestProducer`
over the common `HttpDeviceManifestUploader`), the download path (real `DownloadController` over
`HttpEventUnionSource`), the listing-backed status path (real `OwnDeviceCompletedAssetsSource` +
`ListingSyncStatusSource`), and the create-event path (real `CreateEvent` over `HttpEventCreationClient`).
Only the platform edges (`UploadJobPlatform`, `PhotoDownloadJobs`, `PhotoLibraryImporter`), the storage
seams, and the HTTP client SHALL be fakes; everything above them SHALL be the shipped production code.

#### Scenario: The composed upload path exercises the real cycle

- **WHEN** the upload-cycle helper assembles the stack and its runner is invoked
- **THEN** the real `SyncEngine`, `EdgeUploadRequestProvider`, and `UploadCycle` run, and only the job
  platform, discovery store, ledger backend, and HTTP client are fakes

#### Scenario: Production seams are not modified

- **WHEN** the world composes the manifest path
- **THEN** it uses a common `HttpDeviceManifestUploader` living in `:test:world`, leaving production's
  `IosDeviceManifestUploader` and the `device-manifest` seam home unchanged

### Requirement: Integration tests assert UiState and world outcomes

The `:test:integration` module SHALL consume `:test:world` and `:domain:presentation` to assert both the
projected `UiState` **and** world outcomes from world mutations and cycle invocations — not `UiState`
alone. World outcomes SHALL include: objects landed in the backend store (the per-device listing grows),
ledger rows reaching `COMPLETED`, and foreign photos imported into the in-memory gallery. This is the
testing-rule-3 seam ↔ UI-state integration surface, now spanning the real upload/download execution edge
rather than injected `SyncEvent`s alone, and it SHALL run on JVM and `iosSimulatorArm64`.

#### Scenario: A completed upload advances both UiState and the store

- **WHEN** an asset is added, its job created and completed, and the cycle plus a status refresh run
- **THEN** the projected `UiState` advances toward `Completed` **and** the object is present in the
  per-device listing with a `COMPLETED` ledger row

#### Scenario: A foreign download imports and is observable

- **WHEN** a foreign device's complete asset is reconciled, staged, and imported
- **THEN** the imported asset is present in the in-memory gallery and (via suppression) is not
  re-uploaded, and the outcome is assertable at the store/gallery level alongside `UiState`

### Requirement: Faithful leave composition helper

The world SHALL provide a `leave()` composition helper that runs the **real** leave edge —
`DownloadController.onLeaveOrSwitch()` (cancel in-flight transfers, prune non-terminal download rows)
followed by clearing the config cell and the joined-event marker — while **retaining** imported
foreign photos, deposited objects, the gallery, and the ledger. It SHALL NOT be modelled by rebuilding
the world (which would forge the outcome and wrongly discard imported photos). Because clearing the
config cell is reactive, the listing-backed status projection SHALL leave the joined layer without any
world rebuild, and re-provisioning the same event afterwards SHALL still find the previously imported
foreign assets suppressed (real cross-event dedup). This mirrors the extension/app leave use-case in
the same "real stack over the world's fakes" posture as the other composition helpers.

#### Scenario: Leave keeps imported photos and clears the join

- **WHEN** a foreign asset has been downloaded and imported, and `leave()` is then invoked
- **THEN** the real `onLeaveOrSwitch()` runs, the config cell and joined-event marker are cleared, and
  the imported asset remains enumerable in the gallery (it is not discarded)

#### Scenario: Re-provisioning after leave still suppresses the import

- **WHEN** the same event is re-provisioned after `leave()`
- **THEN** the previously imported foreign asset is still in `suppressedLocalIds()` and the own-device
  cycle does not re-upload it

