## Why

The desktop harness today (`:app:desktop`, capability `desktop-test-harness`) only **forges**
`SyncStatus`/`UiState` directly through `PanelController` — it never runs the real engine, upload
cycle, download controller, or listing-backed status projection. So the whole platform-agnostic
stack — `SyncEngine` + `UploadCycle`, `ExtensionReconciler`, `DeviceManifestProducer`,
`DownloadController`, `OwnDeviceCompletedAssetsSource` + `ListingSyncStatusSource`, `CreateEvent` —
is only ever exercised on-device (OS-scheduled, unforceable) or in narrow per-module `commonTest`s
with **private, per-test fakes** (`FakePlatform`, `FakeUnion`, `FakeImporter`, `FakeFiles`, …).
Nothing lets that entire stack run **as a system** against controllable in-memory infrastructure,
observable on JVM + simulator without a device.

This is change **2 of 3** of the "full-stack world" design. It builds the **keystone** the other
two need: a shared, controllable in-memory **world** the real stack runs against. Change 1
(`extract-ui-harness-module`) is independent; change 3 (the full-stack desktop harness) consumes
this module to drive the real stack behind the phone frame. This change delivers the world plus its
first consumer (`:test:integration`) — **no desktop UI** (that is change 3).

## What Changes

- **Create a new test-infra module `:test:world`** (`commonMain` + `commonTest`; targets `jvm()` +
  `iosSimulatorArm64` only — test infra never ships to device, so **no `iosArm64`**). It provides a
  controllable in-memory world and composition helpers that assemble the **real** stack against it.
  Per testing rule 1 its own tests run on **both** JVM and the iOS simulator.
- **Backend object store (the keystone):** an in-memory model of the edge's byte store + registry —
  deposited object keys per device byte-partition (`files/<deviceId>/…`), per-`(event, device)`
  manifests, and registered events. It computes the edge's read-models **faithfully** (drift from
  the real Deno `backend/` edge is **accepted — no golden fixture**): the per-device file listing
  (`GET /files/device/<id>`), the event-wide union (`GET /event/<id>/files`, complete-only across
  devices), and the reconcile-seed listing (the same per-device listing, consumed by the rejoin
  reconciler). It models **one fixed "my device"** plus **injectable foreign devices** (for
  download/echo).
- **Ktor `MockEngine` mini-edge:** a routing `MockEngine`-backed `HttpClient` answering the app-side
  metadata calls, so the **real** common-Ktor seams run against it — `HttpDeviceFilesSource`
  (`GET /files/device`), `HttpEventUnionSource` (`GET /event/<id>/files`), `HttpEventCreationClient`
  (`POST /event`), and the device-manifest `PUT /event/<id>/device/<id>`. Reuses the existing
  `HttpEventUnionSourceTest`/`HttpEventCreationClientTest` MockEngine pattern, extended from a
  single unconditional response to a **path+method route table**.
- **Fake `UploadJobPlatform`:** an operator-driven, **inspectable** job queue. `createJob` → PENDING;
  an operator action **completes** a job (deposits the object key into the store — byte transfer is
  **store-direct, not ktor**) or **fails** it with a chosen engine `UploadError` (drives the real
  retry chain, `attempt++`). Implements all six seam methods, a settable job-limit (`LIMIT_EXCEEDED`
  via `CreateResult`), and a token-delta change feed driven by the in-memory gallery (add → new
  `Resource`; remove → `removedAssetIds`; "expire token" → `fullEnumeration`).
- **Fake download seams:** operator-driven `PhotoDownloadJobs` (staging; a non-completed transfer
  leaves the resource PENDING — **there is no `DownloadError` type**) and `PhotoLibraryImporter`
  (imports into the in-memory gallery so **echo-suppression** is exercised; optional
  `ImportResult.Failed`). Plus the already-shared in-memory collaborators reused as-is:
  `InMemoryRawAssetSource`, `InMemoryLedgerBackend`, `InMemoryDownloadStore`, and an in-memory
  `DiscoveryStore`.
