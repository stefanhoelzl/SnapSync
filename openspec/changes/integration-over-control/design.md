## Context

Phase 9 (`changes/archive/2026-09-23-add-rig-jvm-host`) gave the control channel `:test:rig` a JVM host over
`:test:world`, a backend seam (mini-edge | deno), and the typed JVM client `:test:control`. Its D8 said the
in-process integration suite "remains the behavioural suite until the next change moves them". This is that
change.

The tree was verified before this proposal (2026-09-23):

**The integration suite.** `:test:integration` holds 113 behavioural `@Test`s in 24 classes, plus four
inbound-port contract bindings (`EntryContracts*`). It runs on `jvm` and `iosSimulatorArm64`. Every test
builds a `World` and reads it directly: `World.store`, ledger rows through `LedgerReads.kt`, fakes' counters.

**Host assembly is duplicated, and has drifted.** Four places assemble the status host by hand:
- `SnapSyncRoot` (iOS);
- `JvmRigHost.compose`;
- the desktop `StatusPane`/world harness;
- `EntryContractFixtures`.

Measured against `SnapSyncRoot.kt:533-590`, the JVM host lacks four pieces:
- `installPushRegistration()`;
- `attested`;
- `versionRefusal`;
- `appStoreUrl`.

So on the JVM host `UpdateRequired` is unreachable and push registration never runs.
module-architecture's "One shared composition" promises that a wiring difference is impossible; that holds
for `snapSyncApp`, not for the layer above it.

**The protocol today.**
- `/user` wires five commands and excludes about 25. Several of the exclusion reasons are false for the
  JVM host, and the one for `onRetryLoad` is false everywhere: re-opening the link is swallowed as a
  duplicate (`StatusContainerHost.kt:578`).
- The JVM host's `/device` levers are the world inspector's set.
- `gallery/seed` mints random ids at a fixed date (2026-06-01T10:00Z).

**The world already has most of the levers the tests pull.** `changeSelection`, `nowMillis`, the import
suspension/resume levers, `addScreenshot`/`addHdVideo`, `appVersion` and the neutral backend's
`setMinAppVersion`/`sweepEvent`/`manifestOf`/`unionOf` all exist. The cost is mostly exposure, not new
machinery.

**Settled upstream, and not re-opened here:**
- integration always runs on mocks;
- tests speak `:test:control` only;
- a fresh host per test, run sequentially;
- journeys gate in `ios-contracts`;
- the entry bindings follow the all-mock composition.

## Goals / Non-Goals

**Goals:**
- One host composition, called by every root. A host the protocol drives is then the iOS app's host.
- Every integration test drives a JVM host only through `RigClient`, and asserts only observable outcomes.
- Every dropped assertion is listed, with where its behaviour stays covered.
- A few all-real journeys gate on the simulator against local deno.
- The desktop harness can mirror any host.

**Non-Goals:**
- Retiring `World.store`, `provision(eventId)` or `addForeignDevice(…, eventId, …)`. Their other callers (the
  desktop inspector, `:test:world`'s own tests, `World`'s own `offline` lever) belong to the phase that
  extracts per-system mocks from `World`.
- Honouring the new world levers on the app host (mixing real and mocked systems is a later phase).
- Any production behaviour change.
- `:test:harness-driver`, `runForge`, `:app:ios:forge`.

## Decisions

### D1. The host composition moves into one function in a new module `:app:composition`

`snapSyncHost(scope, ports: AppPorts): ComposedApp` returns the `AppCore` and the `StatusContainerHost`:
1. It calls `snapSyncApp`.
2. On first touch of `host` — and only then, preserving the iOS root's timing, where a cold background wake
   that touches the core must install nothing — it:
   1. installs the permission and push-registration subscriptions;
   2. builds `StatusSources` from the core's read-models, all of them;
   3. constructs the host.

Every root supplies ports and nothing else:
- `SnapSyncRoot`;
- `World` (and through it the JVM host, the desktop harness and the entry fixtures).

**Details:**
- **The ports' extras.** `AppPorts` gains:
  - `appStoreUrl`;
  - `timeZone`;
  - `displayClock`. On a device it is the same `SystemClock` as `clock`. The world keeps `clock` pinned for
    the core's determinism and puts the screen on the wall clock, which is the world's existing deviation,
    now named in one field instead of re-made by each root.
