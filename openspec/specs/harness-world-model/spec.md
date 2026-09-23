# harness world model Specification

## Purpose

A controllable in-memory "world" (`:test:world`) that the REAL platform-agnostic stack — `SyncEngine`
+ `UploadCycle`, the join-time `ShareSetLoad`, `DeviceManifestProducer`, `DownloadController` +
`QueuedPhotoDownloadJobs`, `OwnDeviceGalleryStatusSource` + `LedgerBackedSyncStatusSource`,
`CreateEvent` — runs against, so the whole system (upload AND download) is observable and testable on
JVM + `iosSimulatorArm64` without a device. It provides a backend object store computing the edge's read-models faithfully (on every
route a backend port contract covers, held to the real `api/` edge by those contracts; elsewhere drift is
accepted, with no golden fixture), a Ktor `MockEngine` mini-edge serving both device-API versions over the real common-Ktor seams,
operator-driven upload/download job fakes, a one-own-plus-injectable-foreign device model, controllable
failure levers, and composition helpers mirroring the extension composition root. Consumed by BOTH the
desktop full-stack harness (`:app:desktop`) and `:test:integration`.

It exists because the code that most needs coverage — the upload cycle's adjudication, the join-time load,
the download echo-suppression — is exactly the code that ran only inside an iOS extension that cannot be
tested on a simulator. Faking the *execution edge* rather than the logic lets the real stack run anywhere,
which is what makes the standing target rule (capability `testing-architecture`, "Every test runs on
every target its module declares") achievable for orchestration and not just for pure functions.

Decision record: `changes/archive/2026-07-03-add-harness-world-model`.

Decision record for the mini-edge as the backend contracts' `Fake`: `changes/archive/2026-09-23-contract-backend-clients`.
The world composes attestation because `AppPorts` requires the seams, and leaves it **inert by default** —
`isSupported()` is false, so a refresh returns early without attesting, exactly as it does in the upload
extension and on a simulator. An opt-in lever turns it on for the tests that need a credential *change* to
happen at all. Two backend behaviours it still does not model, stated so they are not assumed: the token
gate itself, and the `401` a device-scoped write answers when the backend holds no attestation record.
Decision record for its seam, failure, state and concurrency rules: `changes/archive/2026-09-23-harden-seam-bug-classes`.

## Requirements
### Requirement: Controllable in-memory world module

The system SHALL provide a test-infra Kotlin Multiplatform module `:test:world` that runs the
**real** platform-agnostic stack against controllable in-memory infrastructure: the honest
in-memory port implementations SHALL live in `:adapter:generic:fake` (package `app.snapsync.fake`; spec
`module-architecture`), and `:test:world` SHALL hold the **operator rigging** around them — the
backend store, the mini-edge, the levered fakes of ports no contract binds yet (`FakeBackgroundTransfer`,
`FakeDownloadTransport`) and the wrappers that own the honest fakes' state cells and carry the operator's
levers over them (`WorldGallery`, `RecordingDownloadStore`, and the wrappers over the honest upload-discovery,
album-manager, photo-importer and photo-access fakes) — per the fake-honesty gate (`architecture-guards`).
A port that a contract binds (capability `port-contracts`) SHALL be doubled in the world by its honest,
contract-bound fake, wrapped; the world SHALL NOT keep a second, levered implementation of that port, whose
behaviour no contract would hold to the real adapter's.
The module SHALL declare targets `jvm()` and `iosSimulatorArm64` **only** (no `iosArm64` — it
never links into a shipped framework), so its logic and self-tests execute on **both** JVM and the
iOS simulator per capability `testing-architecture` ("Every test runs on every target its module
declares"). It SHALL NOT host port contracts: those live in `:test:contracts` (capability
`port-contracts`), which is also the only test-infra module the device app may link. It SHALL be consumed by **both** the desktop full-stack harness
(`:app:desktop`) and the `:test:integration` module. Nothing in a production `domain`/`adapter`
module's **main** source sets SHALL depend on `:test:world`.

#### Scenario: Runs on JVM and the simulator

- **WHEN** the module's self-tests are run
- **THEN** they execute on `jvm()` and `iosSimulatorArm64` (the module declares no `iosArm64` target)

#### Scenario: Consumed by both the harness and integration tests

- **WHEN** the desktop harness and `:test:integration` each assemble a world
- **THEN** both reach the same world class over the same `:adapter:generic:fake` doubles, and no production
  main source set gains a dependency back into `:test:world`

#### Scenario: Rigging cannot live in a fake

- **WHEN** an operator lever (a settable cell, a failure switch, an inspection list) is needed on an
  honest `:adapter:generic:fake` double
- **THEN** it is expressed in a `:test:world` wrapper owning the fake's constructor-injected state,
  never as a public member of the fake (the fake-honesty gate fails otherwise)

#### Scenario: A contracted port's double in the world

- **WHEN** the world needs a failure lever on a port whose honest fake a contract binds, such as the album
  manager
- **THEN** the lever lives on a world wrapper around that fake, and the fake answering beneath it is the one
  the contract holds to the real adapter

### Requirement: Backend object store with faithful read-models

The world SHALL provide an in-memory backend store holding the edge's state: deposited object keys per
device byte-partition (`files/devices/<deviceId>/<filename>`), and the **relational** state the real
backend keeps — events, per-`(eventId, deviceId)` memberships each carrying an `active`/`departed` state,
each membership's asset set, and the device-scoped resources with their `uploaded` flag. From this state it
SHALL compute the edge's read-models **faithfully in behavior** — the per-device file listing
(`GET /files/devices/<id>`), the event-wide union (`GET /events/<id>/files`), and the join-load
listing — where the join-load listing is the **same** per-device read-model the join-time share-set load
consumes (capability `upload-state-reconciliation`). Byte-level fidelity to the real Deno `api/` edge is **NOT** required, and the store SHALL NOT mint
real presigned S3 URLs (each `url` is a
synthetic in-memory handle the fake download seams resolve store-direct).

The per-device listing SHALL return one `{filename, url}` entry per resource recorded as uploaded. The
event-union SHALL span a device's memberships whether `active` or `departed`, include an asset **only when
every** resource that asset names is recorded as uploaded, tag each asset with its owning `deviceId`, and
gate on event existence (an unregistered event is absent, not empty).

Behavioural fidelity on every route a backend port contract covers (capability `port-contracts`) SHALL
NOT be a matter of accepted drift: the mini-edge, serving this store, SHALL be bound as those contracts'
`Fake`, and the real `api/` edge passing the same clauses is its reference. A clause the real edge passes
and the mini-edge fails is a defect in the mini-edge, fixed there. A state the mini-edge does not model is
declared unreachable by its binding and never simulated. Drift on routes and fields no contract covers
remains accepted, with no golden fixture.

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

#### Scenario: The mini-edge diverges from the real edge on a contracted route

- **WHEN** a backend contract clause passes against the real `api/` edge and fails against the mini-edge
- **THEN** the build fails naming the clause and the mini-edge binding, and the fix is made in the
  mini-edge rather than by declaring the clause's state unreachable

### Requirement: MockEngine mini-edge over the four common-Ktor seams

The world SHALL expose a Ktor `MockEngine`-backed `HttpClient` — a "mini-edge" — that answers the
app-side metadata calls by dispatching on HTTP method + request path against the backend object store,
so the **real** common-Ktor seams run unmodified against it. It SHALL answer any unmatched request `404`.
The same `HttpClient` SHALL be injected into the real `HttpDeviceFilesSource`, `HttpEventUnionSource`,
`HttpEventCreation`, and the module's common enrollment and manifest seams, mirroring the extension
composition root's single shared client.

The mini-edge SHALL serve **both** device-API versions side by side, for as long as the real backend does.
It SHALL split a leading `/api/vN` off the request path — defaulting to v1 when the path carries none —
and route on the remainder, mirroring the backend's own version split. Serving only the newer version
would be a harness that models a backend that does not exist, and would break every seam that has not yet
moved; a world in which the client and the backend can only ever move together cannot exercise the
crossing this capability exists to make testable.

Under **v1** the mini-edge SHALL keep its existing behaviour unchanged, including that a manifest publish
to `PUT /events/<id>/devices/<id>` also marks the membership active — v1 is frozen, and its publish really
does reactivate.

Under **v2** the mini-edge SHALL model the **separation of joining from contributing**, because the device
code under test depends on it: a **bodyless** `PUT /events/<id>/devices/<id>` creates or reactivates the
membership and is the only route that may refuse enrolment at capacity, while
`PUT /events/<id>/devices/<id>/manifest` replaces the membership's asset set, leaves its state untouched,
and enrolls nobody. A v2 manifest publish from a device holding no membership SHALL be refused rather than
creating one — modelling it as a create would let a device pass in the harness and fail against the real
backend, which is the one divergence this world exists to make impossible.

The v2 manifest publish SHALL also model the backend's **ordering by manifest version** (capability
`api-endpoints`, "The v2 manifest publish is ordered by its version"): a publish whose version is strictly
older than the membership's stored one changes nothing and is still answered as a success; an equal or newer
one replaces the asset set and records its version; a versionless one replaces it and clears the stored
version; and the v2 join clears it. The world counts applied and refused publishes separately, so a test can
tell "refused as older" from "not published". Without it, a crossed pair that the real backend refuses would
overwrite in the harness, and the device's handling of the refusal could not be tested.

