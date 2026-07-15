## ADDED Requirements

### Requirement: Developer launch-environment forge-state trigger

The iOS app SHALL read a `SNAPSYNC_FORGE_STATE` variable from the process environment
**once per process launch** and, when it is present and names a **recognized** forge
state, SHALL assemble a `StatusContainerHost` from **forged sources** for that state — via
a shared **forge factory** (`:domain:presentation`, `commonMain`) — and render the
screen from that host's `container.stateFlow`, exactly as the production shell renders its
live container. The forged screen SHALL therefore render **live** `UiState` from a real
`StatusContainerHost`, **not a static `UiState`**, preserving the shell invariant; the
trigger substitutes the container's **inputs**, never its output.

While a forge state is active, the app SHALL NOT assemble the live stack: the OS-lifecycle hooks
that would boot it (foreground/background scene transitions, remote-notification and push forwarding)
SHALL be inert, because the unsigned simulator the screenshots run in has no App-Group ledger
container, no App Attest, no photo-library grant, and no backend — and touching any of them would
crash the process. Rendering the forged host SHALL be the process's only significant work.

The forge factory SHALL map a recognized state name to forged source values that drive the
real reduction (`StatusContainerHost`) to the intended frame, and SHALL produce **only
frames the real reduction can reach** — it SHALL NOT fabricate a `UiState` the production
reduction never emits. The factory SHALL forge only the inputs the state requires
(typically the permission source, the config source, and the sync-status source) and SHALL
rely on the container's benign production defaults for the rest (e.g. `AlwaysAttested`,
`InMemoryDownloadStatusSource`), so a settled `Joined(InSync)` frame is reached without a
backend, an attestation token, or photo-library access. The recognized state names and
their forged inputs SHALL live in the factory (under test in `commonTest`, running on both
JVM and `iosSimulatorArm64`); `:app:ios` SHALL only read the variable and mount the
factory's host, introducing no state-selection or `UiState`-construction logic in the
wiring-only shell and performing no parsing in Swift.

The trigger SHALL be applied **at most once per process**: it SHALL NOT re-apply on Compose
view or view-controller recreation within the same process. When the variable is **absent**,
the app SHALL behave exactly as without this feature — it SHALL assemble and render the live
production stack (`SnapSyncRoot`) with no forge side effect. When the variable is present but
names an **unrecognized** state, the app SHALL produce no forge side effect and SHALL fall
back to the live production stack.

The trigger SHALL rely on the fact that a process-environment variable is only injectable via
a developer launch (e.g. `pymobiledevice3 developer dvt launch --env`, or a `simctl` launch
`--env`); launches from SpringBoard or TestFlight carry no such variable, so the trigger is
inert in production **with no compile-time guard**.

#### Scenario: A recognized forge state renders that frame live
- **WHEN** the app is cold-launched with `SNAPSYNC_FORGE_STATE` set to a recognized state
  (e.g. `in_sync`)
- **THEN** the app assembles a `StatusContainerHost` from the factory's forged sources for
  that state and renders `container.stateFlow`, showing the corresponding frame
  (e.g. `Joined(SyncHealth.InSync)` with the forged event name)

#### Scenario: The forged screen is the live container, not a static UiState
- **WHEN** a forge state is active
- **THEN** the rendered screen is the shared `StatusScreen` observing a real
  `StatusContainerHost.container.stateFlow` — the same path the production shell uses — and
  no static `UiState` is passed to the screen

#### Scenario: The factory only produces reduction-reachable frames
- **WHEN** the forge factory maps a recognized state name to forged sources
- **THEN** the resulting frame is one the production reduction (`StatusContainerHost`) can
  itself emit from those inputs, and the factory constructs no `UiState` the real reduction
  never produces

#### Scenario: A settled frame needs no backend, attestation, or photo access
- **WHEN** the `in_sync` state is forged
- **THEN** the container reaches `Joined(SyncHealth.InSync)` using the benign default
  `attestedSource` and `downloadSource` with only permission, config, and sync-status
  forged — with no network call, no attestation token, and no photo-library access

#### Scenario: Forge mode does not boot the live stack
- **WHEN** a forge state is active and the app's scene transitions to foreground (or background)
- **THEN** the OS-lifecycle hook is inert — it assembles no live stack, opens no ledger, requests no
  attestation, reads no photo library, and makes no network call — so the process only renders the
  forged screen

#### Scenario: Absent variable renders the live production stack
- **WHEN** the app is launched from SpringBoard or TestFlight with no `SNAPSYNC_FORGE_STATE`
  in its environment
- **THEN** no forge side effect occurs, the live production stack (`SnapSyncRoot`) is
  assembled and rendered, and behavior is identical to the app without this feature, with no
  compile-time flag distinguishing the build

#### Scenario: Unrecognized value falls back to the live stack
- **WHEN** the app is cold-launched with `SNAPSYNC_FORGE_STATE` set to a value the factory
  does not recognize
- **THEN** no forge side effect occurs and the app assembles and renders the live production
  stack

#### Scenario: The trigger applies at most once per process
- **WHEN** a forge state is active and the Compose view or view controller is recreated
  within the same process
- **THEN** the trigger is not re-applied, and a subsequent **cold launch** with the variable
  still set forges again in the fresh process
