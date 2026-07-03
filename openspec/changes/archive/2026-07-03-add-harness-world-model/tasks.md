# Tasks — add-harness-world-model (change 2 of 3)

The keystone shared test-infra module `:test:world` + its first consumer `:test:integration`.
Depends on landed Move A (`add-rawasset-walk-seam`) and Move B (`relocate-upload-cycle`). No
production code changes; the world composes the **real** stack against controllable fakes. All logic
lives in `commonMain`/`commonTest` so it runs on **JVM and `iosSimulatorArm64`** (testing rule 1).

## 1. Create the `:test:world` module

- [x] 1.1 Add `test/world/build.gradle.kts` from the capability template but with targets `jvm()` + `iosSimulatorArm64()` **only** (no `iosArm64` — test infra never ships to a framework); `jvmToolchain`.
- [x] 1.2 `commonMain` deps: **`api`** (the world is a facade — its consumers need the real stack's types) of `:domain:engine`, `:domain:gallery`, `:domain:status`, `:domain:permission`, `:domain:download-store`, `:capability:upload`, `:capability:upload-url`, `:capability:config`, `:capability:rejoin`, `:capability:download`, `:capability:event-creation-ui`; plus `implementation` of `libs.ktor.client.core`, `libs.ktor.client.mock` (in `commonMain` — the mini-edge is shared infra), `libs.kotlinx.serialization.json`, `libs.coroutines.core`, `libs.kermit`.
- [x] 1.3 `commonTest` deps: `kotlin("test")`, `libs.coroutines.test`.
- [x] 1.4 Register `:test:world` in `settings.gradle.kts`.
- [x] 1.5 `./gradlew :test:world:build` resolves (empty module compiles); confirm **no dependency cycle** — grep that no `capability`/`domain` module references `:test:*` (one-directional).

## 2. Backend object store + the three read-models (drift-accepted, no golden)

- [x] 2.1 `BackendStore`: in-memory `byteStore: deviceId -> Set<objectName>`, `manifests: (eventId, deviceId) -> DeviceManifest`, `events: Set<eventId>` (marker registry). Deposit/register/read APIs, all inspectable.
- [x] 2.2 **Per-device listing** computation (`GET /files/device/<id>`) → `[{filename, size, url}]` over the device byte partition. This single read-model serves **both** the rejoin reconcile seed and own-device status completeness.
- [x] 2.3 **Event-union** computation (`GET /event/<id>/files`) → complete-only assets across devices: marker gate (absent ⇒ 404), per-device manifest fan-out, include an asset only when every manifest resource `key` is present in that device's partition; project to `{deviceId, assetId, creationDate, resources:[{role, contentType, key, filename, size, url}]}`.
- [x] 2.4 Synthetic `url` handles (e.g. `world://<deviceId>/<filename>`) the fake download jobs resolve store-direct (no real presigned S3).
- [x] 2.5 `commonTest`: self-tests pinning the **behavioral** read-model contract (shapes, completeness semantics, marker-gate 404, empty-array cases) — not byte-golden.

## 3. Ktor `MockEngine` mini-edge (real seams over a route table)

- [x] 3.1 `miniEdgeClient(store): HttpClient` — `HttpClient(MockEngine { ... })` dispatching on `request.method` + `request.url.encodedPath` (extend the existing `HttpEventUnionSourceTest` single-response pattern to a route table).
- [x] 3.2 Routes: `GET /files/device/<id>`, `GET /event/<id>/files` (404 when no marker), `POST /event` (201 + register marker), `PUT /event/<id>/device/<id>` (200 + deposit manifest); unmatched ⇒ 404.
- [x] 3.3 A **backend-offline** switch flipping the two GETs to `502`.
- [x] 3.4 Inject the client into the **real** `HttpDeviceFilesSource`, `HttpEventUnionSource`, `HttpEventCreationClient`, and the common `HttpDeviceManifestUploader`; `commonTest` proves each real seam round-trips against the mini-edge (incl. the offline → keep-last-good / union-failure paths).

## 4. Operator-driven upload-job platform + discovery

- [x] 4.1 `FakeUploadJobPlatform` implementing all six `UploadJobPlatform` methods with inspectable pending/retry/ack buckets and per-job attempt.
- [x] 4.2 `createJob` → `CREATED`/`PENDING`; settable job-limit → `LIMIT_EXCEEDED`; forced `failCreate` → `FAILED`.
- [x] 4.3 Operator **complete(job)** → deposit `files/<ownDeviceId>/<filename>` store-direct + move to ack bucket (`SUCCEEDED`); **fail(job, error: UploadError)** → move to retry bucket carrying the chosen error (drives the real `Retry` chain, `attempt++`).
- [x] 4.4 Token-delta `discoverResources(sinceToken)` derived from the in-memory gallery: add → `resources`, remove → `removedAssetIds`, **"expire token"** action → `fullEnumeration=true` with the whole key-set.
- [x] 4.5 `InMemoryDiscoveryStore : DiscoveryStore` (`loadToken`/`saveToken`/`clearToken`).
- [x] 4.6 `commonTest`: lifecycle transitions (create→complete→ack→ledger COMPLETED; create→fail→retry attempt++; limit-exceeded defer; expire-token full-enum reconcile).

## 5. Operator-driven download seams + echo-suppression

- [x] 5.1 `FakePhotoDownloadJobs` — inspectable `enqueue`; operator **stage(ref, resourceKey)** resolves the synthetic `url` store-direct and calls `controller.onResourceStaged`; a non-staged download stays PENDING (**no `DownloadError`** — the no-terminal-failure posture).
- [x] 5.2 `FakePhotoLibraryImporter` — `import` adds the asset to `InMemoryRawAssetSource` (so it enters gallery enumeration) and marks the download store imported; settable `failNextImport` → `ImportResult.Failed`.
- [x] 5.3 Reuse `InMemoryDownloadStore` (already `commonMain` in `:domain:download-store`) as-is; its `suppressedLocalIds()` feeds both `UploadCycle.suppressedAssetIds` and `OwnDeviceCompletedAssetsSource`.
- [x] 5.4 `commonTest`: a foreign asset flows union → stage → import → suppression, and the own upload cycle then **skips** the imported asset (echo-suppression end to end).

## 6. Common manifest uploader (D6)

- [x] 6.1 `HttpDeviceManifestUploader(client: HttpClient, host) : DeviceManifestUploader` (~15 lines) PUTting `<host>/event/<eventId>/device/<deviceId>` — a common mirror of the shipped `IosDeviceManifestUploader`. Production's uploader is **untouched / not relocated**.
- [x] 6.2 In-memory `DeviceManifestStore` (accumulator + last-uploaded JSON) for `DeviceManifestProducer`.
- [x] 6.3 `commonTest`: a drained cycle's `onDiscovery` produces + PUTs a manifest that lands in the store and makes the union report the asset complete.

## 7. Device model + failure levers

- [x] 7.1 Fixed own `deviceId`; `addForeignDevice(deviceId, assets)` deposits byte objects + manifest so the union returns that device's complete assets.
- [x] 7.2 Failure levers exposed on the `World`: backend-offline (§3.3), job-limit (§4.2), per-job `UploadError` (§4.3), import failure (§5.2).

## 8. Composition helpers (mirror `UploadExtensionRoot.process()`)

- [x] 8.1 Upload-cycle assembler: `SyncEngine(EdgeUploadRequestProvider(host, ownId), LedgerWriter(ledger))` + `UploadCycle(...)` + a `process()`-shaped runner (reload config → `reconciler.reconcile` → `buildUploadConfig` → `cycle.run()`); model the pending-requeue as an opt-in step.
- [x] 8.2 Reconcile + manifest assembler: `ExtensionReconciler(HttpDeviceFilesSource, ledger, marker, ownId, clearDiscoveryCursor)` + `DeviceManifestProducer(inMemoryStore, HttpDeviceManifestUploader, ownId)`.
- [x] 8.3 Download assembler: `DownloadController(HttpEventUnionSource, InMemoryDownloadStore, fakeJobs, fakeImporter, myDeviceId = ownId)`.
- [x] 8.4 Status assembler: `OwnDeviceCompletedAssetsSource(enumerator, HttpDeviceFilesSource, ownId, suppressedLocalIds)` + `ListingSyncStatusSource(completed, permission, gallery, inFlight = ReadingInFlightSource { ledger.aggregates().pending }, scope)`.
- [x] 8.5 Create-event assembler: `CreateEvent(HttpEventCreationClient, MutableCreationStatusSource, provision = { config cell := eventId }, scope)`.

## 9. Create `:test:integration` (first consumer)

- [x] 9.1 Add `test/integration/build.gradle.kts` (`jvm()` + `iosSimulatorArm64()`); `commonTest` deps `:test:world`, `:domain:presentation`, `kotlin("test")`, `libs.coroutines.test`, `libs.orbit.test`. Register in `settings.gradle.kts`.
- [x] 9.2 Assemble the real `engine → status → presentation` stack over the world; assert **`UiState`** (Loading → Setup → InProgress/NothingToSync/Completed) from world mutations + cycle invocations.
- [x] 9.3 Assert **world outcomes** alongside `UiState`: objects landed in the store (per-device listing grows), ledger rows `COMPLETED`, foreign photos imported into the gallery, echo-suppression holds.
- [x] 9.4 Cover the create-event path (`CreateEvent` → provision → config present → gate lifts) and a failure lever (backend-offline keep-last-good).

## 10. Build, verify, docs

- [x] 10.1 `./gradlew build` green — `:test:world` + `:test:integration` self-tests/integration tests run on JVM.
- [x] 10.2 `./gradlew compileIosMainKotlinMetadata` green (iOS proxy compile of both new modules).
- [x] 10.3 Confirm the module graph: `:test:world` depends only on production capability/domain modules + ktor; nothing production depends back on it.
- [x] 10.4 Add `:test:world` (and `:test:integration`, if not already) to the `CLAUDE.md` module table with one-line descriptions.
- [x] 10.5 `openspec validate add-harness-world-model --strict` passes.