The direct manifest **injection helper** used to set up foreign devices is not a route and SHALL keep
creating an active membership; constraining it would make test setup model an enrolment flow it is not
exercising.

The per-device listing SHALL answer in **identity terms** under v2 — each entry carrying the asset
identity, the resource role, and a capture filename — and SHALL mint no download URL, while keeping the
object-name shape under v1. Serving one shape for both would let a client that misreads the field pass
every test, because the two shapes carry a field of the same name meaning different things. The world does
not model a capture name distinct from the storage key, and SHALL answer with the key: a client consumes
only that value's extension, which the two share.

Under **v2** the mini-edge SHALL also accept the **byte upload** the app's uploader addresses —
`PUT /files/devices/<deviceId>/<assetId>/<role>?filename=<capture name>` — refusing an unknown role or a
missing filename `400`, and storing the object under the key the real backend composes for that resource,
answered `201`. The world's own uploader keeps depositing store-direct, because it plays the operating
system's transfer; the route exists so a caller that enters "a device holds uploads" through the edge's
public surface — the backend port contracts' setup (capability `port-contracts`) — reaches the same state
on the mini-edge as on the real backend.

The mini-edge SHALL be able to enforce the **version gate**: when armed, a v2 request that declares no app
version, or one below the configured minimum, SHALL be refused `426` with the minimum in the body, so the
client's handling of that refusal is exercisable without a backend (capability `min-app-version`). It
SHALL be **off by default** and armed by an operator lever — a gate that refused by default would fail
every seam that does not yet declare a version, which is all of them until the client half ships.