- **The HTTP client's callbacks** (`onRejected`, `onVersionRefused`, `onServed`) become one object: an
  inbound port `BackendVerdicts`, which the core implements and exposes as `core.backendVerdicts`. The
  interceptor gains an overload taking it. Both roots pass `core.backendVerdicts`, so no root can wire two
  of the three callbacks.
  - Building ports from a function of the sinks was considered and rejected: it forces the iOS root's lazy
    web to be restructured for no extra guarantee.
- **Why a module.** The host lives in `:ui:presentation`, which may not name `compose/`, and `compose/` may
  not name presentation. `:app:ios` is the only module that sees both, and it is iOS-only and untested.
  `:app:composition` is the one join of the two, and it withholds each from the other's consumers. It joins
  the withholding group, carries no test source set, and holds no conditional. Its composition is exercised
  end to end by every protocol-driven test.
- **Alternatives considered.**
  - Removing the defaults from `StatusSources` and merging the installs: cheaper, but each root would still
    assemble the host.
  - Putting the assembly in `:test:world`: parity for the test side only, with iOS still hand-assembled.

  Chosen by the user.

### D2. `World` composes through `snapSyncHost` and exposes `host`

The world's `core` and `host` come from the one call. `JvmRigHost.compose` shrinks to building a world with
the rig's routing hook, and the desktop `StatusPane` and the entry fixtures read `world.host`. This answers
phase 9's open question: the assembly gets its own home, and it is not `:test:world`.

**Attestation in the JVM host.** On the mini-edge the JVM host builds its world with `attests = true`, as a
device attests. On deno it stays off, because the local backend attaches a dev fallback token and does not
model the credential exchange. The world's default is unchanged, so the desktop harness and `:test:world`'s
tests are unaffected.

### D3. The test fixture: one host per test, over the client only

```kotlin
@Test fun x() = rigTest { rig ->          // JvmRigHost.start("mini", 0) + RigClient; closed after
    val event = rig.createAndJoin(window = JUNE_2026)
    rig.seed(kind = "policy", n = 2)
    rig.os("photokit-ext/processRawValue")
    rig.device("jobs/complete")
    rig.awaitState { it.ui.health == InSync }
}
```

- **Helpers.** They live in `:test:integration`'s test source set and are built from `RigClient` calls
  only: create-and-join, seed, cycle, gallery ids.
- **The window.** Every upload test uses an event window that contains the seed's default date, because
  create's ceiling is at most start + 30 days.
- **Timing and ordering.** Waits poll `/device/state` (`awaitState`). Tests already run in real time
  (`runBlocking`), so no virtual time is lost. Tests run sequentially.
- **`:test:integration` becomes a JVM-only module** (`kotlin("jvm")`, `src/test`). Its dependencies are
  `:test:control`, `:test:rig`'s JVM variant (to start the host) and `:ui:presentation`'s types (through the
  client).
- **Nothing else is on its compile path.** It names no `World` type. Its compile classpath must not carry
  `:test:world`, `ports/`, `flow/` or `compose/`. The rig's own dependencies are `implementation`, the same
  construction that holds `:test:control`'s read-model rule. Because the host factory is exposed as a
  string-named backend, the test never names the world.

### D4. What a test may assert

**Observable** (asserted): what `UiState` shows, and what a system outside the app records:
- **the backend**: objects, union, manifests, device config, event existence and name, departed members,
  and request counts (publishes);
- **the photo library**: the gallery census, album contents, original filenames;
- **the operating system's upload jobs**: `/device/jobs`;
- **the staging directory's files**;
- **the diagnostics reporter's received dumps**;
- **the pushes the backend sent**;
- **the logs.**

