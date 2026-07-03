## Context

Change 2 of the "full-stack world" design. The goal across the three changes: drive the **real**
platform-agnostic stack (`SyncEngine` + `UploadCycle`, `ExtensionReconciler`,
`DeviceManifestProducer`, `DownloadController`, `ListingSyncStatusSource`, `CreateEvent`) against
**controllable in-memory infrastructure**, so the whole system — upload **and** download — is
observable and testable on JVM + simulator without a device. This change builds the keystone:
`:test:world`, a shared test-infra module providing that world. Change 3 wires it behind the desktop
phone frame; `:test:integration` (created here) is its first consumer.

**The template is the extension composition root.** `UploadExtensionRoot.process()`
(`app/ios/photokit-extension/.../UploadExtensionRoot.kt`) is the exact production assembly the world
mirrors, one substitution at a time:

```
                          PRODUCTION (extension)            :test:world (this change)
  ledger backend          iosLedgerBackend()          →     InMemoryLedgerBackend
  upload job platform      IosUploadJobPlatform        →     FakeUploadJobPlatform (operator-driven)
  discovery cursor         IosDiscoveryStore           →     InMemoryDiscoveryStore
  suppression source       iosSuppressionSource()      →     InMemoryDownloadStore
  config source            KeychainConfigStore         →     in-memory config cell
  HTTP client              darwinHttpClient()          →     HttpClient(MockEngine{ route table })
  reconcile file seam      HttpDeviceFilesSource ✔ REAL, over the mini-edge client
  manifest uploader        IosDeviceManifestUploader   →     HttpDeviceManifestUploader ✔ REAL-shaped, over the mini-edge, in :test:world
  upload provider          EdgeUploadRequestProvider ✔ REAL (pure URL builder)
  engine / cycle / producer / reconciler ✔ ALL REAL
```

Everything above the substituted seams is the shipped code, unmodified.

### Verified seam inventory (grounding for the fakes)

- **`UploadCycle`** (`:capability:upload`, `commonMain`, ktor-free): `run(): CycleResult`; ctor
  `(engine, ledger: LedgerWriter, platform: UploadJobPlatform, store: DiscoveryStore, log,
  onDiscovery: suspend (Discovery) -> Unit, suppressedAssetIds: suspend () -> Set<String>)`.
- **`UploadJobPlatform`** (six suspend methods): `fetchRetryJobs`, `fetchAckJobs`, `retryJob(job,
  request)`, `acknowledge(job)`, `discoverResources(sinceToken: ByteArray?): Discovery`,
  `createJob(request, resource): CreateResult`. `CreateResult = { CREATED, LIMIT_EXCEEDED, FAILED }`.
  `PlatformUploadJob(key, contentType, state: PlatformJobState, error: UploadError?, data, handle)`,
  `PlatformJobState = { SUCCEEDED, FAILED, CANCELLED, PENDING, REGISTERED }`.
- **`Discovery(resources: List<Resource>, nextToken: ByteArray, removedAssetIds: List<String>,
  fullEnumeration: Boolean)`** — the token-delta change feed. Additions ride in `resources`; there is
  no separate "added" field. `fullEnumeration=true` ⇒ `resources` is the whole live key-set and the
  cycle reconciles via `ledger.retainAssets`.
- **`DiscoveryStore`** (non-suspend): `loadToken()`, `saveToken(token)`, `clearToken()`.
- **`SyncEngine(provider: UploadRequestProvider, ledger: LedgerWriter)`**; `handle(event): SyncDecision`.
  `UploadError = { Network, Http(status), Cancelled, Unknown(detail) }`.
- **`EdgeUploadRequestProvider(host, deviceId)`** — pure: `provide` → `PUT
  <host>/files/device/<deviceId>/<encoded filename>`, header `Content-Type`. No network. REAL in the world.
- **`ExtensionReconciler(files: DeviceFilesSource, ledger: LedgerBackend, marker: JoinedEventMarker,
  deviceId, clearDiscoveryCursor: suspend () -> Unit, log)`**; `reconcile(configuredEventId): Boolean`.
  Seeds `LedgerEntry(key=filename, assetId=assetIdFromUploadKey(filename), COMPLETED, attempt=0)` via
  `resetTo`. `DeviceFilesSource.list(deviceId): Result<List<String>>`;
  `HttpDeviceFilesSource(client, host)` GETs `<host>/files/device/<deviceId>`.