#### Scenario: Real seams round-trip against the mini-edge

- **WHEN** the real `HttpDeviceFilesSource`, `HttpEventUnionSource`, and `HttpEventCreation` are
  each given the mini-edge client and invoked
- **THEN** each parses a well-formed response computed from the backend object store (the listing, the
  union, and a minted event id respectively)

#### Scenario: Both versions are served side by side

- **WHEN** the same logical call is made under the v1 prefix and under the v2 prefix
- **THEN** each is routed to that version's behaviour, and a path carrying no prefix is served as v1

#### Scenario: A v2 join creates the membership and writes no manifest

- **WHEN** the bodyless join route is called under v2 for an event and device
- **THEN** the membership exists and its asset set is unchanged, so a device that had contributed before
  still participates in the union with no republish

#### Scenario: A v2 manifest publish lands in the store

- **WHEN** the manifest seam publishes to the v2 manifest sub-resource for a device holding a membership
- **THEN** the manifest is deposited into the store and subsequently participates in the union
  completeness computation

#### Scenario: A v2 manifest from a non-member is refused

- **WHEN** the v2 manifest sub-resource is called for a device that holds no membership in that event
- **THEN** the request is refused and no membership is created as a side effect

#### Scenario: An older v2 manifest is refused as a success

- **WHEN** a member holding manifest version 9 publishes version 7 to the v2 manifest sub-resource
- **THEN** the request succeeds, the asset set and the stored version are unchanged, and the world counts one
  refused publish and no applied one

#### Scenario: A v2 manifest does not reactivate a departed member

- **WHEN** a departed member publishes to the v2 manifest sub-resource
- **THEN** the asset set is replaced and the membership stays departed, unlike the v1 publish

#### Scenario: The v1 publish still enrols

- **WHEN** a manifest is published to the v1 route
- **THEN** it is deposited and the membership is marked active, exactly as before this change

#### Scenario: The listing answers in identity terms under v2

