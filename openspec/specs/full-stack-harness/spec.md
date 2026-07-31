# full-stack-harness Specification

## Purpose

The **world harness** at `:app:desktop:run`: the real `StatusScreen` in a phone frame on the left, whose
counts *emerge* from the real stack running against `:test:world` — never forged — and a right-pane world
inspector that drives that world (presets, an operator-triggered `process()`-shaped cycle, the gallery and
backend, the upload-job queue and downloads, failure levers).

It exists because the forge harness (`desktop-test-harness`) can only ever *type in* what the screen shows,
and `:test:world`'s only other consumer (`:test:integration`) asserts without rendering. Neither lets a human
**watch the real stack behave**. Here the operator plays the OS — nothing auto-runs — so every emergent
behavior (a rejoin seeding completed rows, backpressure on the job cap, a download suppressing an echo) can
be produced on a laptop, with no device and no network.

The inspector is **test equipment**: raw Material 3, never the `App*` design system, and it holds no logic —
the real logic lives in `:test:world` (`harness-world-model`).

Decision record: `changes/archive/2026-07-03-add-full-stack-harness`; the bug-report affordance
driving the real dump command: `changes/archive/2026-07-31-add-bug-report-description`.
## Requirements
### Requirement: Dual-pane full-stack harness at `:app:desktop:run`

The full-stack harness SHALL be a Compose desktop **application** whose `main()` lives in the
`:app:desktop` module (run task `:app:desktop:run`), so `./gradlew :app:desktop:run` launches it.
It SHALL render two panes side by side: on the left, the real shared `StatusScreen` inside the
module's `PhoneFrame` via the module's `StatusPane`; on the right, a world-inspector control
panel. Since migration step 10, `:app:desktop` is the **one** desktop module: it hosts the shared
pane library (`PhoneFrame` + `StatusPane`) **and** both harness applications — the forge harness
(capability `desktop-test-harness`) runs from the same module via the `:app:desktop:runForge`
task. The full-stack `main()` SHALL compile to a class **distinct** from the forge harness's
`app.snapsync.desktop.MainKt`, so the two entry points never collide within the module.

#### Scenario: The full-stack run task opens both panes

- **WHEN** `:app:desktop:run` is launched
- **THEN** the window shows the real status screen inside the phone frame on the left and the
  world-inspector control panel on the right

#### Scenario: The forge harness is unaffected

- **WHEN** `:app:desktop:runForge` is launched after this change
- **THEN** the forge harness still opens (no entry-point-class collision), over the same
  `PhoneFrame` + `StatusPane` pane library

### Requirement: Left-pane status emerges from the real stack

