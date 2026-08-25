# CLAUDE.md

SnapSync — an iOS app for **sharing photos from an event** (Kotlin Multiplatform + Compose), shipped
via TestFlight. Join an event by scanning its QR, and your photos taken since a per-device
**capture-date cutoff** are shared to it while everyone else's arrive in your library. The JVM desktop
app is test equipment, not a product.

**Mission** (full form + decision record: `openspec/config.yaml` context /
`changes/archive/2026-07-21-align-specs-with-mission`): joined users easily share the photos they take during a
**short-lived event** (days/weeks — celebrations, holidays, trips), synced gallery-to-gallery; you never
care how photos arrive, you just look at your own gallery. No accounts; simple setup; the host picks the
event's **date range** at creation (at most **30 days** long), and that **end** is the capture-date ceiling
**only** — it bounds which photos may be uploaded and closes nothing, so a guest who scans days late still
joins and contributes their in-window photos. How long the event **lives** is a separate stamped lifetime
(30 days from `max(createdAt, startsAt)`); the nightly sweep deletes it then — or sooner, once every member
has left — and that IS how an event ends. Named futures (don't build for them; don't deepen assumptions
against them unnamed): Android · paid events (device count is the only lever) · concurrent multi-event
membership (single active membership is the *current* contract).

> It began as a *personal one-way photo backup*. Defaults inherited from that era are dangerous here:
> what was "back up everything of mine" becomes "upload a guest's whole camera roll to a stranger's
> event". A membership's cutoff is therefore **required**, never absent.