- **WHEN** the per-device listing is read under the v2 prefix
- **THEN** each entry carries the asset identity, the role and a capture filename, and no entry carries a
  minted download URL — while the v1 prefix still answers with object names

#### Scenario: The version gate is off until armed

- **WHEN** a v2 request reaches the mini-edge with no app-version declaration and the gate has not been
  armed
- **THEN** it is served normally, so seams that do not yet declare a version are unaffected

#### Scenario: An armed gate refuses a request declaring no version

- **WHEN** the gate is armed and a v2 request declares no app version, or one below the configured minimum
- **THEN** it is refused `426` carrying the minimum, so the client's update-required handling is exercised

#### Scenario: Event creation registers the marker

- **WHEN** `POST /events` is answered
- **THEN** a canonical event id is minted, the response is `201 { eventId, name, createdAt }`, and the
  event marker is registered so a subsequent union read is gated in (not 404)

#### Scenario: A v2 byte upload is listed and completes an asset

- **WHEN** a device uploads a resource through the v2 byte route and has published a manifest declaring
  only that resource for an asset
- **THEN** the per-device listing carries the resource, and the union lists the asset as complete

### Requirement: Operator-driven, inspectable upload-job lifecycle

The world SHALL provide a fake `BackgroundTransfer` that models the OS upload-job lifecycle as an
operator-driven, **inspectable** queue implementing every seam method. Like the device transports, it SHALL
receive the ledger only as a `TransferRecord` (`sync-ledger`) and SHALL record each terminal outcome through
its guarded `markTerminal`; it SHALL serve no library read. Like every transport, it SHALL expose no capacity
read and no live-set, lost-set or discard member: the cycle creates until `createJob` refuses, and no cycle
reconciles stranded rows (decision record: `changes/both-uploaders-active`).
`createJob` SHALL enqueue a PENDING job and return `CREATED`, unless a **settable job-limit** is reached
(returning `LIMIT_EXCEEDED`) or a forced create-failure is set (returning `FAILED`). An operator **complete**
action SHALL deposit the job's object key into the backend object store **store-direct** (byte transfer
is not routed through ktor) and move the job to the acknowledge bucket, so the next cycle records it
`COMPLETED`. An operator **fail** action SHALL move the job to the retry bucket carrying a chosen engine
`UploadError` (`Network`, `Http`, `Cancelled`, or `Unknown`), driving the real engine retry chain: the
engine answers `Retry` with a freshly minted request and the job is re-created. The engine carries no attempt
count (capability `sync-engine`), so the queue records its own per-key count of the creations it observed. The
queue's pending/retry/acknowledge buckets and per-key creation count SHALL be
inspectable so tests assert the lifecycle, not only the final outcome.

#### Scenario: Complete deposits the object and the ledger records COMPLETED

- **WHEN** the operator completes a created job and the next upload cycle runs
- **THEN** the object key is present in the backend store and the ledger holds a `COMPLETED` row for it

#### Scenario: Fail drives the real retry chain

- **WHEN** the operator fails a created job with a chosen `UploadError` and the next cycle runs
- **THEN** the engine answers `Retry`, the job is re-created, and the queue's creation count for that key rises

#### Scenario: Job-limit truncates creation but not the cycle

- **WHEN** the job-limit is set below the number of `Work` resources in a cycle
- **THEN** `createJob` returns `LIMIT_EXCEEDED`, the cycle returns `PROCESSING`, the un-enqueued
  resources hold `DISCOVERED` rows, and the cycle still
  published its device manifest

#### Scenario: The job-limit is the only bound on creation

- **WHEN** the world's upload cycle enqueues more admitted rows than the settable job-limit allows
- **THEN** it creates jobs until `createJob` returns `LIMIT_EXCEEDED` and stops that pass there, having read
  no capacity from the queue

#### Scenario: The fake queue holds no ledger store

- **WHEN** the world constructs its fake job queue
- **THEN** it is given the world's ledger as a `TransferRecord`, exactly as a device transport is

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

An operator action SHALL be complete when it returns. `onStaged` **is** a suspend seam: the delegate
thread still must not be blocked by an import, so `QueuedPhotoDownloadJobs` owns the launch and tracks it,
and the world SHALL await those tracked imports — via the feature's own `awaitOutstandingImports` — before
the stage action returns. Otherwise every download assertion in the world becomes a race, which is the
opposite of what an operator-driven harness is for.