- **`DeviceManifestProducer(store: DeviceManifestStore, uploader: DeviceManifestUploader, deviceId)`**;
  `produce(eventId, startDate, discovered, removedAssetIds, fullEnumeration)`.
  `DeviceManifestUploader.put(eventId, deviceId, json): Boolean`. `IosDeviceManifestUploader` PUTs
  `<host>/event/<eventId>/device/<deviceId>` (route confirmed).
- **`DownloadController(union: EventUnionSource, store: DownloadStore, jobs: PhotoDownloadJobs,
  importer: PhotoLibraryImporter, myDeviceId, log)`**; `reconcile(eventId)`, `onResourceStaged(ref,
  resourceKey, stagedPath)`, `importReady()`, `onLeaveOrSwitch()`. `EventUnionSource.union(eventId):
  Result<List<UnionAsset>>`; `HttpEventUnionSource(client, host)` GETs `<host>/event/<eventId>/files`.
  `PhotoDownloadJobs.enqueue(List<PendingDownload>)`, `cancelAll()`. **No `DownloadError`.**
  `PhotoLibraryImporter.import(ref, resources, creationDate): ImportResult { Imported(createdLocalId)
  | Failed(message) }`.
- **`OwnDeviceCompletedAssetsSource(enumerator: GalleryResourceEnumerator, files: DeviceFilesSource,
  deviceId, suppressedLocalIds)`** — implements **both** `CompletedAssetsSource` and
  `GalleryStatusSource`. **`ListingSyncStatusSource(completed, permission, gallery, inFlight, scope):
  SyncStatusSource`** is a factory function. `InFlightSource` fed by `ReadingInFlightSource(read:
  suspend () -> Int)` (the world reads `ledger.aggregates().pending`).
- **`HttpEventCreationClient(client, host)`** — `POST <host>/event`, `{name}` → `CreateOutcome {
  Created(eventId) | InvalidName | Transient }`. `CreateEvent(client, status, provision, scope)`.

### Corrections to the settled brief (reality, captured — not re-litigated)

1. **No `DownloadError` type exists.** The download side has a deliberate "no terminal failure"
   posture: `PhotoDownloadJobs` non-completion leaves a resource PENDING for retry; import failure is
   `ImportResult.Failed(message)`; union failure is `Result.failure`. The world's download failure
   levers are therefore **backend-offline (union `Result.failure`)** and **import failure
   (`ImportResult.Failed`)** — not a per-job `DownloadError`.
2. **`IosDeviceManifestUploader` lives in `:app:ios:photokit-extension`** (iosMain), not
   `:domain:gallery`. Gallery owns only the ktor-free `DeviceManifestUploader` **interface**. This is
   what forces the manifest-uploader decision below.
3. **Names:** `ExtensionReconciler` (not `Reconciler`); `HttpEventCreationClient`/`EventCreationClient`
   (not `HttpEventCreator`); `ListingSyncStatusSource` is a factory function (not a class).

## Goals / Non-Goals

**Goals:**
- One controllable in-memory world the **whole real stack** runs against, on JVM + `iosSimulatorArm64`.
- Faithful-enough edge read-models (per-device listing, event-union, reconcile-seed) to drive the real
  seams — **drift from the Deno edge is accepted; no golden fixture**.
- Operator-driven, **inspectable** upload/download job lifecycles (queue state observable; deposits,
  ledger rows, imports all readable) so integration tests assert **world outcomes**, not just `UiState`.
- Reuse existing in-memory fakes wholesale (`InMemoryLedgerBackend`, `InMemoryDownloadStore`,
  `InMemoryRawAssetSource`, `InMemoryGalleryResourceEnumerator`); promote the good private per-test
  fakes into shared, inspectable world doubles.
- Zero production change; `:test:integration` created as the first consumer.

**Non-Goals:**
- No desktop harness UI / control panel driving the world (change 3).
- No golden byte-fidelity against the real edge (drift accepted).
- No `iosArm64` target (test infra never ships to a device framework).
- No change to `device-manifest` or any shipped seam/home.

## Decisions