What a member contributes is decided by **one** policy at **one** place (capability
`photo-selection-policy`, enforced in `UploadCycle`'s resource selection): the capture-date **range**
`[from, until]` bounds *when* a photo was taken (lower bound clamped to the event start, upper to the event
end); the **origin exclusions** bound *what it is* — screenshots, screen recordings, GIFs,
sub-floor-resolution received media, and members of a denylisted album (WhatsApp, Telegram, …) never enter
an event. PhotoKit exposes **no** camera-origin flag on any iOS through 26, so the policy can only
*subtract* known non-captures and **admits on doubt**: a stray uploaded meme is harmless and visible, while
an event photo that silently fails to upload is invisible and unfixable. The same policy gates the byte
upload, the device manifest (or an excluded photo leaks into the event union), **and** the status total `N`
(or the screen pegs below 100% forever).

**Limited (partial) photo access is a first-class grant** (capability `limited-photo-access`;
`PermissionStatus.LIMITED`): the user's hand-picked selection IS the membership's own-photo scope — the
policy then filters the selection exactly as it would a library. Three measured platform facts shape the
implementation, and violating any of them reads as "mysteriously broken" with no error anywhere:
① **the limited-access alert is armed by the LIBRARY CHANGING, not by reading** — measured on device
(SE2, iOS 26.5.2; record: `changes/archive/2026-08-06-correct-limited-access-read-premise/PROBE-FINDINGS.md`, superseding
the storm claim in the 2026-07-20 probe): a `PHAsset` fetch under `.limited` surfaces iOS's alert **iff
the library gained content outside the app's selection since the app last looked** — armed **once per
change**, not once per fetch, and merely surfaced by the first fetch after it. ~15 hammered walks against
an unchanged library queued **zero**; one camera capture then a single read queued one, which survived
SIGKILL onto the home screen. App-created assets join the selection at creation, so **an import, and any
fetch resolving what it created, never arms it**. Consequences: read volume does NOT reduce the alert
count, so do not justify anything as alert suppression (the read discipline is kept because under a
partial grant the selection IS the scope, and it saves round-trips); and **every photo the member takes
costs one system prompt**, which no read strategy avoids — only the full-access upgrade does. Reads still
happen ONLY on the cold-launch baseline and the `PhotoSelectionChangeSource` observer emissions, and every
upload cycle's discovery is fed the in-memory snapshot (`SelectionScopedTransfer` in `uploadCore`), never a
walk. ⏰ Re-measure at the next iOS major; evidence is one device, one point release, n=1 change.
② **the ≥26.1 PhotoKit
extension cannot be REGISTERED under `.limited`** (`setUploadJobExtensionEnabled` is refused in *both*
directions with `PHPhotosErrorAccessUserDenied` 3311 — measured, SE2/26.6; the older "registration
succeeds and lies" reading is contradicted by measurement), so the OS never invokes it there — under a
partial grant resolution yields the app-driven mechanism instead (`resolveUploadMechanism`);
③ asset/album **creation is unrestricted** under `.limited`, so downloads and the event album need no
special handling (the album **denylist**, though, is inert — album structure is
unreadable; the resolution floors still apply). Decision record:
`openspec/changes/accept-limited-photo-access/` (`PROBE-FINDINGS.md` + `LIMITED-ACCESS-DESIGN.md`).

Stack: Kotlin 2.4.0 · Compose MP 1.11.1 · JDK 25 · min iOS 18.0 · Orbit MVI · SQLDelight · Ktor.
(Two upload tiers, resolved per transition from the OS fact AND the current photo permission: OS-driven
PhotoKit only on iOS ≥26.1 under a full grant, app-driven background `URLSession` everywhere else — all of
iOS 18–26.0, and ≥26.1 under a partial grant. See the `ios-photokit-upload` / `ios-url-session-upload`
specs.)
(`gradle/libs.versions.toml` is the source of truth for versions.)

## Repo layout

```
api/            the Deno backend (bunny Edge Scripting) - api/README.md is its doc of record
site/           the Astro landing page
iosApp/         the Xcode project (app + upload-extension targets) - NOT a Gradle project
domain/ adapter/ ui/ app/ test/   the Gradle modules - mapped under Modules below
openspec/       specs/ (contract of record) + changes/archive/ (decision records)
architecture/   GENERATED diagrams - `./gradlew architectureDiagrams` and commit; stale blocks the PR
metadata/       App Store listing copy + App Review notes
screenshots/    the 6 committed raw captures both the listing and the site derive from
scripts/        build and dev tooling, incl. the device lease + guard (see On-device iOS)
tools/ config/ gradle/            more build tooling
```

**There is no `backend/`** - it split into `api/` + `site/`. Capability names are whatever
`ls openspec/specs/` prints; never guess a spec path.

## Runbooks (load the skill before you start)

- **Touching the connected iPhone** - install, launch, screenshot, device logs -> load **`ios-device`**.
  It opens with the **device lease**, which `scripts/device-guard` requires before any of it will run.
  It stops at the running app: to *drive* one, see `rig-channel` below.
  (`pymobiledevice3`, `dvt`, the libimobiledevice tools)
- **Seeing or clicking the app's UI without a device** -> load **`ui-harness`**. 🚫 **Never**
  `java.awt.Robot`, and never capture the real screen `:0` - it raises a portal consent prompt and
  **blocks until someone answers**. (`:test:harness-driver`, `driveForge`, `driveWorld`)
- **Anything needing a Mac** - an Xcode build, an `.xcarchive`, an IPA, code signing, or the
  `iosSimulatorArm64Test`s -> load **`ssh-mac-build`**. (`xcodebuild`, `ssh-mac.yml`)
- **Testing a backend change against a real device** -> load **`local-backend`** first; it owns the
  three-hop chain and the one step whose omission is silent. (`deno task dev:local|dev:tunnel`)
- **Driving the app on device** — joining, creating, leaving, resetting, seeding, wiping, reading the
  selection policy, forcing an OS callback, reading live state — over the build-time-only control channel
  (`-Psnapsync.rig=true`, `/os`, `/user`, `/device`) -> load **`rig-channel`**. It needs the same device
  lease as `ios-device`. **There are no `SNAPSYNC_*` launch triggers any more**: production Kotlin declares
  none, and a guard fails the build if one returns. (`:test:rig`, `usbmux forward`)
- **Running the app on a SIMULATOR** — two members of one event at once, a headlessly seeded/wiped
  photo library, headless permission state -> load **`ios-simulator`**. It needs **no device lease**,
  and the ad-hoc signature is not optional (an unsigned build has no App-Group container).
  (`xcrun simctl`, `scripts/sim-sign`)
- **Apple portal chores** - certificates, device UDIDs, provisioning profiles, bundle-id
  capabilities, App Store / TestFlight text metadata -> load **`asc-portal`**. (`app-store-connect`)

**Two traps no skill gates** - both are reachable without loading anything:

- 🚫 **Never join an event you did not create.** A `direction=download` join imports that event's
  photos into this device's library and registers this device on its backend membership. Log-scraped
  ids are someone's real event.
- ⚠️ **There is deliberately NO whole-zone storage reset.** The single `snap-sync-dev` zone is the
  *only* zone (`api/src/config.ts` - the deployed backend uses it too), so it is shared with real
  TestFlight / App-Store users' photos; a blind wipe would destroy them. Clean up **targeted only** -
  a fresh event id, `SNAPSYNC_LEAVE`, or deleting that event's/device's objects via bunny. Never
  re-introduce a `reset-storage`-style whole-zone delete.

## Read first

There is no narrative design doc. Two OpenSpec trees carry the whole design:

- **`openspec/specs/<capability>/spec.md`** — the **contract of record**. One spec per capability, each
  a `## Purpose` (what it is for, and why) plus `## Requirements` (SHALL/WHEN/THEN). A spec is
  authoritative for its own contract; it never defers that to a doc outside `openspec/`.
- **`openspec/changes/archive/<id>/`** — the **decision record**. Each archived change holds
  `proposal.md` (what & why) and `design.md` (`## Context`, `## Goals / Non-Goals`, `## Decisions`) —
  this is where rationale, rejected alternatives, and trade-offs live. Specs cite theirs as
  `Decision record: changes/archive/<id>`.

Read the spec for the capability you are changing, then its decision record before altering behavior.

## Agent harness limits

Two harness facts that are invisible until they bite, and that no amount of local reasoning recovers.

- ⏱️ **The Bash tool's `timeout` is capped at 600_000 ms (10 min), and larger values are clamped
  SILENTLY.** You do not get an error, a warning, or a shorter-than-requested acknowledgement — you
  get `Command timed out after 10m 0s` at exactly the cap, with the command killed mid-work. So a
  command that needs longer than 10 minutes **cannot be run in the foreground at all**, and raising
  the number is never the fix: it is the clamp, not the value. This is not hypothetical — `/ship`'s
  15-minute merge wait was killed at 10 minutes on **26 consecutive ships** (July–August 2026), and
  the escalating `timeout` values tried in response (1.2M, 1.5M, 1.8M, 3M ms) all clamped to the same
  600_000. Sum inner `timeout`s to stay under it, or go background.
- 🌙 **`run_in_background: true` is the escape, and it has no cap.** The shell is detached, survives
  across turns, and the harness **re-invokes you when it exits** with a notification carrying the exit
  code and an output-file path you `Read`. Cost: the work spans two turns. Have the background command
  print a single greppable result line as its last act (`/ship` uses
  `SHIP-WAIT RESULT: <status> (<reason>)`) — write it with `writeSync(1, …)` or an equivalent, because
  a `console.log` immediately followed by `process.exit()` is truncated when stdout is a pipe. Then
  "no line" stays distinguishable from "it failed".
- 🏃 **`ch-bg`** (`~/.local/share/codehydra/bin/ch-bg`) runs a command transparently — same stdio, same
  exit code — and exists only to put a marker in the command string so CodeHydra does **not** count
  that background shell as keeping the workspace busy. Wrap long-lived processes that are *not* the
  work itself: dev servers, watchers, `tail -f`. Do **not** wrap work the workspace is genuinely doing
  (`/ship`'s merge wait rebases and force-pushes the worktree — it *should* read as busy), and note
  the prefix changes the command string, so it can fall outside an `allowed-tools` grant like
  `Bash(npx:*)` and start prompting.

## Build & test

- `./gradlew build` — the canonical check (compiles all targets + runs JVM tests). **No display
  needed**: the Compose Desktop UI tests (`:ui:screens:jvmTest`) render offscreen under
  `-Djava.awt.headless=true` (set on that task in `ui/screens/build.gradle.kts`), so no X server /
  Xvfb is required. Only the two harness run tasks — `:app:desktop:runForge` (forge) and
  `:app:desktop:run` (full-stack world, below) — open a real window and need a display.
- `./gradlew compileIosMainKotlinMetadata` — the **Linux-runnable proxy** for the iOS source
  sets: it compiles `iosMain`/`commonMain` (and cinterop) without a Mac, so you can catch
  iOS-only breakage here. The actual iOS tests (`iosSimulatorArm64Test`, etc.) are **macOS-only**
  and run on GitHub Actions `macos-26`.

**Don't prefix commands with `cd <workspace root>`.** Every Bash call *starts* at the workspace
root and is reset back to it afterwards — shell state (cwd, env vars, functions) does not persist
between calls — so that leading `cd` is always a no-op. It was on 45% of commands (6,517 of 14,340
measured across July–August). `cd` into a **sub**directory is a different thing and still correct:
`cd api && deno task dev:local`. The non-persistence is why `USBMUXD_SOCKET_ADDRESS` is set in
`.claude/settings.json` rather than exported per call, and why a `P=…` shorthand must be defined in
the same call that uses it.

### Reading the Apple SDK from Linux (`klib dump-metadata`)

**"I'd need a Mac to check that" is wrong for a whole class of questions.** The Kotlin/Native
distribution ships **prebuilt platform klibs**, and they are the compiler's own input — so what an
Apple API *declares* is readable here, in under a second, with no Xcode:

```
K=~/.konan/kotlin-native-prebuilt-linux-x86_64-$(grep -oP '^kotlin = "\K[^"]+' gradle/libs.versions.toml)
$K/bin/klib dump-metadata $K/klib/platform/ios_arm64/org.jetbrains.kotlin.native.platform.Photos
```

Answers **enum case sets and their values**, **property nullability**, selector encodings, and
deprecations. It settled three things in one sitting (2026-08-09) that had each been treated as
device-only: `PHAssetResourceUploadJobState` declares exactly five cases (so a table's `else` was
absorbing a *known* state, not just hypothetical ones); `PHAssetResourceUploadJob.destination` and
`.resource` are declared **non-null** and are nil at runtime (the two widenings in
`IosPhotoKitUploadPlatform` are load-bearing, not redundant `?`); and `PHAssetResourceUploadJob` has
no `statusCode`, so its `responseHeaderFields` cannot yield one. `:test:architecture`'s
`PlatformVocabularyPinTest` pins the declared sets from this same source, so a case Apple adds fails
the Kotlin bump rather than reaching a device untaught.

⚠️ **This does not contradict the law "a platform-capability claim is settled by a compile, not by a
symbol table"** — they answer different questions, and conflating them is how `Dispatchers.IO` was
misread:

| question | authority |
|---|---|
| *can I **call** this?* | the symbol table over-promises (visible but `internal`, present but unlinkable) — **settle it with a compile** |
| *what does this **declare**?* | the klib **is** the compile's input — authoritative by construction |
| *what does the **device** do?* | neither — only a measurement |

The third row is the one that keeps this honest: the runtime may return a value no header carries, and
the prebuilt klib reflects the SDK **Kotlin/Native** was built against, not the iOS version on the
phone. So the declared vocabulary tracks the **Kotlin version** in `libs.versions.toml`, not the
locally installed Xcode — which is why the pin fires on a Kotlin bump.

## Test UI (review/exercise every UI state)

`./gradlew :app:desktop:runForge` launches the forge harness (module `:app:desktop`, which hosts BOTH
desktop harnesses): the real
`:ui:screens` status screen inside a phone-sized frame on the left, and a **control panel** on the right
(raw Material 3 — it is test equipment, never `App*`). The panel **forges any display state** — permission presets,
sync-state presets, and the engine console — so you can review and test all UI states without a
device. See the `desktop-test-harness` spec.

`./gradlew :app:desktop:run` launches the **full-stack world harness** (same module): the same
real status screen on the left — but its counts **emerge** from the real `LedgerBackedSyncStatusSource`
composed by `snapSyncApp` over `:test:world` (never forged) — and a right-pane **world inspector** (raw M3) that drives the real stack: presets,
**Invoke extension** (one `process()`-shaped cycle + download reconcile), the gallery/backend, the
upload-job queue and downloads, failure levers, and an engine-console footer. The operator plays the OS
(nothing auto-runs). See the `full-stack-harness` spec.

**To drive either harness headlessly** - no display, and no screenshot of the real screen - load the
**`ui-harness`** skill: `:test:harness-driver` composes the shipped harness root into an offscreen
Compose scene served over HTTP, so an agent clicks the real buttons and reads back the real pixels.

## Releasing (TestFlight · App Store · screenshots)

**Contract and rationale live in the specs** — `ios-testflight-delivery`, `ios-appstore-release`,
`ios-appstore-metadata`, `changelog-labels`. Read those before changing any of it; what follows is
only what an operator types.

**Every merge to `main` uploads a signed build to internal TestFlight** (`ios-deliver`),
automatically and unfiltered — docs-only merges included. It reaches **no external tester**: there is
no public alpha channel, and the App Store release below is the only path to real users. Each build's
marketing version is **computed** (`max(floor, latest vX.Y tag with its minor +1)`, integer bump —
`v0.9 → 0.10`); a major jump is a manual `Config.xcconfig` floor bump via a PR.

**An App Store release PROMOTES a build you already tested** — the `vX.Y` tag is its receipt, not its
trigger:

```
gh workflow run ios-appstore-promote.yml -f build_number=512             # attach, no submit
gh workflow run ios-appstore-promote.yml -f build_number=512 -f submit=true
```

- `build_number` is the build's `CFBundleVersion` = the `ios.yml` `run_number` that produced it.
  Promote the exact build you validated; the store version is derived from it (there is no `version`
  input).
- ⚠️ **Never push a `vX.Y` tag by hand.** Tags trigger nothing, and a tag you push yourself makes that
  version permanently un-releasable — the guard refuses a version whose tag already exists.
- ⚠️ **A promote is single-shot per version.** Correcting an already-promoted version's screenshots or
  release notes is a **manual console upload**.
- ⚠️ `asc review doctor` is **not** the whole preflight: it reported zero blockers on a version that
  `asc review submit` then refused, for a missing `en-US: whatsNew` (run 30632785849). A green gate is
  not a promise the submit will pass.
- Release notes derive from the merged PRs' `enhancement`/`bug`/`internal` labels. Preview any range
  before dispatching, and read the run summary rather than only App Store Connect:
  ```
  GH_TOKEN=$(gh auth token) python3 .github/scripts/release_notes.py \
    --repo stefanhoelzl/SnapSync --target <origin-sha> --previous vX.Y
  ```

**Refreshing the marketing screenshots** — `screenshots/*.png`, 6 raws, 3 forge states × light/dark.
Both the App Store listing (at release time) and the `site/` landing page (on merge) derive from these
committed raws, so refreshing them is a **commit**; nothing regenerates automatically, and a merge to
`main` uploads nothing to the store.

```
gh workflow run screenshots.yml --ref <branch>          # ~11-19 min
RID=$(gh run list -w screenshots.yml -L1 --json databaseId -q '.[0].databaseId')
gh run download "$RID" -n screenshots-raw -D screenshots
# LOOK AT THEM (below), then:
git add screenshots/ && git commit
```

- **Eyeball them before committing — this is the only check there is.** A system notification
  (*"Ready for Apple Intelligence"*, fired by fresh-device onboarding) can land in a capture; it hit
  **1 of 2** runs. Re-dispatch if one does. This is **not** automatable by asserting the top band is
  flat: `in_sync` legitimately renders the event name there, so a colour check false-positives.
- **Only `create` should re-diff on an unchanged UI**, and only in the 90×32 px region that renders
  the wall clock. `joining` and `in_sync` come back byte-identical. A diff anywhere else means the UI
  really moved.
- **A headline or size change needs NO re-capture** — both consumers derive from the committed raws.
  Edit `metadata/screenshots/en-US.json` or the `site/` landing page and push.
## Modules

```
:domain                the platform-free core (spec module-architecture): five zone packages under app.snapsync — model/ (vocabulary + pure codecs: the config surface incl. the EventLink codec + generated LINK_ORIGIN, sync vocabulary, selection policy + denylist + upload keys + device manifest + RawAsset mapping, edge-URL builder, SyncStatus/SyncProgress, PermissionStatus, the UploadMechanism kind + its pure resolver (OS fact + permission + an optional dev override → one mechanism), the UserCommands bundle type, and the Logger.invocation enter/exit helper over the injected ports/LogScope seam), ports/ (every port seam, named for the need: config, SecureStore [one addressed durable+protected value; the iOS Keychain is its binding], gallery candidate-read/status/manifest, permission, photo-selection-change, download + download-store, backend-need clients, upload transfer/scheduler/discovery, push, attest, join marker, LedgerStore, album, crash reporting — CrashReporting.start() is the shared compositions' first act, idempotent, no-op without a baked DSN), feature/ (mutually blind, feature-blindness gate armed): upload (UploadCycle + the port-pure cycle gate, UploadArm/UploadProducer, BackgroundUploadPump, ExtensionReconciler, SyncEngine + LedgerWriter — the single ledger-writer feature — UploadPushReceiver), membership (JoinEvent + DeviceEnroller, LeaveEvent, DeviceManifestProducer, MembershipRefresh + TitleNeed, ReconfigureEvent, ResetDeviceState, switchDecision), status (SyncStatusSource + LedgerBackedSyncStatusSource + LedgerCountsSource/Poller + OwnDeviceGalleryStatusSource), trust (DeviceAttestation incl. refreshOutcome), download (DownloadController + QueuedPhotoDownloadJobs + DownloadPushReceiver + the DownloadStatusSource read-model), album (AlbumCoordinator — ensureAlbum owns the granted/opt-in gate, albumIdFor the import-time lookup), creation (CreateEvent + the CreationStatus seams), push (PushRegistration + EventNotifier over the PushHttpClient/PushTokenSource ports), flow/ (the OS-callback trigger flows Foreground · Background · SilentPush [the cross-arm push fan-out] · DownloadBackstop · Provision, each importing model/+feature/ ONLY — flow-no-ports gate armed; every port/platform touch injected as a compose/-built effect lambda; each flow transcribes into architecture/flows/ under the closed grammar, and an untranscribable flow FAILS generation), and compose/ (the SHARED composition, law "One shared composition": uploadCore(scope, UploadPorts) → UploadCycle — the ONE cycle assembly both device tiers' roots AND the world call; snapSyncApp(scope, AppPorts) → AppCore — the app feature graph, the flow instances, the live UserCommands bundle, and the permission-grant subscriptions as an explicit installPermissionSubscriptions() the app shell invokes from host assembly ONLY). Zero project() deps; jvm+iosArm64+iosSimulatorArm64; no iosMain source dir; model-purity + ports→model + feature-blindness + flow-no-ports gates armed in :test:architecture
:adapter:generic:app   platform-free technology impls of the :domain ports: the Ktor clients (HttpAttestClient, HttpEnrollment, HttpEventDirectory, HttpDeviceFilesSource, HttpLeaveNotifier, HttpEventUnionSource, HttpEventCreation, KtorPushHttpClient) + the SQLDelight stores (SqlDelightLedgerStore, SqlDelightDownloadStore) with both db schemas + the SystemClock/SystemTimeZone port impls; jvm+ios. Its jvmTest/iosSimulatorArm64Test extend the storage contracts from :test:world
:adapter:ios:ext-safe  every iOS adapter the EXTENSION process links (placed by linkage): the discovery walk + cursor store (IosDiscovery/IosDiscoveryStore), the PhotoKit upload-job platform (IosPhotoKitUploadPlatform — the BackgroundTransfer impl for the OS-driven tier), ledger/download-store native drivers (iosLedgerStore/iosDownloadStore + the App-Group consts), manifest store + PhotoKit enumerator, device-log writers (FileLogWriter/PublicNSLogWriter) + IosLogScope, the crash-reporting seat (SentryCrashReporting + SentryLogWriter over the Sentry KMP SDK, capability crash-reporting: Error/Assert→events, lower→breadcrumbs, every outgoing UUID scrubbed; DSN read from the process bundle — absent in every dev build, injected only by CI Release archives), darwinHttpClient, IosJoinedEventMarker, IosAlbumManager/IosAlbumMapStore, IosAttestKey, FileBackedConfigStore (the App-Group config file of record — its ONLY storage: save/clear/read are file-only since the finale ended 11a's Keychain write-through and the Stage-2 change deleted the read-only legacy-Keychain fallback, so a missing file IS "left the event" and a reinstall is a leave; the absence classifier isConfigFileAbsence beside it is therefore solely load-bearing — widening its not-found whitelist is a change to the leave decision, capability event-rejoin-reconciliation) — and the Keychain impls: this is the ONLY module that may touch SecItem* (IosKeychain, KeychainDeviceIdentity, KeychainAttestStore; the :test:architecture guard enforces it, and the extension-safety gate forbids platform.UIKit/BackgroundTasks anywhere in it)
:adapter:ios:app-only  iOS adapters only the MAIN APP process links: IosUrlSessionUploadPlatform + IosBackgroundScheduler (the 18–26.0 tier), IosDownloadTransport, IosPhotoLibraryImporter, PhotoLibraryPermission (the request/Settings/limited-library-picker port), IosShareSheet (the UIKit share-sheet presenter) — the two URLSession adapters own OS-reattached app-process session ids, so an extension-side link must stay structurally impossible
:adapter:generic:fake  the honest in-memory port impls (package app.snapsync.fake): InMemoryLedgerStore/DownloadStore/AttestStore/CandidateSource/GalleryStatusSource/CrashReporting + the discovery/marker/manifest/album-map stores — what the world harness and every integration test stand on. Honesty is MECHANICAL (FakeHonestyTest, armed here): a fake's public surface is its port contract plus a constructor taking initial state (the gallery fakes take a MutableStateFlow cell); operator rigging (levers, inspection) lives in :test:world wrappers (WorldGallery, RecordingDownloadStore), never here. jvm+iosSimulatorArm64 only (never links into a shipped framework); commonTest hosts the re-homed fake-driven feature tests (RawAssetMapping, status sources, download trio, DeviceAttestation)
:ui:presentation       Orbit MVI container + UiState (Compose-free, no engine dep) — the presentation-imports gate (scope ui/presentation/src) enforces: it references only model/, feature read-model types, and its own vocabulary — never ports/ or flow/. StatusContainerHost's inputs are exactly read-models (bare StateFlows + feature sources), the model/ UserCommands bundle (user taps incl. requestAccess/openSettings; live instance built only in compose/), the loadJoinDetails query, and the pure CutoffFormatter (now/zone injected; production binds the Clock/TimeZoneSource ports via :adapter:generic:app's SystemClock/SystemTimeZone in the shells). Also hosts the forgeStatusHost forge factory (formatter passed in by the shell)
:ui:screens            Compose screens (written against App* only); both sides speak model/'s one Arrow enum; CutoffFormatter is a required param (no system-reading default)
:ui:components         App* design system + the Material 3 skin; api(:domain) for the model/ Arrow in AppStatusLine's signature (the one enum presentation and the skin both render from)
:app:desktop           the ONE desktop module: the shared pane library (PhoneFrame + StatusPane, StatusContainerHost wiring) + BOTH harness apps — the full-stack world harness (:app:desktop:run; real StatusScreen whose counts EMERGE from the AppCore the world composes via snapSyncApp, + a right-pane world inspector; capability full-stack-harness) and the forge harness (:app:desktop:runForge, a JavaExec — the Compose plugin models one application main class; phone frame + control panel forging any UI state; capability desktop-test-harness)
:app:ios               iOS app wiring + framework export (thin, untested); SnapSyncRoot holds ONE switch — whether this OS carries the OS-driven upload mechanism at all, which decides only whether that producer is CONSTRUCTED (its registration selector does not exist below 26.1). Which mechanism RUNS is re-resolved per transition by model/'s resolveUploadMechanism over the OS fact, the current photo permission, and an optional dev override the control channel supplies; the root builds the platform adapters and calls :domain compose/'s snapSyncApp
:app:ios:extension     iOS ≥26.1 background-upload extension: the composition root (UploadExtensionRoot) calling :domain compose/'s uploadCore over :adapter:ios:ext-safe (which holds the PhotoKit platform adapter) + :adapter:generic:app — the SnapSyncUploadKit framework; thin, untested (orchestration + tests live in :domain feature/upload)
:test:world            test-only shared infra: the controllable in-memory "world" — BackendStore + MockEngine mini-edge + operator levers/wrappers rigging :adapter:generic:fake's honest doubles — whose World.core IS the real AppCore from the SAME snapSyncApp the iOS shell calls (features, flows, and the UserCommands bundle are production instances), with the extension-tier cycle from the same uploadCore. commonMain also hosts the LedgerStore/DownloadStore CONTRACTS (a test source set cannot be exported; :adapter:generic:app's driver tests and the fake-backed runs extend them from their own test source sets). jvm()+iosSimulatorArm64. Consumed by :app:desktop AND :test:integration (capability harness-world-model)
:test:architecture     test-only JVM guards for invariants the compiler cannot express (capability architecture-guards), all gating ./gradlew build: the zone gates (model-purity, ports→model, feature-blindness, flow-no-ports, presentation-imports), KeychainContainmentTest (no SecItem* outside :adapter:ios:ext-safe — catches fully-qualified calls, which no linter can see on iosMain), the extension-safety gate (no platform.UIKit/BackgroundTasks in extension-linked source), RuntimeIdentityTest (every OS-held literal exactly once), the entitlements guard (never raise default-data-protection to NSFileProtectionComplete — it would make every App-Group file unreadable while locked, killing the background tier), SwiftShellGuardTest + KotlinShellGuardTest (the shell decision pins, exact in both directions; detektAppShell itself gates inside check), ModuleSetTest (settings == the target module set), MixedPortImplTest (no port interface beside a technology impl), DeletionLedgerTest (the migration's retired dead weight stays dead), FakeHonestyTest, LawsDigestTest, RunbookSkillsTest (every skill CLAUDE.md's Runbooks block points at exists, and the ios-device skill's SNAPSYNC_* launch-trigger index equals the literals in production Kotlin - the two duplicates the runbook split deliberately keeps, held loud-when-stale), EventLink guards
:test:integration      test-only: seam → UI-state integration over :test:world — asserts UiState AND world outcomes (objects landed, ledger COMPLETED, foreign photos imported)
:test:harness-driver   test-only dev infra (non-gating, no spec): serves EITHER desktop harness over HTTP with no window — composes the shipped ForgeHarnessRoot/WorldHarnessRoot into an offscreen Compose scene (CPU raster Skia; no X server, no screen-capture portal) so an agent can click the real buttons and read back the real pixels + semantics tree. Runbook above; rationale in Driver.kt
:test:rig              test-only dev infra (non-gating, no spec): the CONTROL CHANNEL — a Ktor CIO server linked into :app:ios ONLY under -Psnapsync.rig=true, so an agent can force OS-callback entry points and read live state over usbmux forward. Contained at COMPILE TIME (a production build contains none of it); it contributes its own call site into :app:ios rather than making the shell carry a seam. The one module that may depend on ktor-server-*. Runbook: load the `rig-channel` skill
iosApp/                Xcode project (app + upload-extension targets) — not Gradle
```

**The migration that produced this graph is complete.** The graph and its laws are the
**`module-architecture`** spec — the contract of record; read it (and `architecture-guards` /
`architecture-diagrams`) before moving any code. Every law is now a permanent, gating check under
`./gradlew build` (`:test:architecture` + `detektAppShell` + the flow-transcriber generation
failure) — there is no non-gating grace period any more, and no migration beacon: the `verify`
job measured zero at the finale and was deleted with its module, per its own contract. The
**`diagrams`** check IS required: stale `architecture/` blocks the PR — run
`./gradlew architectureDiagrams` and commit. Rationale for any placement lives in the decision
records under `openspec/changes/archive/` (the migration's steps are archived changes like any
other).

## The laws (digest)

Authority: `openspec/specs/module-architecture/spec.md` — this digest is the in-context copy, one
line per law; a `:test:architecture` guard keeps the two in sync. Every law is mechanically
gated in `./gradlew build`; a violation is a red build, not a review note.

- **The module set withholds; packages organize** — a module exists only to withhold a
  third-party/platform dep by compile error (platform-free `:domain`, M3 in `:ui:components`,
  extension-safety adapter split); everything finer is a package with a derived text gate.
- **A build-time-only module is contained by compilation, not by a runtime check** — a test-only
  module may link into a shipped-format binary only under a build property; without it the build
  contains NO source of that module (no stub, no inert branch), and it contributes its own call
  site rather than making a shell carry a permanent seam.
- **Zones inside the core** — `:domain` is `model/` ← `ports/` ← `feature/` ← `flow/` ←
  `compose/`; features never reference a sibling feature; `flow/` never references `ports/`.
- **Ports are the I/O boundary named for the need** — anything touching an external system (time,
  files, network, env included) goes through a port interface in `ports/`, named for the need
  (must survive a second platform); adapters implement, named for technology, placed by linkage.
- **State and authority** — no global mutable state in `:domain`, ever; instance state only as
  derived caches or coordination primitives; authority behind ports (kill-test: after
  kill+relaunch, every fact recoverable via ports, keyed by identifiers the external system
  persisted). Which thread a port call runs on is NOT the impl's concern — see the lane law below.
- **Dispatcher lanes are fixed by the composition** — three lanes, each with a purpose: **main** is
  reserved for platform UI and carries nothing else; **`Dispatchers.Default`** carries presentation-state
  reduction; the **composition lane** — a dispatcher of the composition's own, one dedicated thread —
  carries the live core's scope, so a blocked platform call cannot eat the pool the UI reduces state on.
  The live core's scope is never UI-bound in ANY binary that composes it (device shell or harness), and
  is **serial**, because the main thread it replaced was single-threaded and core code relies on that for
  mutual exclusion. User commands declare their lane where they are built (the container launches intents
  unconfined, so the scope does not govern them) and no decorator supplies a default. An adapter's
  dispatcher hop now buys **concurrency**, never safety. In the **extension** process there is no UI and
  no main lane: `process()` is synchronous by the OS's contract and runs under `runBlocking` on the
  OS-invoked thread. Gated by `MainLaneContainmentTest`, `CommandLaneTest`, `ConstructorBlockingTest`.
- **Rules in features, order in flows** — flows coordinate, never decide; features are mutually
  blind and coordinate via one-writer durable state behind shared ports, written whole; no field
  encodes a request to another feature.
- **A trigger flow never outlives its own run** — a `flow/` class declares no `CoroutineScope` and
  every `Unit`-returning lambda it accepts is `suspend`; `run()` is `suspend` and returns only when
  the work it coordinates has finished, so a shell can report completion to the OS truthfully.
- **Commands cross one door** — user taps, OS callbacks, and port-state transitions all enter
  through `flow/` commands (built/decorated only in `compose/`, injected into presentation);
  reads do NOT cross flow — presentation observes feature read-model StateFlows directly.
- **One shared composition** — every live-core binary and the world harness call
  `snapSyncApp`/`uploadCore`; platform-mechanism selection is a pure, tested TOTAL function of OS facts and runtime state, re-evaluated when an input changes rather than once per process; the
  wiring graph itself is smoke-tested, never unit-tested; DI is manual (decision D6).
- **Shells are wiring only** — zero conditionals in `:app:*` Kotlin (detekt-gated); Swift is a
  transcriber (forwards raw ObjC-visible inputs whole, decides nothing; pinned exceptions only).
- **A platform-capability claim is settled by a compile, not by a symbol table** — an artifact records
  what ships, not what is callable. `Dispatchers.IO` is in the Kotlin/Native coroutines klib and is
  `internal`; a design built on the symbol table's evidence was withdrawn at the first compile. State
  absence precisely too — a *public* API, a target, a version — so a reader can tell what would falsify it.
- **Necessity claims carry forcing proofs** — "the platform forces X" cites an API contract, a
  measurement, or a vendor doc — never the current code — and names its expiry trigger.
- **Absence is never silent** — "nothing" and "couldn't tell" are different answers wherever their
  consequences differ; a deliberate collapse names the consequence that makes it safe for EVERY
  cause it absorbs; and an entry point never collapses into silence.

Still true and not a law: because iOS targets are present, `commonMain` is limited to the common
stdlib + each zone's allowlisted libraries — JVM-only APIs there break the iOS compile (verify
with the proxy task above).

## Logging & errors

- Log via **Kermit** (multiplatform). Cross-cutting logging infra lives in **`:domain`'s `model/`**:
  the `Logger.invocation` enter/exit helper (params + result + duration), driving the injected
  `ports/LogScope` seam. The iOS ambient prefix global (`IosLogScope`) and the consolidated
  device-log writers (`FileLogWriter`/`PublicNSLogWriter`) live in `:adapter:ios:ext-safe`.
- **Device diagnostics** (capability `diagnostic-logging`): the app and extension are separate
  processes, each writing its **own** verbatim, un-redacted log - the app to its `Documents/debug.log`,
  the extension to `ext-debug.log` in the **shared App Group** (not pullable, so getting it over USB
  takes one extra launch). Each is the **canonical un-redacted channel** (os_log redacts `<private>`)
  and rolls to a `.1` sibling past 10 MB. To pull either off a device, load the **`ios-device`** skill.
- **Sending the logs off-device** (capability `diagnostic-logging`): **double-tap the "SnapSync" label**
  at the top of any screen → a confirm dialog → one diagnostic dump reaches Bugsink (state + counts +
  the tail of BOTH logs, ~700 KB total, sent **verbatim** — ids intact, unlike automatic crash
  events). It is deliberately invisible: no button, no semantics, and on a build with no baked
  `SENTRY_DSN` (every dev/sideload build) **no dialog opens at all**. To exercise it on device, inject
  a DSN into the ssh-mac `xcodebuild` line. Measured against the hosted instance (2026-07-29):
  Bugsink **drops attachments entirely**, caps events at `MAX_EVENT_SIZE` = 1 MiB (a `413` the SDK
  swallows — an over-budget dump is silently lost), and stores 340 KB context strings **byte-identical**.
  Dumps group as one issue (`diagnostic dump`); read them with `/bugsink`.
  ⚠️ **Do not reach for `NSLog` when debugging — not even "just this once", not even from Swift.** An
  interpolated `NSLog("x \(y)")` is a *dynamic format string*, which os_log redacts wholesale: your line
  never appears in `idevicesyslog` and the capture looks like "the code never ran". This is written
  above; it was ignored anyway on 2026-07-16 and burned a full build/install/scan cycle to re-learn. From
  Swift, route diagnostics through Kotlin (`SnapSyncRoot`) so they land in `debug.log`. Every line carries
  a
  `[<entryPoint>]` prefix (e.g. `[onSilentPush]`, `[process]`) tracing it to what triggered it; wrap
  new platform invocations / entry points with `Logger.invocation` and, for `scope.launch` work, wrap
  *inside* the launch so the context spans the async body.
- **Crash reporting** (capability `crash-reporting`): production builds report crashes and
  `Error`/`Assert`-severity Kermit lines (lower severities ride as breadcrumbs) to the operator's
  Bugsink instance, in **both** processes, via the `CrashReporting` port both shared compositions
  start first. Every UUID-shaped token is scrubbed before send (an eventId IS the upload
  capability); the SDK's random per-install `user.id` is the one deliberate exception — do not
  "fix" it into the scrub. The DSN exists only as the `SENTRY_DSN` CI secret, baked into Release
  archives (`ios-archive`) — dev/sideload builds carry none, so the SDK never starts there. Bugsink
  ingests no dSYMs: `ios-deliver` parks each main build's dSYMs as a `dsyms-<build>` artifact for
  offline `atos` symbolication (90-day cap; park longer-lived versions' dSYMs elsewhere at promote
  time). **Triage these crashes with the `/bugsink` skill** (`.claude/skills/bugsink/`, read-only,
  non-gating dev infra): it lists unresolved issues from `steho.bugsink.com` (project 1, API
  `/api/canonical/0/`, `BUGSINK_TOKEN` via proton-env) and drills into one for the symbolicated
  stacktrace — symbolication runs **on Linux** via the `symbolic` lib against the `dsyms-<data.dist>`
  artifact (no Mac/atos needed), and fails loud when that artifact has expired.
- Errors are **reduced into state**: sealed domain errors → `UiState`, converted at capability
  boundaries — not thrown to the UI. This is also what lets the harness force any failure state.

## Testing strategy

Three standing rules:

1. **Every unit test runs on the iOS simulator too.** Put logic tests in `commonTest` so they run
   on **both** JVM and `iosSimulatorArm64` — JVM is the fast loop, not the only coverage.
   `jvmTest`/`iosTest` hold only driver/cinterop wiring behind a shared contract (e.g.
   `LedgerStoreContract` over the JVM-sqlite vs native driver).
2. **`:app:*` Kotlin is wiring-only and untested** (the shell gates enforce zero unpinned
   decisions). All logic, shared or iOS-specific, lives in the tested `:domain` zones or the
   adapter modules.
3. **Seam ↔ UI-state integration tests** compose the real core — the same `snapSyncApp`/
   `uploadCore` the device shells call — over `:test:world`'s rigged `:adapter:generic:fake` ports, drive
   the flows and commands, and assert `UiState` AND world outcomes (objects landed, ledger
   `COMPLETED`, foreign photos imported). They live in the test-only **`:test:integration`**
   module (`commonTest` → runs on JVM and simulator).

The edge-URL builder (`:domain` `model/`) is pinned by `commonMain` tests on URL composition,
filename percent-encoding (deterministic + injective), and the Content-Type-only header set — pure
string-building, no network or crypto.

## Workflow

- **All changes** go through a branch → PR → **`/ship`** (branch protection forbids direct pushes
  to `main`). Every PR carries exactly one changelog label — `enhancement` · `bug` · `internal` —
  which `/ship` applies and the required `check-label` gate enforces; the App Store release notes are
  derived from it (capability `changelog-labels`), so `internal` means "no customer sees this".
- **The OpenSpec flow is user-driven — never entered on the agent's initiative.** Changes that
  **add, alter, or remove behavior** go through it (propose → apply → sync/archive) so
  `openspec/specs/` stays the contract of record — but *entering* the flow is the user's call, and so
  is **every phase boundary** inside it. On a behavior-touching request: name the affected
  capability, say it needs a change, and **stop** — no code, no `openspec/` file — until the user
  picks the route (OpenSpec proposal or direct change). Then run **only the phase asked for**: after
  `propose`, wait for the word before `apply`; after the tasks land, wait before `sync`/`archive`.
  Never invoke an `opsx` skill or the `openspec` CLI unbidden. **Reading is the opposite, and is
  mandatory**: read the capability's spec and its decision record before changing anything.
- Purely mechanical work — build/CI, dependency bumps, behavior-preserving refactors, docs — skips
  OpenSpec: branch → PR → `/ship`. Classify honestly and **ask when unsure**; a wrong "mechanical"
  guess is exactly how behavior-changing work gets built with no spec behind it.
- **The `openspec` CLI is not installed** — there is no global binary and no `package.json`. Invoke
  it via npx, pinned to the version CI uses: `npx --yes @fission-ai/openspec@1.5.0 <cmd>` (e.g.
  `… validate --specs --strict`, matching `.github/workflows/build.yml`). Do not run a bare
  `openspec …`; it will fail with "command not found". The generated skills below say bare
  `openspec …` — translate each call to the pinned npx form.
- **The `.claude/opsx` skills and commands are generated**, not hand-written. They assume the
  machine-global profile in `~/.config/openspec/config.json` is `core` (workflows propose ·
  explore · apply · sync · archive) with `delivery: both`. Regenerate with
  `npx --yes @fission-ai/openspec@1.5.0 config profile core` then `… update`, and commit the
  output verbatim — hand-edits are overwritten on the next update. On a default profile, `update`
  emits only four workflows and **deletes** the `sync` skill/command.
  **Regenerating is a no-op today, and must stay one**: `.claude/` is byte-identical to generated
  output, so `update --force` changes nothing. It was not always — the archive's placeholder gate was
  hand-patched into `SKILL.md` (and only there, which is why `/opsx:archive` never had it), and
  `update --force` silently deleted it, with a green run. Anything an archive must *enforce* belongs
  in **`openspec/config.yaml`**'s `context:` block — hand-authored, injected into every agent in this
  root, and the one surface `update` does not rewrite (capability `openspec-archive-command`). Never
  patch a rule into `.claude/`; the tool will take it away and tell no one.
- **`openspec validate --specs --strict` checks structure, not truth.** It asks whether a spec has a
  Purpose, Requirements, and SHALL/WHEN/THEN with a scenario — it has never opened a `.kt` file. It
  passed 50/50 on a tree carrying 28 audited drifts (four specs contradicting themselves *within one
  file*), and it passes 50/50 now that they are swept: the same answer with the lies in and with them
  out. Green means well-formed. It does not mean true, and nothing in CI does.