The world SHALL NOT re-install `onStaged` to obtain that guarantee. It previously did, because the seam was
non-suspend and the composition's fire-and-forget launch left the work unreachable — the same
unreachability that let the app's background-session handler be released while its imports were only queued
(capability `ios-app-shell`). Now that the feature tracks its own launches, the harness runs the production
wiring unshadowed: one fewer place it can diverge from the app.

`PhotoLibraryImporter.import` SHALL import the asset into the in-memory gallery (so it enters gallery
enumeration) and mark the download store imported, so the imported asset's id enters `suppressedLocalIds()`;
a settable import-failure SHALL yield `ImportResult.Failed`. The fake importer SHALL name each
imported resource through the **same** shared rule the iOS importer applies (`importFilename`,
capability `photo-download`), so the world can never show a human filename where a device would show
a storage object key. A fake that is *more* correct than production is the failure mode this clause
exists to close: the fake applied the published name directly while the device let PhotoKit name the
resource after its staged file, and that divergence hid the wrong name from `:test:integration`
entirely. Because the real `UploadCycle.suppressedAssetIds` and `OwnDeviceGalleryStatusSource` consult
that suppression set, a foreign asset that is downloaded and imported SHALL NOT be re-uploaded by the
own-device cycle.

#### Scenario: A downloaded-and-imported asset is suppressed from re-upload

- **WHEN** a foreign asset is discovered via the union, staged, and imported into the gallery, and the
  own-device upload cycle then runs
- **THEN** the imported asset appears in `suppressedLocalIds()` and the cycle creates no upload job for
  it (echo-suppression holds)

#### Scenario: A non-staged download stays pending

- **WHEN** an enqueued download is not staged by the operator
- **THEN** its resource remains PENDING for retry and no terminal failure is recorded

#### Scenario: An imported asset carries the naming production would give it

- **WHEN** a foreign asset whose manifest publishes a human filename is staged and imported
- **THEN** the gallery asset it creates carries that filename, derived through the same shared
  rule the device applies

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

#### Scenario: The harness does not shadow the composed staged-resource hook

- **WHEN** the world is constructed
- **THEN** `downloadJobs.onStaged` is the hook `snapSyncApp` installed, not a world-local replacement

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
in-memory ports (the config ports as `:adapter:generic:fake`'s `InMemoryConfigStore` over the world's
config cell, with the `membershipUnreadable` lever a world property over the fake's readable cell — the
lever lives in the world, never in the fake; the fake `BackgroundTransfer`, the fake `UploadDiscovery`, the
`:adapter:generic:fake` ledger and manifest stores, the mini-edge HTTP seams) and `uploadCore` builds the real
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
  platform, library discovery, config and manifest stores, ledger backend, and HTTP client are fakes

#### Scenario: A wiring difference from production is impossible

- **WHEN** the world and a device tier each assemble an upload cycle
- **THEN** both call the same `uploadCore` function over different port implementations, so the world
  cannot carry gate, manifest, or policy wiring production lacks (or vice versa)

#### Scenario: The world's config double is the contracted fake

- **WHEN** the world composes its config ports
- **THEN** they are one `InMemoryConfigStore`, the fake held to the same config contract the real App-Group
  file store passes (capability `port-contracts`), so an unreadable membership answers the world as a real
  unreadable store would — reads unavailable, writes refused

#### Scenario: Production seams are not duplicated

