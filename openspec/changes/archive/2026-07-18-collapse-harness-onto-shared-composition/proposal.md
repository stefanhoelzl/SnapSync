# Proposal: collapse-harness-onto-shared-composition

## Why

Migration step 10 of the `module-architecture` plan (`test/architecture/migration/PLAN.md`,
"harness collapse"). The target module set names `:adapter:fake` and one `:app:desktop`; the honest
in-memory doubles still lived scattered across three otherwise-empty legacy modules
(`:domain:gallery`, `:domain:download-store`, `:capability:attest`) plus two test source sets, the
world still hand-assembled the app-side graph (`rebuildSources()` building status sources, a
`JoinEvent`, a create use-case beside `AppCore`'s), and the world carried two byte-identical copies
of production code (`WorldLedgerStore`, `HttpEnrollment`) — the deletion ledger's last row. The
`FakeHonestyTest` gate, created self-arming at step 0, still printed PENDING.

## What Changes

- **`:adapter:fake` is created** (jvm + iosSimulatorArm64; package `app.snapsync.fake`): the honest
  in-memory port implementations move in — `InMemoryLedgerStore` (from `:domain:engine`'s
  commonTest), `InMemoryDownloadStore`, `InMemoryAttestStore`, `InMemoryPhotoLibrary`,
  `InMemoryGalleryStatusSource`, `InMemoryRawAssetSource`, and the world's four lever-free doubles
  (`InMemoryDiscoveryStore`, `InMemoryJoinedEventMarker`, `InMemoryDeviceManifestStore`,
  `InMemoryAlbumMapStore`). The three settable gallery fakes lose their `set()`/`current()` levers:
  state is a **constructor-injected cell** (`MutableStateFlow`), so rigging physically lives with
  whoever owns the cell — `:test:world`'s wrappers (`WorldGallery`, `RecordingDownloadStore`) or a
  test. **The fake-honesty gate arms** (deliberate-red proven, zero gate edits).
- **The world collapses onto `snapSyncApp`**: `World` builds `AppPorts` over the fakes and holds
  `core: AppCore = snapSyncApp(scope, ports)` — features, flows, and the user-tap command bundle
  are the production instances; `uploadCore` keeps composing the extension-tier cycle over the same
  stores (the two-process model in one process). `WorldLedgerStore` and the world's `HttpEnrollment`
  copy die (`:adapter:generic`'s serves the mini-edge via the injected client) — the deletion
  ledger's `Enrollment ×2 (keep 1)` row reaches **zero**, the first law retired by the ledger.
- **Integration tests enter through the bundle**: hand-built `UserCommands`/`JoinEvent` assemblies
  are replaced by `World.userCommands` / `World.joinEvent` (the composed instances); the leave test
  awaits the fire-and-forget backend DELETE instead of assuming it synchronous. Assertions are
  unchanged.
- **`:app:desktop:ui` folds into `:app:desktop`**: one desktop module hosts both harnesses —
  `:app:desktop:run` (world, unchanged) and `:app:desktop:runForge` (forge, a plain `JavaExec`,
  since the Compose plugin models one application main class). `:test:harness-driver` loses the
  dead dependency; both `driveForge`/`driveWorld` verified live.
- **Module deletions**: `:domain:gallery`, `:domain:engine`, `:domain:download-store`,
  `:capability:attest`, `:app:desktop:ui`. The storage-seam contracts (`LedgerStoreContract`,
  `DownloadStoreContract`) re-home to `:test:world` **commonMain** (the one test-infra surface every
  implementor's test source set can reach); the SQLDelight driver tests re-home to
  `:adapter:generic` (`jvmTest` + `iosSimulatorArm64Test`); `SyncEngineTest` and the fake-backed
  contract runs to `:test:world` commonTest; the remaining fake-driven feature tests to
  `:adapter:fake` commonTest.

## Impact

- Specs: `harness-world-model` (the collapse), `full-stack-harness` + `desktop-test-harness` +
  `desktop-app-shell` (the fold and run tasks), `gallery-status` (fake placement), `sync-ledger`
  (store/contract seats).
- Beacon: 29 → 22 (modules 10→4, ledger 1→0, shells 18 unchanged; no law increased).
- Behavior-preserving: both harnesses keep the same panels, buttons, and presets; device binaries
  untouched (`:app:ios` merely drops a vestigial no-longer-referenced dependency).
