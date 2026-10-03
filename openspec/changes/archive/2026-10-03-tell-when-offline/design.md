# Design

## Context

See proposal.md (Why). The behaviour was settled in an interview (2026-10-03); this records the choices and
what shapes their implementation.

What exists:

- **The port, already merged ahead of this change** (`internal(network)`, commits `42f211921` and `d92eb1b46`):
  `NetworkAccess` (`ONLINE · OFFLINE · BLOCKED`) and `NetworkMonitor.watch(): Flow<NetworkAccess>`, a cold flow whose
  first value is the truth; `IosNetworkMonitor` over `nw_path_monitor` (app-only), `AndroidNetworkMonitor` over the
  default-network callback, `NetworkMock`, and `NetworkMonitorContract`. Nothing composes it yet.
- **Measured** (contract recordings and device tests): Android, API 36 emulator, live in CI: online, airplane mode
  and a package denied by the `OEM_DENY_3` firewall chain all read correctly; a blocked app's `activeNetwork` is null
  while its network info reads `BLOCKED`. SE2 / iOS 26.6.2, recorded: Wi-Fi reads `satisfied`, airplane mode
  `unsatisfied / notAvailable`. **iOS BLOCKED (`cellularDenied`) is unmeasured**: no phone the project drives has a
  SIM. Its mapping is unit-tested and documented on the adapter.
- **Where the outcome lands today**: `UiState.layer` is `CreateEvent(error)`, `JoiningEvent(phase)` (whose
  `JoinPhase.LoadFailed` already says "Check your connection") or `Joined(… SyncHealth)`. The status line's ladder
  lives in presentation; `SyncHealth.NeedsAccess` is the only tappable rung. Foreground work is the `Foreground` flow,
  started by `lifecycleHandlers.onForeground`, which then hands the leftover work to the tail.
- **Zone laws that shape it**: presentation sees `model` and feature read-models only; flows import `model` and
  `feature` only, every port touch injected from `compose/`; a port lives in `AppPorts`/`DevicePorts` and nowhere
  else (`docs/architecture.md`).

## Goals / Non-Goals

**Goals:**
- One debounced, foreground-only network state in the core, read by presentation for all three screens.
- The return of the network resumes the foreground work and reloads a join whose details failed to load.
- Every new state reachable without a device: the mock's lever in the world harness and the rig.

**Non-Goals:**
- A reachability probe of our server, or any change to how a failed request is reported.
- Background restrictions (Android Data Saver / background data off, iOS Low Data Mode) — a later change with its
  own message ("syncs only while SnapSync is open"), measured first.
- Warning that mobile data is off while the device is on Wi-Fi.
- The upload extension: it uploads through PhotoKit, which waits for a network by itself, and has no screen.

## Decisions

### D1 — A feature-owned watch, started and stopped by the lifecycle flows

A feature (`feature/status`, beside `StatusCountsPoller`) owns the watch: `start()` begins collecting the port's
flow, `stop()` cancels the collection, and a read-model (`NetworkStatusSource` in `feature/status/readmodel`) exposes
`StateFlow<NetworkAccess>`. The `Foreground` flow starts it and the `Background` flow stops it, exactly as they start
and stop the counts poller — so the platform's monitor runs only while the app is in front, which is the cold port's
reason to be cold.

- *Alternative: an always-on `StateFlow` collected from composition.* Rejected: the monitor would run through every
  background launch for a screen nobody sees, and the port was made cold for this.
- *Alternative: collect in presentation.* Rejected: presentation cannot see ports or services, and the return-to-
  network trigger (D4) is not presentation's.

On `stop()` the state resets to `ONLINE`, so a return to the foreground never shows a stale notice; the next
collection's first value replaces it.

### D2 — Asymmetric debounce: slow to warn, instant to clear

A non-`ONLINE` reading becomes the published state only after it has held for 5 seconds; `ONLINE` is published at
once and cancels a pending warning. A change between `OFFLINE` and `BLOCKED` after a warning is shown is published at
once (the cause changed, not the presence). The first value after `start()` follows the same rule, so opening the app
offline shows the notice after the grace period — the interview's choice over "immediately at launch". The 5 seconds
is a constant of the feature, not a spec value (the spec says "a few seconds").

Measured on the SE2 (iOS 26.6.2, 2026-10-03, rig build, real network, mocked backend): with the app on the joined
screen, airplane mode on (Wi-Fi off) showed "You're offline" in the status line, and after Leave the create screen
showed it below a disabled Create; airplane mode off cleared it at once. Toggling airplane mode from Control Center
makes the app inactive and active again, so the watch stopped and restarted: the notice came back through the
foreground's own reading, and its return arrived as a foreground entry (which runs the same work) rather than as
`[onNetworkReturned]`. The return trigger fires only for a network that comes back while the app stays in front —
pinned on the JVM host by `NetworkIntegrationTest`. A side effect worth knowing: pulling down Control Center while
offline clears the notice and shows it again after the grace when the app is back in front.