- **WHEN** the world composes the manifest path
- **THEN** its `Enrollment` port is `:adapter:generic:app`'s `HttpEnrollment` over the injected mini-edge
  client — the world carries no copy of any production adapter (the step-10 death of the world's
  byte-identical `HttpEnrollment` closed the deletion ledger's last row)

### Requirement: The world composes the app graph through snapSyncApp

The world SHALL hold the app-side graph as a real `AppCore` produced by the **same** `snapSyncApp`
composition the iOS app shell calls (spec `module-architecture`, "One shared composition"),
constructed over an `AppPorts` whose ports are the world's fakes and mini-edge seams. The world SHALL
bind no `AppPorts` field to a body of its own that stands in for core machinery: the provision a join
performs, the attestation refresh and the push registration are built by `snapSyncApp` and run for real in
the world, exactly as on iOS. The world's operator surface is its own levers **beside** the composed core
(the operator `provision()`, `leave()`, the inject/fail levers), never a second body for a seam the core
calls: `onEventMinted` is a routing hook whose default provisions the minted event through the composed
Provision flow (the desktop inspector points it at the status host's pending-join gate), and the
upload producer is inert (nothing auto-runs — the operator plays the OS).
The world's exposed download controller, status sources, creation status, join use-case, and
user-tap command bundle SHALL be `AppCore`'s instances — never world-local rebuilds — so a wiring
difference between the harness and the app shell is impossible rather than undetected.

**One** named deviation is permitted, an operator-synchronicity concern and nothing else: the world's
operator `leave()` MAY remain a synchronous faithful edge beside the bundle's production-ordered leave
(whose backend notify is fire-and-forget by design); tests driving the bundle's leave await the backend
outcome.

The former second deviation — re-installing the composed `downloadJobs.onStaged` hook with an identical
body plus Job retention — is **withdrawn**. It existed only because the seam was non-suspend and the
composition discarded the Job; the feature now tracks its own launches, so the harness has nothing left to
re-install and the permission would only license a divergence nobody needs.

#### Scenario: The harness's app graph is the production graph

- **WHEN** the world harness or an integration test fires a user-tap command (create, commit-join,
  leave)
- **THEN** the command runs through `AppCore.userCommands` — the same compose-built bundle the iOS
  shell injects — over the world's ports, and its effects land in the world's fakes and mini-edge

#### Scenario: A join in the world runs the real Provision flow

- **WHEN** an integration test commits a join through the command bundle
- **THEN** the join's provision step runs the composed `flow/Provision` — membership entry, upload
  transition, push registration, album ensure, status refresh and download reconcile — with no world-local
  body in its place

#### Scenario: The world cannot rebuild what the composition owns

- **WHEN** the world or its inspector needs a status source, download controller, or join use-case
- **THEN** it reads the composed `AppCore`'s instance; no second assembly of a feature graph exists
  in harness code

### Requirement: Integration tests assert UiState and world outcomes

The `:test:integration` module SHALL consume `:test:world` and `:ui:presentation` (re-homed from
`:domain:presentation` at migration step 9) to assert **world outcomes** from world mutations and cycle
invocations — never injected `SyncEvent`s alone. World outcomes SHALL include: objects landed in the
backend store (the per-device listing grows), ledger rows reaching `COMPLETED`, and foreign photos
imported into the in-memory gallery. Where the seam under test reaches presentation, the test SHALL
**also** assert the projected `UiState`; where it does not, the world outcomes are the complete
assertion — an exclusion is proved by the absence of bytes, of a ledger row, and of a manifest entry,
none of which is a `UiState`. This is the seam-to-UI-state integration surface owned by capability
`testing-architecture`, spanning the real upload/download execution edge, and it SHALL run on JVM and
`iosSimulatorArm64`.

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

### Requirement: Mini-edge leave cascade

The `:test:world` mini-edge SHALL answer `DELETE /events/<eventId>/devices/<deviceId>` with the same
effect the real backend has: set that membership's state to `departed`. Its assets SHALL be retained, so
the union keeps serving what the device shared before it left, and the mini-edge notify fan-out SHALL
exclude it. The call SHALL be idempotent, and a leave naming a membership that never existed SHALL change
nothing rather than fail.

The mini-edge SHALL NOT model the retired sibling-object cascade — the rename to `<deviceId>.left.json`,
the last-write-wins resolution over sibling write times, or the leave-time reap of a freed device's bytes
and config. Membership is one record with a state (capability `database`), and reclamation belongs solely
to the nightly sweep (capability `scheduled-cleanup`); a harness that still performed a leave-time GC
would let an integration test pass against a cascade the backend no longer runs.

#### Scenario: A leave departs the membership and keeps its assets

- **WHEN** the mini-edge receives `DELETE /events/<eventId>/devices/<deviceId>` for the last active member
- **THEN** that membership's state becomes `departed`, its assets remain, and neither the event nor the
  device's bytes or config are deleted

#### Scenario: Mini-edge union keeps a departed device, notify drops it

- **WHEN** a device's membership state is `departed` while the event has other members
- **THEN** the mini-edge union includes that device's assets and the mini-edge notify fan-out excludes it

### Requirement: The world's event marker carries a start date

The world's backend object store SHALL model the event marker as `{ eventId, name, createdAt, startsAt }`
— the same four fields the real marker carries (capability `api-endpoints`) — and its registration seam
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

### Requirement: The mini-edge answers the event rename

The `:test:world` mini-edge SHALL answer `PATCH /events/<eventId>` with the same faithfulness as the
real backend route (capability `event-rename`), so the integration tests exercise the shipped
`EventRename` client against a route that behaves like the one it will meet in production.

It SHALL validate `name` by the **same** rule the mini-edge's `POST /events` applies — trimmed,
non-empty, at most 100 characters — answering `400` otherwise; it SHALL answer `404` for an event that
is not registered; and it SHALL answer `502` while the backend-offline lever is set, like every other
routed read.

On success it SHALL replace **only** the event's name, leaving its `startsAt`, `endsAt`, and every other
registered fact untouched, and SHALL respond `200` with the **same** event-details shape
`GET /events/<eventId>` serves. Both routes SHALL build that response from one place, so a rename's echo
can never drift from the details fetch that follows it.

Validation SHALL precede the existence check, matching the real route's order, so a bad name against a
missing event is a `400` in both.

#### Scenario: A valid rename rewrites only the name
- **WHEN** the mini-edge receives `PATCH /events/<eventId>` carrying a valid name for a registered event
- **THEN** it responds `200` with the event's details carrying the trimmed new name, and the registered
  `startsAt` and `endsAt` are unchanged

#### Scenario: An invalid name is refused
- **WHEN** the request carries a name that is absent, empty, whitespace-only, or over 100 characters
- **THEN** the mini-edge responds `400` and the registered name is unchanged

#### Scenario: An unregistered event is a 404
- **WHEN** the request targets an event the world never registered, or one the sweep removed
- **THEN** the mini-edge responds `404` and registers nothing

#### Scenario: The offline lever applies
- **WHEN** the backend-offline lever is set and a rename arrives
- **THEN** the mini-edge responds `502`, exactly as it does for the routed reads

#### Scenario: The rename echo matches the details fetch
- **WHEN** a rename succeeds and `GET /events/<eventId>` is then requested
- **THEN** both responses carry the same name and the same event facts

### Requirement: The world's marker write is a required collaborator

The marker write the world's importer performs SHALL be a **required** collaborator of that importer, with
no default.

A no-op default makes an importer that never records a marker look like a working one: the row stays
importable, so every later pass imports the asset again while reporting success — an unbounded duplicate
generator presented as a healthy path (capability `download-store`). This is a safety-relevant collaborator
and takes the same posture as every other one in this project: supplied explicitly, or not at all.

#### Scenario: A world importer cannot be built without its marker write

- **WHEN** the world's importer is constructed
- **THEN** the marker write must be supplied, rather than defaulting to a no-op

### Requirement: Full-enumeration discovery driven by the in-memory gallery

The world SHALL provide a fake `UploadDiscovery` whose `discover(policy)` is a **full enumeration** of the
in-memory gallery, read through the honest `InMemoryCandidateSource`. That source answers the single
`CandidateSource.candidates(policy)` read over the world-owned asset cell, and its candidates map their
resources through the real shared fan-out when the cycle asks for them. Every readable discovery SHALL
report `Discovery.fullEnumeration = true`. There is no change feed and no token: an added asset appears in
the next discovery's candidates, and a removed asset is simply absent from it, which is exactly the evidence
the cycle's presence diff consumes (capability `sync-ledger`, "Deletion is a presence diff over an
authoritative walk").

The walk SHALL be narrowed the way the device's fetch is narrowed, and no further: by the rules a platform
predicate can express, returning a superset of the policy's capture window. It SHALL NOT be narrowed by the
full admission. On a device the fetch predicate cannot express the id-set exclusions or the resolution
floors, so an asset they exclude is still **present** in the walk. A fake that applied the whole admission
would make such an asset absent, and the cycle would delete its rows: in the harness alone, a narrowing
would become a deletion. That is the same false-deletion shape the retired reconcile shipped on device.

The same fake SHALL resolve ledger keys to uploadable resources (capability `ios-url-session-upload`) from the
world's in-memory gallery, and both reads SHALL be observable, so a test can assert **which keys** a cycle
resolved and **how many** discoveries it made. A key whose asset the operator has removed from the gallery
SHALL resolve to nothing.

The world SHALL provide an operator **unreadable-walk** lever that makes the next discovery answer as a
device does for an unreadable library: no candidates, and `fullEnumeration = false`. It is the case the
authoritative gate exists for, and without a lever no test could show that an unreadable walk deletes
nothing.

#### Scenario: Adding an asset yields a new candidate

- **WHEN** an asset is added to the in-memory gallery and discovery runs
- **THEN** `Discovery.candidates` carries that asset, and its resources fan out when the cycle asks

#### Scenario: Removing an asset deletes its in-window rows

- **WHEN** an asset whose in-window rows are `COMPLETED` is removed from the gallery and a cycle runs
- **THEN** the discovery no longer carries it, and the cycle deletes its rows, so it stops counting and
  stops being listed

#### Scenario: Narrowing the policy is not a removal

- **WHEN** the membership's capture-date cutoff is raised so assets still present in the gallery fall
  outside it, and a cycle then runs
- **THEN** those assets' ledger rows are kept: they are outside the walk's window, so the world shows the
  narrowing exactly as a device does, as rows the enqueue admission declines to upload rather than rows the
  walk retracted

#### Scenario: An asset the admission excludes is still present

- **WHEN** an asset with a `COMPLETED` row is added to a denylisted album while it stays in the gallery, and a
  cycle runs
- **THEN** the discovery still carries it and its rows are kept

#### Scenario: An unreadable walk deletes nothing

- **WHEN** the operator makes the walk unreadable, removes an asset with in-window rows, and a cycle runs
- **THEN** the discovery carries no candidates and reports no full enumeration, and no row is deleted

#### Scenario: A later cycle enqueues the remainder from the ledger

- **WHEN** a job-limited cycle is followed by another cycle with no gallery change in between
- **THEN** the remainder is enqueued by resolving its `DISCOVERED` rows' keys, and the second cycle's walk
  reads no already-recorded asset's resources

#### Scenario: A removed asset resolves to nothing

- **WHEN** the operator removes an asset from the gallery and a cycle resolves a ledger key for it
- **THEN** the resolution returns nothing for that key

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

### Requirement: The world boots cold

Constructing the world SHALL force no member of the composed `AppCore` that the iOS root does not force at
process start. In particular it SHALL NOT touch a lazily-built feature, flow or controller to obtain a side
effect of its construction; if a harness needs a feature's instance, it reads it at the point of use, as a
production entry point would. The world therefore starts from the same state a cold iOS process starts
from, so a path that only works once something else has been built fails in the world as it would on a
device.

#### Scenario: A wiring that depends on build order

- **WHEN** a collaborator is wired only as a side effect of building another lazy member
- **THEN** a world-driven test that exercises the first collaborator without the second fails, rather than
  passing because the world built the second at construction

#### Scenario: The world's source touches the core eagerly

- **WHEN** `World.kt` reads a member of the composed core in an `init` block or an eagerly initialized
  property, outside a lambda body
- **THEN** the boots-cold gate fails, naming the line — the access must be deferred to use (`by lazy`,
  `get()`, or the function that needs it)

### Requirement: The world's transfer doubles are the transfer contracts' Fake bindings

The world's `BackgroundTransfer` and `DownloadTransport` doubles SHALL each be bound, as the `Fake` binding of
that port's contract (capability `port-contracts`), in `:test:world`'s `commonTest`, so every world and
integration test stands on a double held to the same clauses its real adapter passes. The binding SHALL play
the network through the double's existing operator actions — answering each transfer as the clause's fixture
route says — and SHALL NOT add a lever for it. A clause the double fails SHALL be fixed in the double; the
double's levers and inspection stay as this spec requires them. Behaviour the double models that no real host
exhibits — the PhotoKit tier's single free retry — SHALL stay uncontracted rather than asserted by a clause only
the double reaches.

#### Scenario: The world's upload double accepts an unusable payload

- **WHEN** the `BackgroundTransfer` contract hands the world's double a resource whose payload is not the
  world's resource type
- **THEN** the double answers `FAILED`, as a real tier does for a payload it cannot upload, and the world tests
  that stage ordinary resources are unaffected

#### Scenario: The binding completes a transfer

- **WHEN** a clause creates a job to a route that accepts
- **THEN** the binding completes it through the double's own complete action, and the clause reads the outcome
  through the port and its handle exactly as it does against the live adapter
