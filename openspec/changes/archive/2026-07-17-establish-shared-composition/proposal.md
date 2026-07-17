# Proposal: establish-shared-composition

## Why

Migration step 7 (PLAN.md "compose/: uploadCore, then snapSyncApp"). The upload cycle is assembled
three times — `UploadExtensionRoot`, `UrlSessionUploadController`, and `:test:world`'s `World` — and
the assembly's entry-gate translation (`readGate`) is written three times with a **divergence
documented nowhere**: the controller reloads the config adapter's StateFlow before the gate read, the
extension does not. Two of the three device-manifest uploaders are byte-identical copies of
`:adapter:generic`'s `HttpEnrollment` (`IosEnrollment` ×2). The `module-architecture` law "One shared
composition" requires every live-core binary and the world harness to call `snapSyncApp`/`uploadCore`;
until they do, every new cycle port is wired per-root — which is exactly how the app-driven tier
shipped without a reconciler and without the direction gate.

## What Changes

- **`:domain` gains the `compose/` zone** (package `app.snapsync.compose`) — the outermost core zone,
  self-arming under the step-0 zone gates with zero gate edits.
- **`uploadCore(scope, UploadPorts): UploadCycle`** — the one upload-cycle assembly. It owns the
  unified `readGate` (see design D1 — the extension's port-pure semantics win), the
  `ExtensionReconciler` + `DeviceManifestProducer` + `SyncEngine` construction, and the shared
  `placeInAlbum`/`onDiscovery` translations. All three former assemblies delegate to it.
- **`snapSyncApp(scope, AppPorts): AppCore`** — the app-level composition: attestation, upload arm,
  join/leave/create use-cases, download controller + jobs + push receiver, album coordinator, and the
  status read-models. `SnapSyncRoot` shrinks to adapters, platform entry points, and the
  coordination lambdas that remain shell-side until `flow/` exists (step 8).
- **Both root-side `IosEnrollment` copies die** (`app/ios` + `photokit-extension`);
  `:adapter:generic`'s `HttpEnrollment` serves both roots. The world's `HttpEnrollment` copy stays
  until step 10 (PLAN). Deletion-ledger row: Enrollment ×4 → ×2.
- **D4 repayment** (`move-features-download-album-creation`): `ResourceEnumerator` moves from
  `feature/upload` to its target `compose/` seat.
- **The world adopts `uploadCore` additively**: `World.cycle` is built by `uploadCore` over the
  world's fakes; the world-local `readGate()`/`reconciler()`/`manifestProducer()` helpers are removed
  (no consumers outside `World` — verified by grep). `World`/`rebuildSources()` are otherwise
  untouched (full collapse is step 10).
- **Behavior-preserving except the one sanctioned decision**: the readGate unification drops the
  controller's per-cycle `KeychainConfigStore.reload()` side effect (design D1 records why the gate
  outcome is provably unchanged and which repair path covers the StateFlow).

## Impact

- Specs: `upload-lifecycle` (the entry-gate translation is one shared implementation),
  `ios-photokit-upload` (composition via `uploadCore`; reconciles the line-27 orchestration placement
  falsified at step 5 — accounted to step 8 by `move-features-download-album-creation`, done here
  because this delta rewrites that sentence anyway), `ios-url-session-upload` (controller delegates
  assembly; the tier's `LedgerWriter` is constructed by `uploadCore` invoked from the controller),
  `harness-world-model` (world composes via `uploadCore`), `ios-app-shell` (root assembles via
  `snapSyncApp`), `gallery-status` (`ResourceEnumerator`'s compose/ seat).
- Code: `:domain` compose/ (new), `app/ios` roots (thinner), `:test:world` (adopts), no module
  create/delete — beacon `targetModules` untouched.
- Beacon: shells Δ (expected down — assembly leaves the detekt-scanned shells); ledger distance
  unchanged at 2 (the Enrollment row shrinks ×4 → ×2 but the row survives until step 10); module 17,
  edges 0, mixed 0 unchanged.
- No runtime identity string moves (`RuntimeIdentityTest` pins hold).
