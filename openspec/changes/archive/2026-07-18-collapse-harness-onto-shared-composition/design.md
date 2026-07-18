# Design: collapse-harness-onto-shared-composition

## Context

Migration step 10 (PLAN.md). The `module-architecture` spec's fake-adapter law: `:adapter:fake`
holds honest in-memory port implementations; the `architecture-guards` fake-honesty gate makes
honesty mechanical (port members + a constructor taking initial state, nothing else; rigging in
`:test:world` wrappers). Step 7 had already put the world's upload cycle on `uploadCore`
additively; the full app-graph collapse (`snapSyncApp`) was reserved for this step.

## Goals / Non-Goals

- Goals: create `:adapter:fake` and arm its gate; make `AppCore` the world's app graph; retire the
  deletion ledger (→ 0); fold the two desktop modules into one; delete the emptied legacy modules;
  keep both harnesses operator-identical.
- Non-Goals: behavior changes on device; the ledger-at-zero *promotion* (the beacon row's move into
  `:test:architecture` as a permanent gate happens at step 13b with every other gate — the row now
  self-reports 0 and stays); package normalization beyond the new `app.snapsync.fake`.

## Decisions

- **D1 — honest-double vs fixture split.** The spec's definition ("what the composition smoke test
  and every integration test stand on") decides residency: every `InMemory*` double that a harness
  composition binds — or that only survived in a legacy module to serve one — moves to
  `:adapter:fake`. Doubles private to `:domain`'s own commonTest (`feature/upload`'s
  `InMemoryLedgerStore` fixture, `AlbumCoordinatorTest`'s map store) stay fixtures: `:domain` has
  zero project deps, so they could not import the fake adapter anyway, and the gate's scope never
  sees them. Levered doubles (`FakeBackgroundTransfer`, `FakeDownloadTransport`,
  `FakePhotoLibraryImporter`, `FakeAlbumManager`, `MutablePhotoAccessStatusSource`) are rigging and
  stay in `:test:world`.
- **D2 — rigging seam = constructor-injected state cell.** The fake-honesty gate forbids `set()`.
  The only gate-compliant mutation seam is state the *caller* owns: the three gallery fakes take a
  `MutableStateFlow` cell (with a convenience initial-state constructor), and `:test:world`'s
  `WorldGallery` wrapper owns the cell and re-hosts `set()`/`current()`. Rejected: keeping the
  levers and exempting "settable fakes" — that is the exact rot path the gate's doc names.
- **D3 — one package `app.snapsync.fake`.** The step-4 pure-move precedent (keep packages) applied
  to unchanged production adapters; these fakes change signature anyway (D2), the module is new,
  and one package makes the gate's subject legible. Import churn is confined to test/harness code.
- **D4 — contracts seat in `:test:world` commonMain.** A test source set cannot be depended on
  across modules, and three modules' test source sets must extend `LedgerStoreContract` /
  `DownloadStoreContract` (`:adapter:fake` in spirit — run from `:test:world` commonTest —
  `:adapter:generic` jvm + simulator). `:test:world` commonMain is the one shared test-infra
  surface, so it hosts them and gains `kotlin("test")` (plus `kotlin("test-junit")` on jvmMain —
  the framework artifact only auto-attaches to test compilations). Rejected: keeping
  `:domain:engine`/`:domain:download-store` alive as contract hosts (module distance stays);
  per-target driver tests use `jvmTest`/`iosSimulatorArm64Test` source sets, NOT the intermediate
  `iosTest` (`:test:world` has no `iosArm64`).
- **D5 — the world is the shell.** `AppPorts`' shell-supplied lambdas are the world's operator
  surface: `provision` writes the config cell, `notifyLeave` is the real `HttpLeaveNotifier`,
  `onEventMinted` is a settable hook (default: provision directly; the inspector points it at the
  status host's pending-join gate), `uploadProducer` is an inert `OperatorUploadProducer` (nothing
  auto-runs — the operator IS the producer), and the attest seams are inert stubs (`AppCore`'s
  attestation is lazy and never woken; the mini-edge is unauthenticated). `installPermissionSubscriptions`
  is deliberately not called, mirroring the nothing-auto-runs posture.
- **D6 — one deliberate wiring re-install.** `AppCore` wires `downloadJobs.onStaged` as a bare
  `scope.launch`; the operator's `stageAllDownloads` must be complete on return, so the world
  re-installs the identical hook with the `Job` handle kept (after forcing the controller lazy, so
  the production wiring is what gets replaced, not raced). This is retention, not different
  behavior, and it is the world's one touch on composed wiring — documented at the site.
- **D7 — operator `leave()` stays beside the bundle's.** `UserCommands.leave` (the production
  ordering) launches its backend DELETE fire-and-forget; a world operator action must not return
  before its observable outcome. `World.leave()` therefore remains the synchronous faithful edge
  (real controller cancel + real DELETE + cell/marker clear), and tests that drive the *bundle*
  leave await the DELETE outcome instead.
- **D8 — download inspection moves to the store seam.** The pre-step recorder wrapped
  `PhotoDownloadJobs`; `AppCore` builds the jobs internally, so the world records at the
  `DownloadStore.markEnqueued` port (`RecordingDownloadStore`), which the controller calls once per
  enqueued resource — the same (asset, resourceKey) rows, cleared on `pruneNonTerminal` (the
  leave/switch path) as the old recorder cleared on `cancelAll`.
- **D9 — the fold keeps `:app:desktop:run` and adds `runForge`.** The Compose Desktop plugin models
  exactly one `application {}` main class per module; the world keeps the plugin task (muscle
  memory + driver parity), the forge becomes a `JavaExec` with the same toolchain/jvmArgs. Entry
  file classes (`MainKt`, `FullStackHarnessKt`) already differ, so both coexist in one module.
- **D10 — `worldTest` cancels a child scope.** `AppCore`'s status collectors are infinite; on the
  bare `runBlocking` scope they would hang every test at exit. The runner hands the body a
  cancelled-at-exit child scope — the same teardown the harness window gives its scope.

## Risks / Trade-offs

- The un-refreshed gallery total after an unjoined-state refresh (core skips the gallery refresh
  without a config) differs from the old always-refresh — invisible in practice because the joined
  layer is the only surface showing `N`, and it matches production exactly.
- `:test:world` commonMain carrying `kotlin("test")` widens a main source set with a test library —
  accepted for a test-only module that never reaches a production classpath (same acceptance as its
  `MockEngine` dependency), and it is what makes the contracts single-sited.