### D1 — New `:test:world` module (`commonMain` + `commonTest`, `jvm()` + `iosSimulatorArm64`)
The world's fakes and composition helpers are **reusable infrastructure** consumed by two modules
(`:app:desktop`, `:test:integration`), so — following the repo's own pattern where settable fakes
(`InMemory*`, `Mutable*`) live in `commonMain` rather than `commonTest` — the world lives in
`commonMain`. Its `commonTest` holds the world's **self-tests** (read-model computations, mini-edge
routing, job-lifecycle transitions). Targets `jvm()` + `iosSimulatorArm64` only (no `iosArm64`: it is
never linked into a shipped framework), so the self-tests satisfy testing rule 1. `commonMain` deps:
the ten capability/domain seams it composes (declared **`api`**, not `implementation` — the world is a
facade that hands the real stack's types to its consumers, so `LedgerState`, `CycleResult`,
`GalleryStatusSource`, `DeviceManifestAsset`, `DownloadController`, `AssetRef`, … must leak
transitively to `:test:integration` and `:app:desktop`), plus `ktor.client.core`, `ktor.client.mock`,
`kotlinx.serialization.json`, `coroutines.core`, `kermit` (`implementation`). *Notable:* `ktor.client.mock`
sits in `commonMain` (not `commonTest`) because the mini-edge is shipped infra of this module —
acceptable for a test-only module that never enters a production classpath.

### D2 — The backend object store computes read-models faithfully, no golden
The store is the single source of world truth: three maps —
`byteStore: deviceId -> Set<objectName>` (objects `files/<deviceId>/<filename>`),
`manifests: (eventId, deviceId) -> DeviceManifest`, and `events: Set<eventId>` (marker registry). The
three read-models are pure functions over these:

- **Per-device listing** (`GET /files/device/<id>`) → `[{filename, size, url}]` — the device's byte
  partition, one entry per object. Serves **both** the rejoin reconcile seed (`HttpDeviceFilesSource`)
  **and** own-device status completeness (`OwnDeviceCompletedAssetsSource` reuses the same seam) — so
  the "reconcile-seed listing" of the brief is **the same read-model**, consumed by two seams; the
  world exposes it once.
- **Event-union** (`GET /event/<id>/files`) → complete assets across all devices. Gate on marker
  presence (absent ⇒ 404, i.e. `union → Result.failure`); enumerate `manifests` for the event; per
  device, include an asset **only when every** manifest resource `key` is present in that device's byte
  partition; project to `{deviceId, assetId, creationDate, resources:[{role, contentType, key,
  filename, size, url}]}`.
- **`url`** is a synthetic in-memory handle (e.g. `world://<deviceId>/<filename>`), never a real
  presigned S3 URL — the download **byte transfer is store-direct** through the fake `PhotoDownloadJobs`,
  so the `url` is only an opaque token the fake resolves against the store.

Fidelity is behavioral (shapes, completeness semantics, gate/404, empty-array cases), not byte-golden.
The real Deno edge is covered by its own `Deno.test` suite; re-goldening here would couple two
independently-evolving surfaces for no test value. *Rationale recorded so a future reader does not
"fix" the drift.*

### D3 — Operator-driven, inspectable `FakeUploadJobPlatform`
Promotes `UploadCycleTest`'s private `FakePlatform` into a shared, **operator-controllable** double:
`createJob(request, resource)` enqueues a PENDING job (recording `resource.filename` and the built
URL) and returns `CREATED`, unless the settable job-limit is hit → `LIMIT_EXCEEDED`, or a forced
`failCreate` → `FAILED`. Operator actions mutate the queue between cycles:
- **complete(job)** → deposit `files/<myDeviceId>/<filename>` into the store (**store-direct** byte
  transfer — no ktor) and move the job to the ack bucket (`SUCCEEDED`), so the next cycle's
  `fetchAckJobs` → `UploadCompleted` → ledger `COMPLETED`.
- **fail(job, error: UploadError)** → move the job to the retry bucket (`FAILED`, carrying the chosen
  `UploadError`), so the next cycle's `fetchRetryJobs` drives the real `Retry` chain (`attempt++`).