The left pane's `StatusScreen` SHALL be driven by the world's **real** platform-agnostic sources, not
forged cells: the real `LedgerBackedSyncStatusSource` (`world.syncStatusSource(scope)`), the real
store-backed download status (`StoreDownloadStatusSource` over the world's download store), the real
`CreateEvent` (an `EventCreator`), the world's creation-status and permission sources, and the world's
config source. No forged `SyncStatus`/`DownloadProgress` cell SHALL exist in the full-stack harness —
every count shown on the phone frame SHALL be computed by the real projection over real world state.

#### Scenario: A completed upload moves the phone-frame counts

- **WHEN** an own asset is added, its upload job created and completed, and the extension is invoked
- **THEN** the object is present in the world's backend store and the left pane's completed count
  advances toward the total — because the real projection recomputed, not because a count was forged

#### Scenario: The status counts cannot be typed in

- **WHEN** the harness code is inspected
- **THEN** the left pane's sync and download sources are the world's real `LedgerBackedSyncStatusSource`
  and `StoreDownloadStatusSource`, with no writable `SyncStatus`/`DownloadProgress` override cell

### Requirement: Operator-driven Invoke-extension cycle

Nothing SHALL auto-run: the operator plays the OS. The inspector SHALL provide a primary **Invoke
extension** action that runs exactly one extension invocation — the `process()`-shaped upload cycle
(reload config → reconcile → build config → run the real cycle, via the world's runner) **and** a
download reconcile over the event union — and then refreshes the status and download sources so the
left pane reflects the new world state. It SHALL also provide an **Expire change token** action that
forces the next invocation's discovery to be a full enumeration.

#### Scenario: Invoke runs one real process cycle

- **WHEN** the operator presses Invoke extension with an asset pending upload and its job completed
- **THEN** the real upload cycle records the asset `COMPLETED`, the download reconcile runs, and the
  left pane updates from the refreshed real sources

#### Scenario: Expire token forces a full enumeration

- **WHEN** the operator presses Expire change token and then Invoke extension
- **THEN** the next discovery is a whole-library full enumeration and the cycle reconciles via the
  retain path

### Requirement: Counts are pull-based — the operator refreshes after each mutation

Every world-mutating inspector action SHALL end by refreshing the gallery source, the ledger-count
source, and the download source, so the real `LedgerBackedSyncStatusSource` projection re-emits — the
operator playing the same foreground-refresh the iOS composition root performs. This is required
because those gallery, ledger-count, and download sources update their `StateFlow`s only on
`refresh()`. Permission changes SHALL reflect without a refresh (the permission source is a live
`StateFlow`).

#### Scenario: A gallery edit reflects only after refresh

- **WHEN** the operator adds or removes a gallery asset
- **THEN** the controller refreshes the completed and in-flight sources and the phone-frame total
  updates to the new gallery size

#### Scenario: Permission reflects immediately

- **WHEN** the operator sets the permission segment to a new state
- **THEN** the status screen's active/blocked treatment updates without any explicit refresh

### Requirement: World-inspector controls drive the real world through a single controller

The inspector SHALL drive `:test:world`'s public control surface through a **single** controller,
with one named method per control and **no** inline world mutation in composables (mirroring the forge
harness's `PanelController`). It SHALL cover: **Enrollment** — a 4-state permission segment
(`NOT_DETERMINED`, `DENIED`, `LIMITED`, `GRANTED` — the partial grant is a first-class state,
capability `limited-photo-access`), an armed
next-request outcome (the fake `PhotoAccessRequester` resolves `request()` to the armed grant/deny), a
joined-event-id readout, and Re-provision / Create event / Leave; **Gallery ▏ Backend** side by side —
editable own-asset rows (add/remove; imported rows badged upload-suppressed via the download store's
suppressed-id set) and stored objects grouped by device (own plus "+ Inject device" for a foreign
contributor's complete assets); **Upload jobs ▏ Downloads** side by side — an upload queue of
pending/retry rows with per-job Complete and Fail carrying a chosen engine `UploadError` (Network /
Http / Cancelled / Unknown) and a job-limit indicator, and a list of pending foreign download
resources each with a Stage action; and **Failure levers** — a backend-offline toggle, the job-limit,
and an import-failure arm (the per-job `UploadError` living on the queue). There SHALL be **no**
`DownloadError` picker — a non-staged download simply remains PENDING (the only download-side failure
lever is the armed import failure).

#### Scenario: Every control maps to a world call through the controller

- **WHEN** the inspector composables are inspected
- **THEN** each control invokes a named controller method that calls the world's public surface
  (`runUploadCycle`, `platform.expireToken`, `permission.set`, `provision`, `addOwnAsset`/`removeAsset`,
  `addForeignDevice`, `platform.completeJob`/`failJob`, `jobLimit`, `stageAllDownloads`,
  `backendOffline`, `failNextImport`), and no composable mutates world state inline

#### Scenario: Fail drives the real retry chain

- **WHEN** the operator fails a created upload job with a chosen `UploadError` and invokes the extension
- **THEN** the real engine answers retry and the job's attempt increments — visible in the upload queue

#### Scenario: A foreign asset flows download → import → suppression

- **WHEN** the operator injects a foreign device, invokes the extension (union → pending download),
  stages the download, and invokes again
- **THEN** the foreign asset is imported into the gallery, badged upload-suppressed, and the own-device
  cycle creates no upload job for it

#### Scenario: No download-error picker

- **WHEN** the Downloads column is inspected
- **THEN** it offers a Stage action only — a non-staged download stays PENDING and there is no
  `DownloadError` control

### Requirement: Presets rebuild a fresh world

Each inspector **preset** SHALL construct a fresh `World`, apply a short setup script, and swap it in —
with the left pane re-binding its status host to the new sources (keyed on a world generation) —
because the world is a live stateful stack (backend byte store, ledger, gallery) whose deposited state
cannot be un-set by resetting a cell. The presets SHALL be: **Clean** (nothing joined), **Enrolled**
(event provisioned with own assets present), **Fresh join** (a fresh event, own assets present,
nothing stored yet), **Re-provision (dedup)** (own assets already stored, then provisioned so
reconcile seeds them `COMPLETED` and a subsequent invoke uploads nothing new), and **Foreign
download** (event provisioned with an injected foreign device's complete assets in the union).
Incremental controls SHALL mutate the current world in place (no world rebuild).

#### Scenario: Clean resets to an empty world

- **WHEN** the operator selects the Clean preset
- **THEN** a fresh world with nothing joined replaces the current one and the left pane rebuilds its
  status host against the new sources

#### Scenario: Re-provision dedup uploads nothing new

- **WHEN** the operator selects Re-provision (dedup) and invokes the extension
- **THEN** reconcile seeds the already-stored assets `COMPLETED` and the cycle creates no new upload job

#### Scenario: Incremental edits keep the world

- **WHEN** the operator adds a gallery asset after selecting a preset
- **THEN** the current world is mutated in place (not rebuilt) and the change is reflected after refresh

### Requirement: Engine console footer

The inspector SHALL include an engine console footer that streams the real stack's log output, so the
operator can see why a cycle behaved as it did (not only its end state). It SHALL install a Kermit
`LogWriter` that funnels the stack's existing log lines into a bounded, scrollable footer, and the
controller SHALL additionally append an explicit line after each Invoke carrying the cycle's
`CycleResult` (`COMPLETED` / `PROCESSING` / `FAILED`) — because that return value is not itself logged
and is what explains a surprising no-op. Installing the console SHALL require **no** change to
`:test:world` or production code (it is a pure read of existing Kermit output). A **Clear** action
SHALL empty the console; a preset's world-rebuild SHALL NOT reset it (the console is a session
timeline, not world state).

#### Scenario: A short-circuited invoke is explained

- **WHEN** the operator invokes the extension with no config provisioned (the cycle short-circuits)
- **THEN** the console shows a line carrying the cycle's `CycleResult`, distinguishing a real no-op
  from a genuine completed cycle that created no jobs

#### Scenario: Clear empties the console but not the world

- **WHEN** the operator presses Clear
- **THEN** the console lines are emptied while the current world state and the phone-frame counts are
  unchanged

### Requirement: The inspector is test equipment — real logic lives in `:test:world`

The world-inspector panel and its controller SHALL be test equipment: raw Material 3 only, never
`App*` (the design-system Material-3 containment exemption applies as it does to the forge harness),
and they SHALL carry **no** tests — they are a thin wiring shim over `:test:world`'s already-tested
control surface and the presentation/status modules. The left pane SHALL be the shipped `StatusScreen`
composable, never a copy. No new testable domain logic SHALL be introduced in `:app:desktop:run`.

#### Scenario: Material 3 is contained to the inspector

- **WHEN** the harness modules' imports are inspected
- **THEN** Material 3 appears in the inspector panel (test equipment) but not in any `App*` signature,
  and the left pane renders the real `StatusScreen`

#### Scenario: No logic parked in the app shell

- **WHEN** the full-stack harness code is inspected
- **THEN** all upload/download/status/reconcile logic is invoked from `:test:world` and the
  presentation/status modules, with only Compose wiring and the controller shim added here

### Requirement: The inspector's Create event control supplies a start date

The world inspector's **Create event** control SHALL supply a `startsAt` alongside the event name, the
real `POST /events` now requiring one (capability `event-creation`). It SHALL let the operator choose
between a start in the **past** (the event has begun — the ordinary case) and one in the **future** (the
event has not begun), so both sides of the floor can be driven through the **real** stack rather than
forged.

Driving the not-started case here — rather than only in the forge harness — is what proves the *theorem*
the design rests on: that a future start uploads nothing not because a gate refuses, but because the
clamped cutoff admits no photo. The forge harness can only show the status line; only the full-stack
world can show the empty object store behind it.

#### Scenario: Creating an event supplies a start date through the real client
- **WHEN** the operator activates Create event
- **THEN** the real `HttpEventCreation` posts a canonical `startsAt` with the name, and the
  mini-edge registers a marker carrying it

#### Scenario: A future-start event uploads nothing through the real stack
- **WHEN** the operator creates an event starting in the future, joins it, adds own assets to the
  gallery, and invokes the extension
- **THEN** no upload job is created and no object appears in the backend column — the left pane showing
  the not-started status line beside an empty store

#### Scenario: Uploads flow once the start is in the past
- **WHEN** the operator creates an event whose start is in the past, joins it, adds own assets, and
  invokes the extension
- **THEN** upload jobs are created and objects land in the backend column exactly as before this change

### Requirement: Phone-pane theme toggle

The world inspector SHALL provide a Light/Dark toggle that forces the **phone pane's** theme
deterministically, so the shipped dark skin can be reviewed against the real stack without a device.
The toggle SHALL drive the design system's test-only theme override (`LocalDarkThemeOverride`) around
the rendered `StatusScreen` only — via the shared `StatusPane`'s `darkThemeOverride` input — so the
phone pane renders the real `AppTheme` in the chosen theme. The toggle SHALL NOT depend on the host OS
setting, SHALL default to Light, and SHALL leave the world inspector's own raw-Material 3 chrome
unthemed by it. The toggle is a pure view control: it SHALL NOT mutate world state and SHALL survive a
preset rebuild (fresh world) unchanged.

#### Scenario: Toggle forces the phone pane dark
- **WHEN** the operator sets the theme toggle to Dark
- **THEN** the phone pane's status screen renders in the dark color scheme, independent of the host OS setting

#### Scenario: Toggle is independent of world state
- **WHEN** the operator applies a preset that rebuilds a fresh world
- **THEN** the chosen theme is retained and no world state is mutated by the toggle

### Requirement: The bug-report affordance drives the real dump command

The left pane SHALL wire the **real** bug-report command from the composed app core (capability
`diagnostic-logging`), not a stub: a double-tap on the app-name label opens the bug-report sheet, and
sending assembles a real dump over the world's real ledger, download store, config, permission and
device-log sources, delivered to the world's reporter.

Because the world composes a reporter that reports itself as configured, the affordance SHALL be
present in this harness — unlike the forge harness, where it is rendered UI-only. The assembled dump
SHALL be inspectable as a world outcome, so an operator or an agent driving the harness headlessly can
confirm what a report would carry without a device.

The sheet SHALL be reachable in every state the left pane can show, since the affordance rides on the
app-name label the shared layout always renders.

#### Scenario: Sending a report records a real dump
- **WHEN** the operator double-taps the app-name label, writes a description, and sends
- **THEN** the world's reporter records exactly one dump carrying that description together with the
  state, ledger and log sections assembled from real world state

#### Scenario: The dump reflects real world state
- **WHEN** a report is sent after uploads have completed in the world
- **THEN** the recorded dump's ledger counts match the world's real ledger rather than any forged value

#### Scenario: Cancelling records nothing
- **WHEN** the operator opens the bug-report sheet and cancels
- **THEN** the world's reporter records no dump and no world state changes
