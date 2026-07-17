# harness-world-model — delta for port-need-renames

## MODIFIED Requirements

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

### Requirement: Real-stack composition helpers

The world SHALL provide composition helpers that assemble the real stack against the world's fakes for a
given world state, mirroring the extension composition root (`UploadExtensionRoot.process()`): the
upload-cycle path (real `SyncEngine` + `EdgeUploadRequestProvider` + `UploadCycle`, driven by a
`process()`-shaped runner: reload config → reconcile → build config → run cycle), the reconcile +
manifest path (real `ExtensionReconciler` over `HttpDeviceFilesSource` + real `DeviceManifestProducer`
over the common `HttpEnrollment`), the download path (real `DownloadController` over
`HttpEventUnionSource`, and the real `QueuedPhotoDownloadJobs` over a fake `DownloadTransport`), the
ledger-backed status path (real `OwnDeviceGalleryStatusSource` + `LedgerBackedSyncStatusSource` over the
world's real ledger), and the create-event path (real `CreateEvent` over `HttpEventCreation`).
Only the platform edges (`BackgroundTransfer`, `DownloadTransport`, `PhotoLibraryImporter`), the storage
seams, and the HTTP client SHALL be fakes; everything above them SHALL be the shipped production code.

#### Scenario: The composed upload path exercises the real cycle

- **WHEN** the upload-cycle helper assembles the stack and its runner is invoked
- **THEN** the real `SyncEngine`, `EdgeUploadRequestProvider`, and `UploadCycle` run, and only the job
  platform, discovery store, ledger backend, and HTTP client are fakes

#### Scenario: Production seams are not modified

- **WHEN** the world composes the manifest path
- **THEN** it uses a common `HttpEnrollment` living in `:test:world`, leaving production's
  `IosEnrollment` and the `device-manifest` seam home unchanged