- The **token-delta change feed** is derived from the in-memory gallery (`InMemoryRawAssetSource` →
  `ResourceEnumerator`): `discoverResources(sinceToken)` diffs the current library against the token —
  additions → `resources`, removals → `removedAssetIds`, and an **"expire token"** operator action →
  `fullEnumeration=true` with the whole key-set (Apple's routine token-expiry path). The queue is fully
  inspectable (pending/retry/ack buckets, per-job attempt) so tests assert lifecycle, not just outcome.

### D4 — Operator-driven download seams; import exercises echo-suppression
`FakePhotoDownloadJobs.enqueue` records `PendingDownload`s (inspectable); an operator **stage(ref,
resourceKey)** action resolves the synthetic `url` against the store and calls the controller's
`onResourceStaged` (writing the download store) — a non-staged download simply stays PENDING (the
no-terminal-failure posture; **no `DownloadError`**). `FakePhotoLibraryImporter.import` **imports into
the in-memory gallery** (`InMemoryRawAssetSource.set(...)` adds the imported asset) and marks the
download store imported, so its `assetId`/localId enters `suppressedLocalIds()` — which the **real**
`UploadCycle.suppressedAssetIds` and `OwnDeviceCompletedAssetsSource.suppressedLocalIds` then honor,
exercising echo-suppression end to end. A settable `failNextImport` yields `ImportResult.Failed`.

### D5 — Device model: one fixed own device + injectable foreign devices
The world fixes **one own `deviceId`** (the id the upload cycle, provider, reconciler, and own-device
status all use). Foreign devices are **injected** as data: `addForeignDevice(deviceId, assets)` deposits
their byte objects + writes their manifest into the store, so the event-union returns their complete
assets. `DownloadController(myDeviceId = own)` skips own-device assets by id (the union is
identity-blind; the client filters), so a foreign device's assets flow through download → import →
suppression, and the own device's own uploads never echo back. This is the minimum model that
exercises both the upload path (own device) and the download/echo path (foreign devices).

### D6 — Manifest uploader: a common `HttpDeviceManifestUploader` in `:test:world` (SUB-DECISION)
**Chosen:** a ~15-line common `HttpDeviceManifestUploader(client: HttpClient, host)` in `:test:world`
that PUTs the manifest JSON to `<host>/event/<eventId>/device/<deviceId>` over the mini-edge client —
a common-source mirror of the shipped `IosDeviceManifestUploader`. Production's
`IosDeviceManifestUploader` is **untouched and NOT relocated**.

*Ktor-home rationale (the crux).* A common uploader needs a module that is **both** (a) a home for the
`DeviceManifestUploader` seam **and** (b) ktor-bearing. **No production module qualifies:**
- `:domain:gallery` **owns** the `DeviceManifestUploader` interface but is **deliberately ktor-free**
  (verified: only `coroutines`, `engine`, `serialization-json`) — adding ktor to hang a common impl off
  it would pollute the agnostic gallery's dependency contract.
- `:capability:upload` is **also deliberately ktor-free** (verified: engine + gallery + coroutines +
  kermit only; its build file comment explicitly excludes ktor edges).
- The ktor-bearing modules (`:capability:rejoin`, `:capability:download`, `:capability:event-creation-ui`)
  do **not** own the manifest seam, and forcing the seam into one of them to gain a common home would be
  a worse coupling than a test-only uploader.

Because the whole design intent of the world is to run **real common-Ktor seams against the
MockEngine mini-edge**, the manifest PUT should be a real ktor seam too — but its only non-polluting
common home is a **test-infra** module. Hence `:test:world`. This exercises the real PUT route + the
real manifest JSON serialization + the store's manifest-deposit read-model (so the union has real
manifests to fan out over).

*Alternative rejected — a store-direct fake uploader* (deposit the manifest into the world store with
no ktor). Simpler, but it skips the ktor seam and the route, leaving a gap exactly where change 3's
harness wants to observe metadata traffic. The ~15-line common uploader costs almost nothing and keeps
the "real seams over a mini-edge" property total. *(The `docs/design.md` note capturing this belongs
with change 3, which builds the harness that surfaces the world; recorded here as the authority.)*

### D7 — `MockEngine` mini-edge is a route table, not a single response
The existing pattern (`HttpEventUnionSourceTest`, `HttpEventCreationClientTest`) is
`HttpClient(MockEngine { respond(body, status, headers) })` — one unconditional response. The mini-edge
generalizes it to dispatch on `request.method` + `request.url.encodedPath`:

```
GET  /files/device/<id>        -> 200 [ per-device listing ]            (offline lever: 502)
GET  /event/<id>/files         -> 200 [ union ] | 404 (no marker)       (offline lever: 502)
POST /event                    -> 201 { eventId, name, createdAt }      + register marker
PUT  /event/<id>/device/<id>   -> 200; deposit manifest into the store
(unmatched)                    -> 404
```

A single **backend-offline** switch flips the two GETs to `502`, driving the real keep-last-good
(`OwnDeviceCompletedAssetsSource` on a failed listing) and union-failure (`DownloadController` swallow)
paths. The same `HttpClient` is injected into all four real seams, exactly as
`UploadExtensionRoot` shares one `darwinHttpClient()`.

