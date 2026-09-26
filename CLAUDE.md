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

What a member contributes is decided by **one** policy at **one** place (capability `photo-sharing`, enforced in `UploadCycle`'s resource selection): the capture-date **range**
`[from, until]` bounds *when* a photo was taken (lower bound clamped to the event start, upper to the event
end); the **origin exclusions** bound *what it is* — screenshots, screen recordings, GIFs,
sub-floor-resolution received media, and members of a denylisted album (WhatsApp, Telegram, …) never enter
an event. PhotoKit exposes **no** camera-origin flag on any iOS through 26, so the policy can only
*subtract* known non-captures and **admits on doubt**: a stray uploaded meme is harmless and visible, while
an event photo that silently fails to upload is invisible and unfixable. The same policy gates the byte
upload, the device manifest (or an excluded photo leaks into the event union), **and** the status total `N`
(or the screen pegs below 100% forever).

**Limited (partial) photo access is a first-class grant** (capability `photo-access`;
`GalleryAccess.LIMITED`): the user's hand-picked selection IS the membership's own-photo scope — the
policy then filters the selection exactly as it would a library. Three measured platform facts shape the
implementation, and violating any of them reads as "mysteriously broken" with no error anywhere:
① **the app never raises iOS's limited-library prompt itself — and nothing may be designed on when iOS
does.** The prompt is iOS's automatic *"Select More Photos… / Keep Current Selection"* nudge to widen a
partial selection; it is **not** a guard on reads (the app can only ever read the selection). The app
suppresses it (`PHPhotoLibraryPreventAutomaticLimitedAccessAlert = true` in `iosApp/iosApp/Info.plist`
**and** `iosApp/BackgroundUploadExtension/Info.plist` — the key is read from each process's own bundle, and
the extension, launched by the OS under `.limited` when a full-grant registration survived, evaluates the
prompt at launch before any of our code runs: measured 2026-09-21, and one such launch showed it) and owns the
route instead — the status screen's "Choose more photos" picker. **Settled** on the SE2
across probes: reads of an unchanged library, however many, and the app's own creations (imports, album
creation and adds — created assets join the selection) raise none. **Not settled**: a photo taken
*outside* the selection. The key **leaked** on iOS 26.5 and 26.5.2 (July and August probes: queued
prompts after a camera photo, surviving SIGKILL onto the home screen) and **held** on 26.6.2 (2026-09-21:
one camera photo, 11 reads over 4 launch-and-kill cycles, zero prompts). So never assert "every photo costs
a prompt", and never justify a read strategy as alert suppression. Record:
`changes/archive/2026-09-21-correct-limited-access-alert-rule` (history table in its design). Reads still happen
ONLY on the cold-launch baseline and the `Gallery` selection observer's `onChanged` emissions (the observer opens only at host assembly), and every
upload cycle's discovery is fed the in-memory snapshot (`SelectionScopedDiscovery` in `uploadCore`), never a
walk — because under a partial grant the selection IS the scope, and a walk is a round-trip that buys
nothing. ⏰ Re-measure at the next iOS major; evidence is one device, and one probe on 26.6.x.
② **the ≥26.1 PhotoKit
extension cannot be REGISTERED under `.limited`** (`setUploadJobExtensionEnabled` is refused in *both*
directions with `PHPhotosErrorAccessUserDenied` 3311 — measured, SE2/26.6; the older "registration
succeeds and lies" reading is contradicted by measurement) — under a partial grant the app's uploader does
the uploading (it creates under any usable grant; `extensionRegistrable` is false). ⚠️ A registration made under a FULL grant
**survives** a downgrade (the deregistration is refused too) and **the OS still invokes it** under `.limited`
— measured SE2/26.6, 2026-09-21: `process()` ran 4 s after a new photo joined the selection. The
extension's own entry gate is what stops it (it withholds without `GRANTED`); never rely on "the OS does not
invoke it";
③ asset/album **creation is unrestricted** under `.limited`, so downloads and the event album need no
special handling (the album **denylist**, though, is inert — album structure is
unreadable; the resolution floors still apply). Decision record:
`openspec/changes/accept-limited-photo-access/` (`PROBE-FINDINGS.md` + `LIMITED-ACCESS-DESIGN.md`).

Stack: Kotlin 2.4.0 · Compose MP 1.11.1 · JDK 25 · min iOS 18.0 · Orbit MVI · SQLDelight · Ktor.
(Two uploaders, both active where both exist: the app-driven background `URLSession` on every iOS version
under any usable grant, and — on iOS ≥26.1 — the OS-driven PhotoKit extension, registered from join to leave
and creating only under a full grant. An overlap is a duplicate upload of the same object, never a loss;
nothing is cancelled except at a leave. See the `background-upload` / `background-upload` /
`background-upload` specs.)
(`gradle/libs.versions.toml` is the source of truth for versions.)

## Repo layout

```
api/            the Deno backend (bunny Edge Scripting) - explained in docs/ (architecture · testing · deployment)
site/           the Astro landing page
iosApp/         the Xcode project (app + upload-extension targets) - NOT a Gradle project
domain/ adapter/ ui/ app/ test/   the Gradle modules - mapped under Modules below
openspec/       specs/ (contract of record: user-observable outcomes) + changes/archive/ (decision records)
docs/           architecture.md · testing.md · deployment.md - engineering explanation (app + api), NOT contract
architecture/   GENERATED diagrams - `./gradlew architectureDiagrams` and commit; stale blocks the PR
metadata/       App Store listing copy + App Review notes
screenshots/    the 6 committed raw captures both the listing and the site derive from
scripts/        build and dev tooling (the phone's lock, guard and re-sign are the global `ios-device` skill's)
.ship/          this repo's half of the global `/ship` skill - gates, PR-title policy,
                post-merge hook, merge budgets (contract: `~/.claude/skills/ship/hooks.md`)
build-logic/    the included build holding the convention plugins (`snapsync.targets`: the allowed targets)
tools/ config/ gradle/            more build tooling
```

**There is no `backend/`** - it split into `api/` + `site/`. Capability names are whatever
`ls openspec/specs/` prints; never guess a spec path.

## Runbooks (load the skill before you start)

- **Touching the connected iPhone** - install, launch, screenshot, device logs -> load **`snapsync-device`**.
  It first has you load the machine-global `ios-device` skill, which owns the **device lock** (shared by
  every project on this machine; its guard hook refuses device commands without it), the Linux re-sign
  and the install. It stops at the running app: to *drive* one, see `rig-channel` below.
  (`pymobiledevice3`, `dvt`, the libimobiledevice tools, `.ios-device.yml`)
- **Seeing or clicking the app's UI without a device** -> load **`ui-harness`**. 🚫 **Never**
  `java.awt.Robot`, and never capture the real screen `:0` - it raises a portal consent prompt and
  **blocks until someone answers**. (`:test:harness-driver`, `driveForge`, `driveWorld`)
- **Anything needing a Mac** - an Xcode build, an `.xcarchive`, an IPA, code signing, or the
  `iosSimulatorArm64Test`s -> load **`ssh-mac-build`**. (`xcodebuild`, `.ssh-runner.yml`)
- **Testing a backend change against a real device** -> load **`local-backend`** first; it owns the
  three-hop chain and the one step whose omission is silent. (`deno task dev:local|dev:tunnel`)
- **Driving the app on device** — joining, creating, leaving, resetting, seeding, wiping, reading the
  selection policy, forcing an OS callback, reading live state — over the build-time-only control channel
  (`-Psnapsync.rig=true`, `/os`, `/user`, `/device`) -> load **`rig-channel`**. It needs the same device
  lock as `ios-device`. **There are no `SNAPSYNC_*` launch triggers any more**: production Kotlin declares
  none, and a guard fails the build if one returns. (`:test:rig`, `usbmux forward`)
- **Running the app on a SIMULATOR** — two members of one event at once, a headlessly seeded/wiped
  photo library, headless permission state -> load **`ios-simulator`**. It needs **no device lock**,
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

There is no narrative design doc. Three places carry the design:

- **`openspec/specs/<capability>/spec.md`** — the **contract of record** for what a **user can observe**
  (host, guest, web visitor): what happens in the app, in their photo library, on other members'
  devices, on the web, over time, under adverse conditions, and the privacy/security/retention
  promises. What, never how — no module, class, route or iOS API names. The rule and the swap test are
  `WHAT EARNS A SPEC` in `openspec/config.yaml`.
- **`openspec/changes/archive/<id>/`** — the **decision record**. Each archived change holds
  `proposal.md` (what & why) and `design.md` (`## Context`, `## Goals / Non-Goals`, `## Decisions`) —
  this is where rationale, rejected alternatives, and trade-offs live.
- **`docs/architecture.md` · `docs/testing.md` · `docs/deployment.md`** — engineering explanation for
  the app AND the api: the module graph and its laws (each pointing at the gate that enforces it), where
  tests live, the backend contract and storage layout, CI, release and deploy. Not contract: the gates in
  `./gradlew build` are the authority; the docs explain them.

Changing an observable outcome: read its spec, then its decision record. Changing anything else: read the
relevant `docs/` file and the code.

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
- 🏃 **`ch bg <cmd…>`** runs a command transparently — same stdio, same exit code — and exists only
  to put a marker in the command string so CodeHydra does **not** count that background shell as
  keeping the workspace busy. (The old standalone `ch-bg` wrapper still works and still counts as the
  marker, but `ch bg` is the canonical spelling — write that one.) Wrap long-lived processes that
  are *not* the work itself: dev servers, watchers, `tail -f`. Do **not** wrap work the workspace is genuinely doing
  (`/ship`'s merge wait rebases and force-pushes the worktree — it *should* read as busy), and note
  the prefix changes the command string, so it can fall outside an `allowed-tools` grant like
  `Bash(npx:*)` and start prompting.

## Build & test

- `./gradlew build` — the canonical check (compiles all targets + runs JVM tests). **No display
  needed**: the Compose Desktop UI tests (`:ui:screens:jvmTest`) render offscreen under
  `-Djava.awt.headless=true` (set on that task in `ui/screens/build.gradle.kts`), so no X server /
  Xvfb is required. Only the two harness run tasks — `:app:desktop:runForge` (forge) and
  `:app:desktop:run` (full-stack world, below) — open a real window and need a display.
  **`deno` on PATH is required**: `:adapter:generic:app:jvmTest` runs the backend port contracts against
  the REAL `api/`, launched as `src/dev/serve.ts --ephemeral` (loopback-only network, filesystem store, no
  zone reachable). Without Deno those tests FAIL naming it; they never skip. `api/src` is an input of that
  task, so a backend-only change re-runs them.
- `./gradlew compileIosMainKotlinMetadata` — the **Linux-runnable proxy** for the iOS source
  sets: it compiles `iosMain`/`commonMain` (and cinterop) without a Mac, so you can catch
  iOS-only breakage here. The actual iOS tests (`iosSimulatorArm64Test`, etc.) are **macOS-only**
  and run on GitHub Actions `macos-26`.

**Complexity ceilings** (`docs/architecture.md`) gate `build` too, as eight
`detekt*Tier` tasks — `shell` · `flow` · `compose` · `core` · `ui` · `harness` · `tests` ·
`buildscripts` — one per scope, because a detekt 1.x rule carries exactly ONE threshold per config.
Every tier layers **`config/detekt/_base.yml`** first, then its own file. The two are different things
and the base says so: `_base.yml` holds **readings** of a rule (what it MEANS, uniform everywhere — a
named constant has complied with `MagicNumber`; a PascalCase `@Composable` has complied with
`FunctionNaming`), while a tier file holds **ceilings** (one scope's measurement). A number never
belongs in the base.
**A tier's own file is OPTIONAL, and its absence means the scope sits at the baseline** — so the set of
files under `config/detekt/` is the list of scopes still carrying debt, and creating one is the visible
act of admitting a regression. `DetektTierCoverageTest` asserts every file belongs to a tier, the
reverse of what it once asserted.
**Every number in a tier file is a ceiling that may only fall**: lowering one is ordinary work, raising
one needs a stated forcing proof in the PR. Nothing enforces that — it is a ratchet carried by the
contract at the head of each config. `api/` carries the same measure through
`api/src/lint/complexity.ts`, a `deno lint` plugin under the `deno lint` gate `api.yml` already
runs (no published Deno rule measures complexity).

⚠️ **Compose trades these rules against each other, which is why `ui.yml` still holds three.**
`LongParameterList` is fixed by bundling, and a bundle is a constructor whose fields are counted;
`LongMethod` is fixed by extracting composables, and each extraction is a new function with a new
parameter list. Measured: two genuine extractions took `StatusScreen` from 138 statements to 71 while
the tier's totals ROSE. Do not treat those two numbers as debt to grind down.

⚠️ **`detektAppShell` is NOT one of these and must never be folded into them.** It is a *proof* —
threshold 2 asserts the shells hold no decisions — and its value comes from the number being 2. The
tiers are *budgets*, seeded at what the tree measured. Tier membership is derived from the live Gradle
project model, so a new module is scanned automatically or `DetektTierCoverageTest` fails naming it;
do not add a path list.

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
device. See `docs/testing.md`.

`./gradlew :app:desktop:run` launches the **full-stack world harness** (same module): the same
real status screen on the left — but its counts **emerge** from the real `LedgerBackedSyncStatusSource`
composed by `snapSyncApp` over `:test:world` (never forged) — and a right-pane **world inspector** (raw M3) that drives the real stack: presets,
**Invoke extension** (one `process()`-shaped cycle + download reconcile), the gallery/backend, the
upload-job queue and downloads, failure levers, and an engine-console footer. The operator plays the OS
(nothing auto-runs). See `docs/testing.md`.

**To drive either harness headlessly** - no display, and no screenshot of the real screen - load the
**`ui-harness`** skill: `:test:harness-driver` composes the shipped harness root into an offscreen
Compose scene served over HTTP, so an agent clicks the real buttons and reads back the real pixels.

## Releasing (TestFlight · App Store · screenshots)

**Contract and rationale live in the specs** — `docs/deployment.md`, `docs/deployment.md`,
`docs/deployment.md`, `docs/deployment.md`. Read those before changing any of it; what follows is
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
:domain                the platform-free core (`docs/architecture.md`): seven zones and the host, each its own module under domain/ with its package app.snapsync.<zone> (presentation and the host have their own entries below) — model/ (vocabulary + pure codecs + ALL pure data: UiState with RangeForm/ResolvedRange/ShareCount, every pure-data type a port carries (SecureStoreRead, CycleResult, EventLookup, DownloadState, ConfigRead, …), and the EventCreator command beside UserCommands; the config surface incl. the EventLink codec + generated LINK_ORIGIN, sync vocabulary, selection policy + SelectionCalibration (the floors and the album denylist, one product-policy value) + upload keys + device manifest + RawAsset mapping, edge-URL builder, SyncStatus/SyncProgress, GalleryAccess, the UploadMechanism kind + its pure resolver (OS fact + permission + an optional dev override → one mechanism), the UserCommands bundle type, and the Logger.invocation enter/exit helper over the injected ports/EntryContext seam), ports/ (every port seam, named for the need — outbound ones the core calls, plus the two INBOUND ports PlatformEntries/ExtensionEntries, what the OS tells each process, which the core implements and the shells drive: config, SecureStore [the platform's protected small-value store, every call addressed by a SecureSlot (service, account, shared); writes answer WriteOutcome; the iOS Keychain is its binding], Databases/Files/Preferences [the other thin storage ports], PlatformDeviceId [the platform's own stable id, null on iOS/JVM; no contract until an adapter answers one], the Gallery [the photo library: GalleryReader — what the extension gets — and Gallery, the app's, a Listenable<GalleryHandlers>: selection snapshots, import placeholder, import settle; decisions over it are the gallery services], gallery status/manifest, permission status, download + download-store, Backend [ONE thin typed port for the whole device API: one method per route, a `Reply` (Ok/Refused(status, body)/Malformed/Unreachable), a token passed exactly to the gated routes, no decisions], DeviceIntegrity [prove(challenge, handle?) — App Attest on iOS], upload transfer/scheduler/discovery, BackgroundTime [the app's background-time hold; the OS's expiry is the only "time is up"], OsCompletions [the one holder of OS completion handlers, released after the wake's own work or at the OS's expiry], push, AttestStore, LedgerStore, album, crash reporting — CrashReporter [thin: start/capture/breadcrumb/setContext/sendDump, a Listenable whose CrashHandlers shape what leaves] and ProcessMetrics [MetricKit's reports; None where there is no provider], both per-process and set up by compose/'s snapSyncProcess, every root's first act (CrashReporting in :domain:services decides: no DSN → nothing starts); the small per-need ports — SystemUi [share sheet, openUrl, the app's Settings page], Clock [now + the device zone, one per process], ProcessInfo [protectedDataAvailable, AVAILABLE/UNAVAILABLE/UNKNOWN — the diagnostic-only locked-since-boot read], LogSink [where a formatted log line is written], EntryContext [the ambient entry-point seam, enter/exit/current]), feature/ (mutually blind, feature-blindness gate armed; a type consumed OUTSIDE feature lives in that feature's readmodel package, feature/<feature>/readmodel/ — SyncStatusSource, the Creation*/Rename*/Download* status types + sources — and ReadModelImportsTest holds presentation, the host, :ui:* and :test:control to those packages): upload (UploadCycle + the port-pure cycle gate and its per-process admission, UploadTransitions over the AppUploadEngine/ExtensionRegistration seams, TailRunner — the one single-flight tail every OS wake hands its leftover work to, running its units ① import staged downloads · ② UploadCycle.topUp · ③ UploadCycle.walkAndPublish (full grant only) and stopping cooperatively on the OS's expiry, SyncEngine + LedgerWriter — the single ledger-writer feature — PushTailGuard, the silent push's active-event check before it joins the tail), membership (JoinEvent + DeviceEnroller, LeaveEvent, ShareSetLoad — the join-time ledger load, DeviceManifestProducer, MembershipRefresh + TitleNeed, ReconfigureEvent, ResetDeviceState, switchDecision), status (SyncStatusSource + LedgerBackedSyncStatusSource + LedgerCountsSource/Poller + OwnDeviceGalleryStatusSource), download (DownloadController + QueuedPhotoDownloadJobs + DownloadPushReceiver + the DownloadStatusSource read-model), album (AlbumCoordinator — ensureAlbum owns the granted/opt-in gate, albumIdFor the import-time lookup), creation (CreateEvent + the CreationStatus seams), push (PushRegistration over the PushTokenPublisher service and the PushTokenSource port), flow/ (the OS-callback trigger flows Foreground · Background · SilentPush [the cross-arm push fan-out] · Provision, each importing model/+feature/ ONLY — flow-no-ports gate armed; every port/platform touch injected as a compose/-built effect lambda; each flow transcribes into architecture/flows/ under the closed grammar, and an untranscribable flow FAILS generation), and compose/ (the SHARED composition, law "One shared composition": snapSyncProcess(ProcessPorts) → ProcessServices — every root's FIRST act: the one crash reporter started, the process metrics listened to, and the log writers the root installs; every composition below requires its ProcessServices; uploadCore(scope, ProcessServices, UploadPorts) → UploadCycle — the ONE cycle assembly both device tiers' roots AND the world call; snapSyncApp(scope, ProcessServices, AppPorts) → AppCore — the app feature graph, the flow instances, the live UserCommands bundle, and the permission-grant subscriptions as an explicit installPermissionSubscriptions() the app shell invokes from host assembly ONLY; platformEntries(core, hooks) and extensionEntries(ports, cycle) — the inbound ports' implementations the roots delegate to, contracted by PlatformEntriesContract/ExtensionEntriesContract). Zone edges implementation()-only and pinned by ModuleSetTest's permitted map; jvm+iosArm64+iosSimulatorArm64 from the snapsync.targets convention plugin (build-logic/); no iosMain source dir; feature-blindness + flow lifetime + read-model gates armed in :test:architecture
:domain:services       the services ZONE (package app.snapsync.services), between ports and feature: the shared capabilities built over the thin ports — what a store holds, when it opens, what a failure means. Today the storage services over the four thin storage ports (`Databases`, `Files`, `Preferences`, and in commit 3 `SecureStore`): LedgerService (ledger.db; either process opens it read-write and migrates), DownloadService (downloads.db; the app is its one writer and migrator) and SuppressionService (the extension's READ-ONLY suppression view: no store → nothing suppressed, an older schema → the cycle PAUSES [CycleResult.Paused, answered to iOS as processing], unopenable → skip); ConfigService (eventconfig.json in the SHARED area — only Files' NotFound is "not joined"), DeviceManifestService, StagingService (staged paths RELATIVE to the SHARED area; `locate` for the transport and the importer), LogTailService, AlbumMapService (+ the one-shot legacy-Keychain migration), removeOrphanedJoinMarker, PersistedDeviceIdentity (the device id over SecureStore + PlatformDeviceId: shared slot → legacy slot → platform id or random UUID → persist; a refused write or an unreadable read is Unavailable and never minted; READ_ONLY in the extension), AttestState (the attestation token + keyId; a refused write throws where the old store threw); CrashReporting (the process's crash reporting over the CrashReporter port: no DSN → nothing starts, the handlers that shape what leaves, the dump) + ProcessAccount (what a process-metric report does); CrashReporting (the process's crash reporting over the CrashReporter port: no DSN → nothing starts, the handlers that shape what leaves, the dump) + ProcessAccount (what a process-metric report does); the BACKEND services (package services.backend): AuthenticatedBackend — CredentialedBackend over the Backend port, the one place the backend's verdicts are decided (the credential read per call; a 401 on a gated call that carried a token drops THAT token, recovers through the Credential, and retries the call ONCE; a 426 on any route → AppVersionGate; a success clears it) — and the need-shaped services over it, bundled as BackendServices (EventDirectory, EventCreation, EventRename, EventJoin, ManifestPublisher, LeaveNotifier, EventUnionSource, DeviceFilesSource + DeviceListingShapeException, PushTokenPublisher); trust: DeviceAttestation (over DeviceIntegrity + the raw Backend's /attest routes + AttestState; the app's Credential — drop, re-attest, answer the token to retry with) + CachedAttestStore + ExtensionCredential (the extension's: drop only, never retry); version: AppVersionGate (the VersionRefusal cell, VersionRefusal itself in model/); both SQLDelight databases (.sq + generated code) live here. The gallery services over the Gallery port — GalleryDiscovery (a walk is authoritative only under a full grant), GalleryAssetPresence (only a full grant may say ABSENT), GalleryCandidateSource, GalleryAlbums (the denylist, matched per SelectionCalibration) — live here too. Opened on first use, never at composition, and only a successful open is kept. Each still implements its transitional store interface in ports/. Edges: model + ports only; its SQLite-backed tests live beside the Databases adapters (a :domain:* build file names no module)
:adapter:generic:app   platform-free technology impls of the :domain ports: HttpBackend (the Backend port over an engine-neutral Ktor HttpClient each composition supplies — Darwin on iOS, CIO for the real api/, MockEngine for the mini-edge: routes, bodies, the bearer header on the gated routes, the declared app version on every request, one log line per request; `isGatedRequest`, pinned to api/src/app.ts) + the SystemClock port impl + (jvmMain only) JdbcDatabases, the JVM `Databases` adapter over sqlite-jdbc; jvm+ios. Its jvmTest binds the Databases contract and the storage services' contracts (LedgerStore, DownloadStore — through the services over JdbcDatabases) from :test:contracts, and also holds the Backend contract's Live binding over HttpBackend, against the real api/ that :test:edge serves
:adapter:ios:ext-safe  every iOS adapter the EXTENSION process links (placed by linkage): IosGalleryReader (every photo-library read and album write either process makes; there is no persisted cursor), the PhotoKit upload-job platform (IosPhotoKitUploadPlatform — the BackgroundTransfer impl for the OS-driven tier), IosDatabases (the iOS `Databases` adapter: SQLite files in the App-Group container over SQLDelight's native driver, file protection, and a read-only open that never creates or migrates) + the App-Group consts, the one PHAuthorizationStatus→GalleryAccess mapping (currentPhotoPermission — the extension's admission read, delegated to by the app-only permission adapter), device-log sinks (FileLogSink/PublicNSLogSink) + IosEntryContext, the crash-reporting seat (SentryCrashReporter over the Sentry KMP SDK, capability `privacy-security`: a translation only — the scrub, the caps and the log mapping are model/Crash.kt's, registered as its CrashHandlers; the DSN is read from the process bundle by the root — absent in every dev build, injected only by CI Release archives), darwinHttpClient, IosDeviceIntegrity, IosFiles (the iOS `Files` adapter: SHARED = the App-Group container, PRIVATE = Documents; its error mapping — isFileAbsence beside it decides `NotFound`, isFileDenied `Denied` — is solely load-bearing for the leave decision, since the config service reads a missing config file as "left the event"; widening the not-found whitelist is a change to the leave decision, capability `photo-sharing`), IosPreferences (the App-Group `UserDefaults` suite) — and the Keychain impl: this is the ONLY module that may touch SecItem* (IosSecureStore, slot-addressed — a shared slot names the App-Group access group, an unshared one none — and platformSecureStore(), which on iosSimulatorArm64 keeps the device-id slot in an App-Group file; the :test:architecture guard enforces it, and the extension-safety gate forbids platform.UIKit/BackgroundTasks anywhere in it). IosSecureStore's four SecItem calls go through the internal KeychainApi seam, and IosDeviceIntegrity's four DCAppAttestService calls through the internal AppAttestApi seam (`docs/architecture.md`); src/rig/kotlin — the recording/replaying seams + the entitled-device SecureStore/DeviceIntegrity/AttestStore bindings — compiles into iosMain ONLY under -Psnapsync.rig=true, into iosTest otherwise
:adapter:ios:app-only  iOS adapters only the MAIN APP process links: IosUrlSessionUploadPlatform + IosBackgroundScheduler (the app's uploader, every iOS version), IosDownloadTransport, IosGallery (the app's Gallery: the selection observer, the import, the change token), PhotoLibraryPermission (the status, the permission dialog and the limited-library picker; the Settings page is IosSystemUi's), IosSystemUi (the UIKit share sheet, openURL through the recorded UrlOpenerApi seam, the Settings page), IosProcessInfo (UIApplication.isProtectedDataAvailable) — the two URLSession adapters own OS-reattached app-process session ids, so an extension-side link must stay structurally impossible; IosBackgroundScheduler's BGTaskScheduler calls go through the internal BackgroundTaskApi seam (`docs/architecture.md`); src/rig/kotlin — the simulator app's live bindings of the PhotoKit contracts, for the adapters of BOTH iOS adapter modules, and of ProcessInfo, plus the registry the ios-contracts job runs — compiles into iosMain ONLY under -Psnapsync.rig=true, into iosTest otherwise
:adapter:generic:fake  the honest in-memory port impls (package app.snapsync.fake): InMemoryLedgerStore/DownloadStore/SecureStore/AttestStore/DeviceIntegrity/Backend (the in-memory backend: UUID event ids, a byte store the caller holds as initial state, the attest routes over the in-memory integrity's proofs)/GalleryStatusSource/CrashReporter/BackgroundScheduler + the config ports (InMemoryConfigStore, one double behind three port-typed factories) + the photo-library fakes (InMemoryGallery — whose LibraryChangeAnswers collaborator is how the world scripts a refused/held/failed import — and PhotoAccess) + the manifest/album-map stores — what the world harness and every integration test stand on. Honesty is MECHANICAL, and the compiler enforces it: every fake class is `internal` and its only public surface is a factory in Factories.kt (the photo-library fakes': PhotoLibraryFactories.kt) returning the PORT type, taking initial state (the gallery fakes take a MutableStateFlow cell); operator rigging (levers, inspection) lives in :test:world wrappers (WorldGallery (with its WorldImports script), RecordingDownloadStore), never here. jvm+iosSimulatorArm64 only (never links into a shipped framework); commonTest hosts the re-homed fake-driven feature tests (RawAssetMapping, status sources, download trio, DeviceAttestation) and binds the fakes to their port contracts
:domain:presentation   the presentation ZONE: the Orbit MVI container reducing into model/'s UiState (Compose-free, no engine dep) — its module depends only on model + feature (implementation()), so ports/ and flow/ do not resolve, and ReadModelImportsTest confines its feature/ references to readmodel packages. StatusContainerHost's inputs are exactly read-models (bare StateFlows + feature sources), the model/ UserCommands bundle (user taps incl. requestAccess/openSettings; live instance built only in compose/), the loadJoinDetails query, and the pure CutoffFormatter (now/zone injected; the host binds displayClock's now and the process Clock's zone, read once). Also hosts the forgeStatusHost forge factory (formatter passed in by the shell)
:ui:screens            Compose screens (written against App* only) + statusActions(host, …), the ONE tap → intent table every host takes (click-tested here); both sides speak model/'s one Arrow enum; CutoffFormatter is a required param (no system-reading default)
:ui:components         App* design system + the Material 3 skin; api(:domain) for the model/ Arrow in AppStatusLine's signature (the one enum presentation and the skin both render from)
:domain:host           the host ZONE (package app.snapsync.host), the SHARED HOST COMPOSITION: snapSyncHost(scope, ProcessServices, AppPorts) → ComposedApp — the core from snapSyncApp AND, on first touch of `host`, the host-assembly subscriptions (permission + push registration) and the StatusContainerHost over every core read-model. The iOS root and the world (hence the JVM rig host, the desktop harness's sources, the inbound-port fixtures) all call it and supply only ports, so no root can build a host that observes less than the phone's. The one zone that sees both compose/ and presentation (edges: model, ports, feature, compose, presentation — implementation() only); wiring only (scanned by detektAppShell, tiered as a shell), no test source set
:app:desktop           the ONE desktop module: the shared pane library (PhoneFrame + StatusPane, StatusContainerHost wiring) + BOTH harness apps — the full-stack world harness (:app:desktop:run; real StatusScreen whose counts EMERGE from the AppCore the world composes via snapSyncHost, + a right-pane world inspector; with -Psnapsync.attach=<url> it instead MIRRORS a remote control-channel host, re-composing StatusScreen from the wire UiState and posting taps as /user intents; `docs/testing.md`) and the forge harness (:app:desktop:runForge, a JavaExec — the Compose plugin models one application main class; phone frame + control panel forging any UI state; `docs/testing.md`)
:app:ios               iOS app wiring + framework export (thin, untested); SnapSyncRoot holds ONE switch — whether this OS carries the OS-driven upload mechanism at all, which decides only whether that producer is CONSTRUCTED (its registration selector does not exist below 26.1). Whether the extension may be REGISTERED is model/'s extensionRegistrable over the OS fact, the current photo permission, and an optional per-uploader switch the control channel supplies (both uploaders run; each gates itself); the root builds the platform adapters and calls :domain:host's snapSyncHost (the core AND its status host — it assembles no host itself), and implements PlatformEntries BY DELEGATION to compose/'s platformEntries (it holds no forwarding body for an OS entry)
:app:ios:extension     iOS ≥26.1 background-upload extension: the composition root (UploadExtensionRoot, implementing ExtensionEntries by delegation to compose/'s extensionEntries) calling :domain compose/'s uploadCore over :adapter:ios:ext-safe (which holds the PhotoKit platform adapter) + :adapter:generic:app — the SnapSyncUploadKit framework; thin, untested (orchestration + tests live in :domain feature/upload)
:app:ios:forge         the FORGE binary (built ONLY under -Psnapsync.forge=true): the real StatusScreen over forged sources, for the marketing screenshots. A separate module and Xcode target because that is the only way to CONTAIN it — it links neither :app:ios nor the live graph, so a forge process cannot boot the live stack because there is nothing in it to boot (`docs/architecture.md`, "A build-time-only module is contained by compilation"). Without the property it compiles NOTHING (empty srcDirs), and :domain:presentation stops carrying the preset table too
:test:world            test-only shared infra: the controllable in-memory "world" — BackendStore + MockEngine mini-edge + operator levers/wrappers rigging :adapter:generic:fake's honest doubles — whose World.core and World.statusHost ARE the real AppCore and status host from the SAME snapSyncHost the iOS shell calls; World.relaunch() is process death over a stated durable/memory split; the mini-edge records the pushes it would send (the APNs mock) (features, flows, and the UserCommands bundle are production instances), with the extension-tier cycle from the same uploadCore. Its backend is ONE seam (WorldBackend): the mini-edge by default, or the real api/ (DenoBackend, jvmMain, via :test:edge); World.neutral holds the backend-neutral reads/levers (Answer.Unavailable, never a silent no-op, where the real backend cannot honour one) and provisionMinted/addForeignDeviceMinted take backend-minted event ids — World.store and caller-chosen ids are mini-edge-only. The upload double completes by replaying the engine's request as a real PUT. It hosts NO port contracts (those live in :test:contracts); its commonTest binds the mini-edge as the Backend contract's Fake (MiniEdgeContractsTest), so mini-edge drift on a contracted route is a red build, and binds its two transfer doubles (FakeBackgroundTransfer, FakeDownloadTransport) DIRECTLY as the transfer contracts' Fakes (TransferContractsTest) — the binding plays the network through their operator actions; the world's PhotoKit-tier retry stays uncontracted. Its test source sets also bind the inbound ports' contracts (PlatformEntries/ExtensionEntries, Live, JVM + IOS_SIM_KEXE, over the composed world). jvm()+iosSimulatorArm64. Consumed by :app:desktop and the control channel's JVM host — never by :test:integration, which reaches it only over the protocol (`docs/testing.md`)
:test:contracts        test-only, CONTAINED (`docs/architecture.md`): the contract mechanism + every port contract as CLAUSE VALUES (LedgerStoreContract — with its size-split parts — DownloadStoreContract, SecureStoreContract, the App-Group stores' ConfigStoreContract/DeviceManifestStoreContract/StagedBytesContract/DeviceLogSourceContract/AlbumMapStoreContract, the photo-library contracts (GalleryReader, Gallery, GalleryImport, PhotoAccess — clauses share one real library and each owns a capture-date window, PhotoLibrary.window), the three transfer contracts (BackgroundTransferContract — ONE for both upload tiers, tier-neutral clauses — DownloadTransportContract and BackgroundSchedulerContract; the two URLSession ones exchange bytes with the loopback fixture scripts/transfer-fixture.py over the TransferFixture route grammar, passed as the contract verb's ?fixture=), the inbound ports' PlatformEntriesContract/ExtensionEntriesContract, CrashReporterContract (over the real Sentry SDK against a loopback ingest in the test executable), whose clauses observe outcomes through a binding-supplied handle, and ONE BackendContract (with its size-split parts: events, membership, listings, attest — challenge + refusals; a successful mint/renew has no host) over EdgeSubject + a BackendSetup that enters every backend state through the backend's public surface (EdgeSetup over HTTP, PortSetup through the port for the in-memory mock), and the credential/state contracts DeviceIntegrity + AttestStore (refusals live on IOS_SIM_KEXE, the rest recorded on IOS_DEVICE_APP with credential bytes masked) and ProcessInfo (live on IOS_SIM_APP); the code IS the spec of a port's clauses). A binding pairs one implementation with one Host (JVM · IOS_SIM_KEXE · IOS_SIM_APP · IOS_DEVICE_APP) and enters each clause's state at construction or answers Unreachable; outcomes Passed/Failed/NotRunHere/Diverged/NotWithin, never a silent skip. Bindings live beside their implementations (fakes: :adapter:generic:fake commonTest; SQLDelight and the live backend: :adapter:generic:app tests; the mini-edge: :test:world commonTest; Keychain/App-Group: :adapter:ios:ext-safe tests (the Files/Preferences/Databases adapters, and the storage services bound through them); the no-grant PhotoKit reads: the iOS adapter modules' iosTest; PhotoKit under a full grant and both URLSession transports, over the simulator target's default session: :adapter:ios:app-only's rig source set; BGTaskScheduler: recorded on the device, replayed in :adapter:ios:app-only's iosTest). The SIMULATOR APP (IOS_SIM_APP — the only simulator process applesimutils can grant photo access) is run LIVE on every push by the ios-contracts job over the rig (`GET /contract` lists the host's registry, scripts/sim-contracts runs it). Hosts CI cannot reach are RECORDED at the OS boundary on a device (rig `POST /contract/<name>` → test/contracts/recordings/<Contract>@<HOST>.rec, committed unedited) and REPLAYED against the current adapter on every build. jvm+iosSimulatorArm64+iosArm64; links into the app only under -Psnapsync.rig=true; the only module whose MAIN code depends on kotlin-test
:test:architecture     test-only JVM guards for invariants the compiler cannot express (`docs/architecture.md`), all gating ./gradlew build: the zone gates (feature-blindness, flow lifetime, ReadModelImportsTest — the permitted zone edges themselves are compile boundaries pinned by ModuleSetTest), KeychainContainmentTest (no SecItem* outside :adapter:ios:ext-safe — catches fully-qualified calls, which no linter can see on iosMain), the extension-safety gate (no platform.UIKit/BackgroundTasks in extension-linked source), RuntimeIdentityTest (every OS-held literal exactly once), the entitlements guard (never raise default-data-protection to NSFileProtectionComplete — it would make every App-Group file unreadable while locked, killing the background tier), SwiftShellGuardTest + KotlinShellGuardTest (the shell decision pins, exact in both directions; detektAppShell itself gates inside check), ModuleSetTest (settings == the target module set), MixedPortImplTest (no port interface beside a technology impl), ContractCoverageTest (every port-contract clause runs against a real implementation on some host; a recorded host counts only through its recording), DeletionLedgerTest (the migration's retired dead weight stays dead), RunbookSkillsTest (every skill CLAUDE.md's Runbooks block points at exists in-repo, and production Kotlin declares no SNAPSYNC_* launch trigger), EventLink guards
:test:integration      test-only, JVM-only: the seam → UI-state integration surface, driven ONLY through :test:control against a fresh in-process JVM rig host per test (rigTest { … } in Rig.kt) — it cannot compile against World, ports/, flow/ or compose/. Asserts only OBSERVABLE outcomes: UiState, and what a system outside the app records (backend objects/union/manifest/device config/request counts, the photo library, OS upload jobs, the staging dir, reporter dumps, pushes sent, logs) — never ledger/download-store content, RigState.ledger included. Its `journeys` source set (:test:integration:journeys, outside build) holds the all-real journeys ios-contracts runs against ONE simulator app and a local api/, the second member played by the journey itself over the backend's public HTTP surface (Member.kt)
:test:harness-driver   test-only dev infra (non-gating, no spec): serves EITHER desktop harness over HTTP with no window — composes the shipped ForgeHarnessRoot/WorldHarnessRoot into an offscreen Compose scene (CPU raster Skia; no X server, no screen-capture portal) so an agent can click the real buttons and read back the real pixels + semantics tree. Runbook above; rationale in Driver.kt
:test:rig              the CONTROL CHANNEL (`docs/testing.md`, "One control protocol, served by two hosts"): ONE HTTP protocol — /os entry points, /user intents, /device state and verbs — served by TWO hosts from the same server, routes and RigState. The APP host is a Ktor CIO server linked into :app:ios ONLY under -Psnapsync.rig=true (reached over usbmux forward or the simulator's loopback), contained at COMPILE TIME and contributing its own call site into :app:ios; the JVM host (jvm() target, JvmRigHost) serves a :test:world World — the real AppCore — over the mini-edge or the real api/ (`./gradlew :test:rig:runJvmHost -Psnapsync.rigBackend=mini|deno`). One closed /device + /os vocabulary (RigVocabulary): GET /device lists what this host honours and refuses, and a refused verb answers 409 with its reason. Its commonMain is tested through :test:control; the iOS seeder/wiper remain untested. The one module that may depend on ktor-server-*. Runbook: load the `rig-channel` skill
:test:control          test-only, JVM-only support: the protocol's typed client (RigClient — health/device/state/awaitState/user/os/deviceVerb/contract, a 409 as a typed Reply.Refused) and the JVM host's tests, over both backends. Depends on :test:rig's JVM variant for the wire types plus :domain:model (the real UiState), :domain:presentation and :domain:feature, each declared explicitly; the rig's own deps are `implementation`, so a client never compiles against ports/, flow/, compose/ or the host, and ReadModelImportsTest confines its feature/ references to readmodel packages — the compile boundary and that gate are the read-model rule
:test:edge             test-only, JVM-only support: LiveEdge — the real api/ as a local deno process (serve.ts --ephemeral, loopback-only, filesystem store), one per test JVM — shared by the Backend contract's Live binding and the world's real-backend option; owns `resolveLocalDeployment`. Consumers set snapsync.apiDir/liveEdgeStore and declare api/ sources as test inputs
:tools:diagrams        test-only build tooling (`docs/architecture.md`): generates the architecture/ diagram set from the code and build model. Its :tools:diagrams:test is the local half of the freshness gate; the CI half is the `diagrams` required check
iosApp/                Xcode project (app + upload-extension targets) — not Gradle
```

**The graph and its laws** are explained in **`docs/architecture.md`** — read it before moving code or
changing a boundary. The authority is the build, not the doc: every gated law is a permanent check under
`./gradlew build` (`:test:architecture` + `detektAppShell` + the flow-transcriber generation failure), and
the doc marks the laws that are review-only. The **`diagrams`** check IS required: stale `architecture/`
blocks the PR — run `./gradlew architectureDiagrams` and commit. Rationale for any placement lives in the
decision records under `openspec/changes/archive/`.

## The laws

Explained — one line each, with the gate that enforces it — in **`docs/architecture.md`**. Do not copy them
here: the gates are the authority and the doc is the one explanation.

Still true and not a law: because iOS targets are present, `commonMain` is limited to the common
stdlib + each zone's allowlisted libraries — JVM-only APIs there break the iOS compile (verify
with the proxy task above).

## Logging & errors

- Log via **Kermit** (multiplatform). Cross-cutting logging infra lives in **`:domain`'s `model/`**:
  the `Logger.invocation` enter/exit helper (params + result + duration), driving the injected
  `ports/EntryContext` seam, and `logLineBody`, the one line format. `snapSyncProcess` installs the process's
  Kermit writers (services' `SinkLogWriter` over the `LogSink` port, plus the crash channel's) and logs the boot
  banner. The iOS ambient prefix global (`IosEntryContext`) and the device-log sinks (`FileLogSink`, which keeps
  its descriptor, lock and roll inside, / `PublicNSLogSink`) live in `:adapter:ios:ext-safe`.
- **Device diagnostics** (capability `privacy-security`): the app and extension are separate
  processes, each writing its **own** verbatim, un-redacted log - the app to its `Documents/debug.log`,
  the extension to `ext-debug.log` in the **shared App Group** (not pullable, so getting it over USB
  takes one extra launch). Each is the **canonical un-redacted channel** (os_log redacts `<private>`)
  and rolls to a `.1` sibling past 10 MB. To pull either off a device, load the **`snapsync-device`** skill.
- **Sending the logs off-device** (capability `privacy-security`): **double-tap the "SnapSync" label**
  at the top of any screen → a confirm dialog → one diagnostic dump reaches Bugsink (state + counts +
  the tail of BOTH logs, ~700 KB total, sent **verbatim** — ids intact, unlike automatic crash
  events). It is deliberately invisible: no button, no semantics, and on a build with no baked
  DSN (every dev/sideload build) **no dialog opens at all**. To exercise it on device, **dispatch the
  branch** — `gh workflow run ios.yml --ref <branch>` — which builds the release channel and delivers to
  internal TestFlight. ⚠️ Injecting `SENTRY_DSN` on the dev build loop's `xcodebuild` line **no longer works**:
  the DSN rides in the generated `Deployment.plist` bundled as a resource, and a build-setting override
  cannot substitute into a resource file. Measured against the hosted instance (2026-07-29):
  Bugsink **drops attachments entirely**, caps events at `MAX_EVENT_SIZE` = 1 MiB (judged on the decoded
  body — the gzip wire size says nothing), and stores 340 KB context strings **byte-identical**.
  ⚠️ An over-cap event is **not** dropped: sentry-cocoa deletes a cached envelope only on a `200`, and
  sends the oldest first, so a rejected one stays in the process's `Caches/io.sentry`, is re-sent on every
  trigger, and **blocks every report behind it, across launches**, until `maxCacheItems` (30) newer
  envelopes evict it. Nothing on screen says so (source: `SentryHttpTransport.m` at 8.58.2; M7 in
  `changes/archive/2026-09-23-diagnostics-reporter-contracts`). So every outgoing event is bounded **by
  construction** (capability `privacy-security`; the caps sit in `model/EventBounds.kt`), and the
  `CrashReporter` contract's `WIRE_WORST_CASE_DUMP_ARRIVES` clause fails `ios-test` if they stop fitting.
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
- **Crash reporting** (capability `privacy-security`): production builds report crashes and
  `Error`/`Assert`-severity Kermit lines (lower severities ride as breadcrumbs) to the operator's
  Bugsink instance, in **both** processes, via the `CrashReporter` port `snapSyncProcess` starts as
  every root's first act. Every UUID-shaped token is scrubbed before send (an eventId IS the upload
  capability); the SDK's random per-install `user.id` is the one deliberate exception — do not
  "fix" it into the scrub. The DSN exists only as the `SENTRY_DSN` CI secret, resolved into the
  generated **`Deployment.plist`** (`docs/deployment.md`) that both bundles carry —
  never into `Deployment.xcconfig`, where `//` opens a comment and silently truncated it to `https:`,
  shipping four mute TestFlight builds (644-673). `ios.yml` reads all four deployment values back out of
  the app **and** the `.appex` after archiving, so a truncated value or a resource that missed a bundle
  fails the run. Dev/sideload builds carry no DSN, so the SDK never starts there. Bugsink
  ingests no dSYMs: `ios-deliver` parks each main build's dSYMs as a `dsyms-<build>` artifact for
  offline `atos` symbolication (90-day cap; park longer-lived versions' dSYMs elsewhere at promote
  time). **Triage these crashes with the `/bugsink` skill** (`.claude/skills/bugsink/`, non-gating
  dev infra; read-only apart from ONE write — resolving an issue a shipped fix closes, on
  confirmation, which `/ship` fires from a `Bugsink-Resolves:` commit trailer): it lists
  unresolved issues from `steho.bugsink.com` (project 1, API
  `/api/canonical/0/`, `BUGSINK_TOKEN` via secrets-env) and drills into one for the symbolicated
  stacktrace — symbolication runs **on Linux** via the `symbolic` lib against the `dsyms-<data.dist>`
  artifact (no Mac/atos needed), and fails loud when that artifact has expired.
- Errors are **reduced into state**: sealed domain errors → `UiState`, converted at capability
  boundaries — not thrown to the UI. This is also what lets the harness force any failure state.

## Testing strategy

Read **`docs/testing.md`** before adding a test file or deciding where one goes — it explains where each
kind of test lives and which targets it runs on, why the `:test:*` modules exist, the port contracts and
their hosts, and why some coverage is measured on hardware rather than asserted.

## Workflow

- **All changes** go through a branch → PR → **`/ship`** (branch protection forbids direct pushes
  to `main`). Every PR carries exactly one changelog label — `enhancement` · `bug` · `internal` —
  which `/ship` applies and the required `check-label` gate enforces; the App Store release notes are
  derived from it (`docs/deployment.md`), so `internal` means "no customer sees this".
- **`/ship` is a GLOBAL skill** (`~/.claude/skills/ship/`), not a file in this repo. Everything that
  varies per repo lives in **`.ship/`** — `gates.sh` (the local half of what gates a merge),
  `pr-title.md` (the App Store title policy), `post-merge.md` (the Bugsink resolve), `config.json`
  (the merge budgets). The hook contract is `~/.claude/skills/ship/hooks.md`; read it before editing
  any of them, and do not add a sixth file expecting ship to read it.
- **The branch ruleset is LIVE-ONLY** — ship owns it as an exhaustive baseline and there is no
  committed copy to edit. Read it with `gh api repos/stefanhoelzl/SnapSync/rulesets`. Required-check
  contexts accumulate additively from what actually executed on a PR; dropping one is a deliberate,
  confirmed act inside `/ship`, never a file edit.
- **The OpenSpec flow is user-driven — never entered on the agent's initiative.** Changes that
  **add, alter, or remove a user-observable outcome** go through it (propose → apply → sync/archive) so
  `openspec/specs/` stays the contract of record — but *entering* the flow is the user's call, and so
  is **every phase boundary** inside it. On a behavior-touching request: name the affected
  capability, say it needs a change, and **stop** — no code, no `openspec/` file — until the user
  picks the route (OpenSpec proposal or direct change). Then run **only the phase asked for**: after
  `propose`, wait for the word before `apply`; after the tasks land, wait before `sync`/`archive`.
  Never invoke an `opsx` skill or the `openspec` CLI unbidden. **Reading is the opposite, and is
  mandatory**: read the capability's spec and its decision record before changing anything.
- Work no user could observe — build/CI, dependency bumps, refactors, a new mechanism, a platform
  workaround, docs — touches no spec and skips OpenSpec (the swap test in `openspec/config.yaml`): branch → PR → `/ship`. Classify honestly and **ask when unsure**; a wrong "mechanical"
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
  root, and the one surface `update` does not rewrite (`openspec/config.yaml`). Never
  patch a rule into `.claude/`; the tool will take it away and tell no one.
- **`openspec validate --specs --strict` checks structure, not truth.** It asks whether a spec has a
  Purpose, Requirements, and SHALL/WHEN/THEN with a scenario — it has never opened a `.kt` file. It
  passed 50/50 on a tree carrying 28 audited drifts (four specs contradicting themselves *within one
  file*), and it passes 50/50 now that they are swept: the same answer with the lies in and with them
  out. Green means well-formed. It does not mean true, and nothing in CI does.
