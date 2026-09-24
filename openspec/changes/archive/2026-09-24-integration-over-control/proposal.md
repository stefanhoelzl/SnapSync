## Why

The integration suite (`:test:integration`, 113 behavioural tests) builds a `World` in-process and reads its
internals — ledger rows, the backend store, call counters — so no test body can target another host, and the
control protocol phase 9 built (`changes/archive/2026-09-23-add-rig-jvm-host`) carries none of the suite's
behaviour. Verifying the move against the tree also showed that the JVM host is not composed like the iOS app:
the status host and its subscriptions are assembled by hand in four places, and two of them have already
drifted (no version-refusal source, no push registration), which a protocol-driven suite would inherit
silently.

## What Changes

- **One shared host composition.** A new module `:app:composition` exposes `snapSyncHost(scope, AppPorts)`:
  it calls `snapSyncApp`, builds the status host from the core's read-models, and installs the permission and
  push-registration subscriptions. `AppPorts` gains the App Store URL, the time zone and the display clock; the HTTP
  client's rejected/version/served callbacks become one `BackendVerdicts` object the core exposes. `SnapSyncRoot`,
  the JVM rig host, the desktop world harness and the inbound-port contract fixtures all call it and supply
  only ports. The iOS change is behaviour-preserving.
- **Integration tests speak the protocol only.** Every test in `:test:integration` drives a fresh, in-process
  JVM rig host over the mini-edge through `:test:control`'s `RigClient`. No test names `World`.
  `:test:integration` becomes JVM-only: the Kotlin/Native run of the suite is dropped on purpose.
- **Assertions are observable outcomes only.** A test may assert what a system outside the app records —
  the backend (objects, union, manifests, device config, request counts), the photo library, the staging
  directory's files, the diagnostics reporter, pushes sent, the logs — plus `UiState`. Ledger and
  download-store content is internal and is not asserted, and that includes `RigState.ledger`.
- **About 40 new protocol entries.** `/device` and `/os` entries are classified on both hosts; `/user` stays one
  table, honoured by both:
  - `/user`: `rename`, `renameStatusConsumed`, `confirmSwitch`, `retryLoad`, `sendDiagnostics`, and setting
    the join/reconfigure form's range without committing it.
  - Device reads: the staging directory, the dumps the reporter received, album contents, and the original
    filename in the gallery view.
  - Backend reads and levers.
  - World levers: `clock/advance`, `selection/change`, `relaunch`, seeding with a chosen id, date and kind,
    failing the next enumeration, controlling the next import, and injecting log text.
- **A new APNs mock.** The mini-edge records the pushes it would send, so "a push reaches the device" is
  observable.
- **The world's attestation is on in the JVM host**, so the credential arm of push registration is
  reachable.
- **Nine tests are dropped.** Their point was an internal fact. The list and each one's remaining coverage
  are in the design. `ManifestPublisherContract` gains a clause for publish-version ordering, replacing two
  of them.
- **The inbound-port contract bindings move** (`EntryContractFixtures`, `EntryContractsJvmTest`,
  `EntryContractsSimulatorTest`) from `:test:integration` to `:test:world`'s test source sets. They keep both
  hosts (`JVM`, `IOS_SIM_KEXE`).
- **All-real journeys gate in `ios-contracts`.** The job installs deno and serves `api/` on
  `127.0.0.1:8080`. It boots a second simulator, and the journeys run through `:test:control` against both
  simulator apps:
  - create and join;
  - seed, upload, and the union visible to the second member;
  - that member downloads and imports.

  Each app's `GET /device` advertisement is checked once per run.
- **`:app:desktop:run` can mirror a remote host.** It attaches as a `:test:control` client and re-composes the
  real `StatusScreen` from the wire `UiState`. Taps become `/user` intents.
- **Not in this change.** `World.store`, `provision(eventId)` and `addForeignDevice(…, eventId, …)` stay, and
  so do their other callers (the desktop inspector, `:test:world`'s own tests). Retiring them belongs to the
  phase that extracts per-system mocks from `World`.

## Capabilities

### New Capabilities
<!-- none -->

### Modified Capabilities
- `testing-architecture`: the integration surface becomes protocol-driven and JVM-only, with observable-only
  assertions; the test-only module list changes; the control protocol becomes the behavioural suite's surface,
  and gains the new entries; `/user` stays one table both hosts honour.
- `harness-world-model`: integration tests no longer consume the world directly; the world gains the APNs mock,
  the relaunch, clock and selection levers, and hosts the inbound-port contract bindings.
- `full-stack-harness`: the harness composes through the shared host composition, and can mirror a remote
  host.
- `module-architecture`: "One shared composition" extends to the status host and its installs; the module
  set gains `:app:composition`.
- `ios-ci`: `ios-contracts` also runs the all-real journeys against local deno on two simulators and checks
  each host's advertisement.

## Impact

- **Modules:**
  - `:app:composition` (new);
  - `:app:ios` (`SnapSyncRoot` delegates host assembly);
  - `:domain:compose` (`AppPorts` fields);
  - `:test:rig` (vocabulary, JVM hooks, `/user` table, app-host hooks);
  - `:test:world` (levers, APNs mock, relaunch, entry bindings);
  - `:test:integration` (rewritten, JVM-only);
  - `:test:control` (client helpers);
  - `:app:desktop` (composition and mirror);
  - `:test:contracts` (one new clause).
- **CI:**
  - `ios-contracts` gains deno setup, a second simulator and the journeys; this will be measured, estimated at
    +3–5 min;
  - `ios-test` loses the integration suite's Kotlin/Native run.
- **Guards and diagrams:** `ModuleSetTest`, the shell guards (`KotlinShellGuardTest`) and the diagrams are
  regenerated.
- **Not touched:** production behaviour. The iOS change is behaviour-preserving; no user sees it, so the
  changelog label is `internal`.
- **Overlap:** the `upload-job-contracts` branch (`ContractCoverageTest`, `Host`, rig verbs) and the
  `os-recipe-timeouts` branch (`World.kt`, a new integration test). Expect a rebase.