### D8 — Composition helpers mirror `UploadExtensionRoot.process()`
The module exposes small assemble-the-real-stack helpers over a `World` instance, one per stack slice:
- **upload cycle** — `SyncEngine(EdgeUploadRequestProvider(host, ownId), LedgerWriter(ledger))` +
  `UploadCycle(engine, ledger, fakePlatform, discoveryStore, log, onDiscovery = { produce manifest },
  suppressedAssetIds = { downloadStore.suppressedLocalIds() })`, driven by a `process()`-shaped runner
  (reload config → `reconciler.reconcile` → `buildUploadConfig` → `cycle.run()` → pending-requeue).
- **reconcile + manifest** — `ExtensionReconciler(HttpDeviceFilesSource(client, host), ledger, marker,
  ownId, clearDiscoveryCursor = { discoveryStore.clearToken() })` and
  `DeviceManifestProducer(inMemoryManifestStore, HttpDeviceManifestUploader(client, host), ownId)`.
- **download** — `DownloadController(HttpEventUnionSource(client, host), InMemoryDownloadStore,
  fakeDownloadJobs, fakeImporter, myDeviceId = ownId)`.
- **status** — `OwnDeviceCompletedAssetsSource(enumerator, HttpDeviceFilesSource(client, host), ownId,
  suppressedLocalIds)` feeding `ListingSyncStatusSource(completed, permission, gallery = that same
  source, inFlight = ReadingInFlightSource { ledger.aggregates().pending }, scope)`.
- **create-event** — `CreateEvent(HttpEventCreationClient(client, host), MutableCreationStatusSource,
  provision = { world config cell := eventId }, scope)`.

`:test:integration` layers `:domain:presentation`'s Orbit container over the status source to assert
`UiState`, while reading the world for outcomes.

## Risks / Trade-offs

- **[Read-model drift silently diverges from the edge]** → Accepted by design (D2); the edge has its
  own `Deno.test` suite. Mitigation: the world's self-tests pin the **behavioral** contract (shapes,
  completeness, gate/offline) against the specs, and the four real common-Ktor seams parse the world's
  responses — a shape drift breaks a seam parse immediately.
- **[World fakes drift from the real iOS adapters]** → The world fakes `UploadJobPlatform` /
  `PhotoDownloadJobs` / `PhotoLibraryImporter`, whose real impls are iosMain and device-only. Same
  blind spot the shipped tests already accept (cross-process rehydration stays device-verified). The
  world raises confidence in the **agnostic orchestration**, not the Darwin adapters.
- **[`ktor.client.mock` in `commonMain`]** → Unusual, but `:test:world` is test-only and never enters a
  production classpath; the mini-edge is genuinely shared infra, so `commonMain` is correct (D1).
- **[Ordering]** → Depends on Move A + Move B having landed (`InMemoryRawAssetSource`,
  `:capability:upload`). Both are archived. Independent of change 1.
- **[Scope creep into change 3]** → The module provides composition helpers but **no UI**; the
  `process()`-shaped runner is a plain function tests call, not a control panel. Change 3 owns the
  panel that drives it.

## Migration Plan

No runtime/data migration — new test-infra modules only. Steps: (1) create `:test:world` + register in
`settings.gradle.kts`; (2) build the backend store + three read-models + self-tests; (3) build the
`MockEngine` mini-edge route table + inject into the four real seams; (4) build the operator-driven
`FakeUploadJobPlatform` + download fakes + in-memory `DiscoveryStore` + common
`HttpDeviceManifestUploader`; (5) build the composition helpers mirroring `process()`; (6) create
`:test:integration`, depend on `:test:world` + `:domain:presentation`, add the first UiState+outcome
integration tests; (7) `./gradlew build` + `./gradlew compileIosMainKotlinMetadata`; (8) docs. Rollback
is dropping both modules. Sequence: after Move A/B (done), before change 3.

## Open Questions

- **How much of `process()`'s re-invocation policy to model** (the "pending > 0 ⇒ PROCESSING" requeue).
  The world runner can expose it as an opt-in loop; integration tests likely want single-step control.
  Resolve during implementation — the seam is a plain function, cheap to shape either way.
- **Whether `:test:integration` also drives the download status projection** (`StoreDownloadStatusSource`
  → `DownloadStatusSource`) into `UiState`, or asserts download outcomes at the store level only. Defer
  to the integration tests; the world supports both.