- **Common `HttpDeviceManifestUploader`** (~15 lines) living **in `:test:world`**, PUTting the
  manifest through the mini-edge. Production's `IosDeviceManifestUploader` is **untouched** — see
  `design.md` for the ktor-home rationale.
- **Composition helpers** that assemble the whole real stack against the world for a given world
  state, mirroring the extension composition root (`UploadExtensionRoot.process()`): the upload
  cycle path, the reconcile + manifest path, the download path, the listing-backed status path, and
  the create-event path.
- **Extend `:test:integration`** (created empty by change 3's design lineage; **created here** as its
  first real user) to assert **`UiState` + world outcomes** (objects landed in the store, ledger
  rows `COMPLETED`, foreign photos imported) from world mutations + cycle invocations — not `UiState`
  alone. This is the testing-rule-3 "seam ↔ UI-state integration" module, now spanning the real
  upload/download execution edge.

## Capabilities

### New Capabilities

- **`harness-world-model`** — the controllable in-memory world + composition helpers the real stack
  runs against. Its requirements pin: backend-store read-model fidelity (drift-accepted, no golden),
  the operator-driven upload/download job lifecycle, the `MockEngine` mini-edge over the four
  common-Ktor seams, the device model (one own device + injectable foreign devices), the failure
  levers, the real-stack composition helpers, and the "consumed by **both** the harness and
  `:test:integration`; runs on **JVM + `iosSimulatorArm64`**" contract.

### Modified Capabilities

_None. `device-manifest` is **unchanged** — production's `IosDeviceManifestUploader` is untouched;
`:test:world` adds its own common uploader without altering the shipped seam or its home._

## Impact

- **New module:** `test/world/` (`build.gradle.kts`, `src/commonMain`, `src/commonTest`); register
  `:test:world` in `settings.gradle.kts`.
- **New module:** `test/integration/` (`build.gradle.kts`, `src/commonTest`); register
  `:test:integration` in `settings.gradle.kts`. Depends on `:test:world` + `:domain:presentation`.
- **New deps (`:test:world` `commonMain`):** the capability/domain seams it composes — `:domain:engine`,
  `:domain:gallery`, `:domain:status`, `:domain:download-store`, `:capability:upload`,
  `:capability:upload-url`, `:capability:config`, `:capability:rejoin`, `:capability:download`,
  `:capability:event-creation-ui` — plus `ktor.client.core`, **`ktor.client.mock`** (in `commonMain`,
  not `commonTest`, since the mini-edge is reusable infra), `kotlinx.serialization.json`,
  `coroutines.core`, `kermit`.
- **No dependency cycle:** `:test:world` is depended-on only by `:app:desktop` (change 3) and
  `:test:integration`, and depends only on production capability/domain modules — one-directional
  (grep confirms no capability/domain module references `:test:*` or `:app:desktop`).
- **CI/coverage:** `:test:world:build` runs its self-tests (read-model computations, mini-edge
  routing, job lifecycle) on JVM + simulator; `:test:integration` gains full-stack UiState+outcome
  assertions. No production code changes; no UI work (change 3).
- **Docs:** add `:test:world` to the `CLAUDE.md` module table; record the manifest-uploader
  ktor-home decision (this change's `design.md`; the `docs/design.md §5.1/§6` world-model note is
  carried into change 3, which builds the harness that surfaces it).
- **Depends on:** landed Move A (`add-rawasset-walk-seam` — the `RawAsset` walk seam +
  `InMemoryRawAssetSource`) and Move B (`relocate-upload-cycle` — `:capability:upload`). Independent
  of change 1 (`extract-ui-harness-module`).
- **Not in scope:** the desktop harness UI / control panel that drives the world (change 3); any
  production behavior change; a golden fixture against the real Deno edge (drift is accepted).