**Internal** (never asserted): ledger and download-store content, including `RigState.ledger`'s counts;
in-memory feature state (`suppressedLocalIds`, `unconfirmedImports`, the gallery's admitted set); and call
counts on a mock whose real system records nothing a person could read, such as PhotoKit resolves.

`RigState.ledger` stays in the protocol for people and agents reading a host by hand. Tests do not assert
it.

### D5. The new protocol entries

**`/user`** stays one table both hosts invoke (phase 9's D3 is unchanged).
- **Added:** `rename`, `renameStatusConsumed`, `confirmSwitch`, `retryLoad`, `sendDiagnostics`, and
  `setRange` (the form's from/until, including an open end, without committing).
- **`sendDiagnostics` is null on a build with no configured reporter**, which is every rig build of the app,
  since no dev build carries a DSN. There the route answers `409` naming that, so the command cannot reach
  the operator's Bugsink. The world's reporter is configured, so the JVM host honours it.
- **The exclusion reason for `onRetryLoad`** is corrected by honouring it.

**`/device`** entries: each is honoured by the JVM host. The app host refuses each world lever with the
shared world-lever reason. Where an entry is a device fact the app host could read but does not yet wire, it
refuses naming exactly that.

| Group | Entries |
|---|---|
| Device reads | `staging` (file listing), `diagnostics/sent`, `album/contents`; `GalleryView` gains each asset's original filename (app host: from `PHAssetResource`) |
| Backend reads | `backend/union`, `backend/manifest`, `backend/device-config`, `backend/event` (exists + name), `backend/departed`, `backend/publishes`, `backend/pushes` |
| Backend levers | `backend/sweep`, `backend/min-app-version`, `backend/hold-leave` (+ release), `backend/fail-route` (device-files listing), `backend/deposit` (bytes, no ack), `backend/legacy-event` (no `startsAt`), `backend/refuse-credential` (next one) |
| World and OS levers | `clock/advance`, `selection/change`, `relaunch`, `app-version`, `gallery/fail-next-enumeration`, `import/suspend-next` (`afterCommit`), `import/resume`, `import/await-parked`, `logs/append` (app + extension) |
| Parameters on existing verbs | `gallery/seed` takes `id`, `date` and `kind` = `screenshot`/`hd-video`/`live-photo` (the app host refuses `id` and those kinds with a reason); `foreign-device` takes `filename`; `downloads/stage` returns while an import is parked |

- **Backend reads go through `World.neutral`**, so each answers "unavailable on this backend" wherever deno
  cannot honour it. On the mini-edge, the reads `NeutralBackend` lacks (departed, device config, pushes,
  event name) are added there.
- **Why so many entries.** Phase 9 expected new verbs to be rare. The measured reason there are about 40:
  under D4 almost every former internal assertion has an observable twin that needs a read.

### D6. `relaunch` is process death, modelled

`World.relaunch()` ends the current composition's scope and runs `snapSyncHost` again over the same
**durable** state:
- the ledger store, the download store, the config and secure stores;
- the staged files, the gallery and album map;
- the backend;
- the OS-held transfer sessions of both transfer doubles.

It then performs the cold-start sequence the iOS root performs. The world classifies each cell it holds as
durable or process memory, in one place. The JVM host's server already reads the core and host through
lambdas (`core = { world.core }`), so a relaunch changes nothing about the server.

This replaces the two-`World` constructions in `LostUploadAck`, `ColdDownloadRelaunch` and the
interrupted-import tests. It makes 8 tests expressible, and it makes "joined but never read" reachable
(a relaunch performs no status refresh).

### D7. The APNs mock

When the backend would send a push, the mini-edge records the push against the registered token. The world
reads the record out; it delivers nothing on its own, because the operator plays the OS. A test sees
`backend/pushes`, then fires `/os/app/onSilentPush` itself.

### D8. Tests that lose their point, and where their behaviour stays

| Test | Why it's internal | Stays covered by |
|---|---|---|
| BoundedTopUp `:51`, and `:23`'s wasted-resolve bound | PhotoKit resolve counts | `UploadCycleTest` `a_refusal_stops_the_pass_with_no_further_resolve`, `a_full_platform_reports_work_remaining` |
| CapTruncatedPublish `:48` | enumeration/resolve counts | `UploadCycleTest` `a_truncated_cycle_resumes_its_remainder_from_the_ledger_without_re_discovering` |
| InterruptedImport `:148` | `suppressedLocalIds` only; its observable twin is `:82`/`:162` | `DownloadControllerTest` `:589`, `:993` |
| ManifestVersion `:32`, `:53` | replay the publisher port directly | `api/test/v2.test.ts` (older refused, equal accepted), plus the **new** `ManifestPublisherContract` clause (D9) |
| ManifestVersion `:79` | a hook inside the ledger read | **uncovered**: the version-before-membership read order in `UploadCore` |
| UnreadStatus `:99` | a partial refresh no entry point performs | **uncovered** as composed; pieces in `InMemoryDownloadStatusSourceTest`, `LedgerBackedSyncStatusSourceTest` |
| Rename `:134` | a stale-id rename is no gesture | `RenameEventTest` `a result for a no-longer-current event persists nothing` |
| PushRegistration `:35` | no world at all; it is a publisher test | `PushTokenPublisherContract` `A_TOKEN_IS_PUBLISHED`, `A_ROTATED_TOKEN_IS_PUBLISHED_OVER_THE_LAST` |

**Reshaped, not dropped:**
- **ShareSet `:69`** becomes "re-scanning your own event creates no job".
- **PushRegistration `:112`**: the backend refuses the first registration's credential, so no device config
  lands. After a new credential, the config appears, with no second delivery.
- **PushRegistration `:146`**: no second attest mint.
- **StagedByteReclaim ×4** assert the staging directory's files. Their backlog is the state an upgraded install
  holds, written by the one flagged lever (see Open Questions).

**Weakened:** CycleEntryGate `:31`'s "the ledger is untouched" becomes "nothing uploaded, no job created".

Every other assertion on ledger rows moves to backend objects, jobs and `ui` health.

### D9. A new `ManifestPublisherContract` clause for publish-version ordering

An older publish landing after a newer one is refused and changes nothing; an equal version is accepted. It
binds the mini-edge (`Fake`) and the live deno backend, so a drift of the mini-edge from `api/` fails the
build. This is the "a real run finds a missing clause" rule, applied at the point where two integration
tests are dropped.

### D10. The inbound-port bindings move to `:test:world`

- **Where they go.** `EntryContractFixtures` goes to `:test:world`'s `commonTest`, `EntryContractsJvmTest` to
  `jvmTest`, and `EntryContractsSimulatorTest` to `iosSimulatorArm64Test`. `quietDiagnostics` moves with them.
- **What they read.** The fixtures read `world.host` (D2) rather than assembling one.
- **Why the move is not optional.** `ContractCoverageTest` counts a binding by its literal host, not by
  whether its module runs that target. A binding left in a JVM-only `:test:integration` would still be
  counted while `IOS_SIM_KEXE` silently stopped running it.
- **New dependency.** `:test:world`'s `commonTest` already depends on `:test:contracts`. It gains nothing new
  beyond `:app:composition`, which its main source set now needs anyway (D2).

### D11. Journeys: two simulators and local deno, in `ios-contracts`

**Runner setup:**
- `denoland/setup-deno` installs deno.
- `deno task dev:local` serves `api/` on `127.0.0.1:8080`, which the `local` deployment already bakes in. It
  runs backgrounded, with its output kept as evidence.
- One `curl` warms deno up, because a cold deno exceeds the app's 5-second HTTP timeout.

**Simulators:**
- `scripts/sim-contracts` already builds, signs and boots simulator A.
- A second simulator, B, is created, booted and granted **in parallel with A's boot**, on its own rig port.
- Both install the same build.
- A fresh runner and a fresh store make the local-backend skill's silent step (a reset after crossing
  backends) inapplicable.

**The three journeys** run as a Gradle JVM test task (`:test:integration:journeys`, outside `build`) over
`:test:control`. They get the two apps' URLs and the deno base from system properties. The task fails,
never skips, when any of those is absent.
1. **Create and join.** A creates an event and confirms the join, and reaches `Joined`.
2. **Own photos land.** A seeds `policy` photos (the admitted half is known from `/device/gallery`) and fires
   foreground. The admitted assets appear in A's device-files listing and in the event union, both read from
   deno's public HTTP surface by the adapter-layer Ktor clients.
3. **A member receives them.** B opens A's invite link download-only and confirms. Its gallery census grows by
   the admitted count and its download settles (`UiState`).

**Advertisement.** Each app's `GET /device` is read once. `unclassified` and `outsideVocabulary` must both be
empty.

**Why two simulators.** World levers are refused on the app host, and the world's foreign bytes (4 bytes
labelled HEIC) would not survive a real PhotoKit import. A second real member is the only honest foreign
device.

**Cost.** The job takes 8.5–14.5 min today. Estimated +3–5 min (a parallel boot, deno setup, about 1–2 min
of journeys). Measured in the change's CI runs and recorded here before archive.

### D12. The mirror: a remote `UiState`, re-composed locally

`:app:desktop:run -Psnapsync.attach=<url>` swaps the in-process world for a `RigClient`:
- **Left pane:** the real `StatusScreen`, rendered from `RigState.ui`, which it polls.
- **Right pane:** a read-only mirror inspector. It shows the host's `GET /device` advertisement and the
  latest state.
- **Taps become `/user` intents.** They are built in the harness's test-equipment code, which is exempt
  from "Shells are wiring only". A tap with no `/user` intent (a surface that opens or dismisses in the
  remote container) is inert and logged, never faked locally.
- **Reachability.** The rig binds loopback only. A remote host is reached over `ssh -L` (a Mac simulator) or
  `usbmux forward` (a phone), which the harness does not arrange.

### D13. Dropping the suite's Kotlin/Native run

`ios-test` ran the 113 tests compiled to Kotlin/Native over fakes. What that caught — Native-only
behaviour of common code — is still caught elsewhere:
- by `:domain`'s own `iosSimulatorArm64` unit tests;
- by the simulator app itself under the contracts and journeys, which runs the composed code natively over
  real adapters.

None of the module's 96 commit subjects mentions a failure that only showed up on Native.

## Risks / Trade-offs

- **[Risk] The composition change touches `SnapSyncRoot`, the one untested module.**
  - Mitigation: the change is behaviour-preserving by construction, since the same calls move behind one
    function.
  - The shell guards pin the shell's decisions.
  - A dispatched TestFlight build is smoke-checked on device before merge (join, upload, the version-refusal
    screen with a raised minimum).
- **[Risk] About 40 entries make the vocabulary large, and each must be classified by both hosts.**
  - Mitigation: `GET /device` already fails naming an unclassified entry, and the JVM host's tests run that
    in `build`.
  - `ios-contracts` now reads the app host's advertisement on every push.
- **[Risk] `relaunch` mis-classifies a cell** (process memory treated as durable, or the reverse), which
  would make relaunch tests lie.
  - Mitigation: one classified list in `World`, plus a `:test:world` test pinning that each durable cell
    survives and each memory cell is fresh.
- **[Risk] Journey flakiness on the runner** (simulator boot, first-launch alerts).
  - Mitigation: reuse `sim-contracts`' readiness waits and evidence (screenshot, app log). Each journey
    waits on `awaitState` with a stated bound, never a sleep.
- **[Trade-off] Two behaviours become uncovered as composed:** ManifestVersion `:79` and UnreadStatus `:99`.
  Recorded in D8.
- **[Trade-off] Slower `build` because of HTTP and a host per test.** Measured at about 10–20 ms per host
  start after the first, so under 5 s for the suite.
- **[Risk] Overlap with in-flight branches.**
  - `upload-job-contracts` touches `ContractCoverageTest`, `Host` and rig verbs.
  - `os-recipe-timeouts` touches `World.kt` and adds an integration test.
  - Whichever merges second rebases. A new integration test from a sibling branch is ported onto the fixture
    as part of the rebase.

## Migration Plan

The change lands as one PR, in commits that each leave `./gradlew build` green:
1. the composition;
2. the world's levers;
3. the vocabulary;
4. the entry-binding move;
5. the test migration, file by file;
6. JVM-only;
7. the journeys;
8. the mirror.

Rollback is a revert; nothing it touches is persisted anywhere users hold.

## Open Questions

- ~~Whether the StagedByteReclaim backlog is reachable through the import levers and `relaunch`.~~ **Resolved:** it
  is not. It is state only an install from before per-asset byte release holds, since every current import
  releases its bytes inline. The four tests therefore stand on one flagged lever, `staging/seed-legacy-backlog`
  (`World.seedLegacyStagedBacklog`). It is the only lever that writes app-private state, and it models an
  upgrade from an older build. The tests' assertions stay on the staging directory's files.

- Whether the relaunch must also model the OS's delivery of a background-session completion to a
  relaunched app (`ColdDownloadRelaunch`), or only the adoption of an already-staged transfer. The existing
  test does the latter; the design keeps to it unless the port can express more.
- The journey cost is estimated, not measured. If it exceeds about +6 min, the second simulator's boot is
  moved ahead of the xcodebuild.