The grace period is why the create and join failure paths stay as they are: a tap inside it fails with today's
"server could not be reached" (spec `create-event`, scenario "Offline create reports the server as unreachable").

### D3 — Presentation folds one state into all three layers

- **Joined**: a new rung `SyncHealth.NoNetwork(blocked: Boolean)`, second in the ladder (below `NeedsAccess`, above
  `NotStarted`). It replaces `Unattested` whenever both hold, by rank alone — no special case.
- **Create**: `Layer.CreateEvent` gains `network: NetworkNotice?` (`Offline` / `Blocked`). While set, the hint line
  shows it instead of `error`, and Create is disabled; `error` is kept untouched underneath, so it reappears when the
  network returns (spec: "the line shows … the failure message an earlier attempt left").
- **Join**: `Layer.JoiningEvent` gains the same `network` field. While set, Join is disabled and the notice shows;
  a `LoadFailed` phase renders as the network notice with Cancel only (no Retry).
- **Tap**: the blocked notice's action and the blocked status line both dispatch the existing `openSettings` command
  (`SystemUi.openSettings`), so no new command or port is needed.
- **Copy** (short, the same on every screen): blocked — "Network blocked for SnapSync – Open Settings"; offline —
  "You're offline". Copy lives in `:ui:screens`, not in the spec.

*Alternative: a separate full-screen network layer.* Rejected in the interview: it would hide the form the user
filled in, and on join it would hide which event the invite was for.

### D4 — The network's return is a trigger

When the debounced state goes from non-`ONLINE` to `ONLINE` while in the foreground:

- **Core work**: `compose/` runs the same work `onForeground` does — the `Foreground` flow, then the tail — under a
  new `TailTrigger.NETWORK` and its own entry-point label (`[onNetworkReturned]`), so the device log tells it apart
  from an app-open. It does not re-register push or take a new background-time hold beyond what the tail takes.
  Wiring it as its own handler, not by calling `onForeground`, keeps "foreground" meaning what the OS said.
- **Join reload**: presentation, on the same transition, reloads a pending join whose phase is `LoadFailed`
  (the existing `onRetryLoad` path). It reloads only on the transition, so a `LoadFailed` caused by a server outage
  while online still waits for the user's Retry.

*Alternative: let the existing triggers (next foreground, push, heartbeat) pick the work up.* Rejected in the
interview: the status would sit stale on screen until the user left and came back.

### D5 — Composition and the mocked system

- `NetworkMonitor` joins `AppPorts` and `DevicePorts` (app process only; `ExtensionPorts` does not take it). The iOS
  root builds `IosNetworkMonitor`, the Android root `AndroidNetworkMonitor`; `app/android`'s manifest declares
  `ACCESS_NETWORK_STATE` (a normal permission, granted at install — not a prompt).
- `MockDevice` gains `network = NetworkMock()`; `MockedSystem` gains `NETWORK("network", …)`; `MockState` persists its
  one value; the JVM root, the rig's launch-time adapters (iOS and Android) and `MockLevers` expose it. The rig serves
  `POST /device/network?access=online|offline|blocked`, honoured only where the network is mocked, refused (409)
  otherwise, like every operator lever. The world harness gets the same lever in its inspector.

## Risks / Trade-offs

- [iOS BLOCKED is unmeasured — the main case this exists for] → Mapping unit-tested and documented on the adapter;
  re-record the contract with `?network=blocked` support once the SE2 has a data SIM (a follow-up task, not a gate).
- [Android reads absence through the deprecated `activeNetworkInfo`] → Pinned by a device test that fails the day the
  platform stops answering `BLOCKED` there, rather than silently reporting offline.
- [A 5-second grace lets an early tap fail with "server unreachable"] → Accepted: that message is true, and the
  notice follows within seconds.
- [The network "returns" but the server is still unreachable] → The resumed work fails as it does today, and the
  existing failure paths report it; nothing here claims the server is up.
- [Captive portals: a Wi-Fi with no internet reads ONLINE] → Out of scope (no reachability probe); transfers fail as
  today.
- [A second trigger running the foreground work] → It runs only on a debounced transition in the foreground, and the
  `Foreground` flow and the tail are already safe against overlapping entries (the tail is single-flight).

## Open Questions

- Does SnapSync's own Settings page show a Mobile Data switch on an iPhone with a SIM before the app has ever used
  mobile data? Measure on a SIM-equipped phone; it does not change the design (the action opens that page either way).
