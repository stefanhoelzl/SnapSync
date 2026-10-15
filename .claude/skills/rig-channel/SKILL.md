---
name: rig-channel
description: >-
  Drive the app running on a phone (SE2, XS, or the A40), over HTTP — the build-time-only
  control channel (:test:rig, -Psnapsync.rig=true). This is how you join, create,
  leave, reset device state, seed or wipe the photo library, read the selection
  policy, force an OS callback, and read live UiState. Use when the task means
  "join an event on device", "create an event", "seed photos", "wipe the
  gallery", "reset the device", "make the app foreground / silent-push / run the
  background task", "why did no upload cycle run", "read the extension's log", or
  anything touching the typed services (`rig`), GET /device or the rig's port forward — or the SAME
  protocol served by the JVM host over mocks (`:test:rig:runJvmHost`, no
  device, no lock). To install or launch a build first, load `snapsync-device`.
---

# rig-channel — driving the app's entry points over HTTP

The entry points that carry every interesting on-device behaviour **cannot be fired from the
sandbox**. The app-driven upload cycle is kicked only by a `BGProcessingTask` heartbeat or a
background-`URLSession` relaunch, so a headless host fires **neither**: a fully joined device with
access granted and the tier armed will sit there running **no cycle at all**, and nothing says so.

`:test:rig` is a Ktor CIO server that runs inside the app and lets you drive those entry points and
read the state back. Its protocol is specified (`docs/testing.md`, "One control protocol,
served by two hosts") and served by **two hosts**: this app host, and a JVM host — the app the JVM root
`:app:jvm` composes over the mocks (see "The JVM host" below). Every surface is a projection of a contract specified elsewhere, so there is no
second way-to-drive that can rot or lie.

To **build** the IPA, load `ssh-mac-build`. To install/launch it, load `snapsync-device` (which has
you load the global `device` skill first). On the A40, `snapsync-android` builds, installs and forwards instead;
everything from "The endpoints" on is the same protocol there.

## Take the phone's lock first

Every phone is shared by every project on this machine, and each is a CodeHydra lock of its own, named
after it (`SE2`, `XS`, `A40` — the global `device` skill's `devices.json`). Everything here is inside that
skill's guard: a device command must **name its phone** (`--udid`, `-s`), and this workspace must hold that
phone's lock. Take it exactly as that skill describes — as a **background** Bash call, **never** under
`ch bg` (waiting for the phone is this workspace being busy):

```
ch lock take SE2 "<why you need the phone>"      # queues FCFS; exits once this workspace holds it
```

The **workspace** holds the lock, not a process: it stays held across calls and turns until you release
it. `ch lock ls` shows the holder, age, reason and waiters. When the device work is done — the forward
stopped first:

```
ch lock release SE2
```

Which iPhone: the **SE2** runs iOS 26.6 — both uploaders, the PhotoKit extension included. The **XS** is on
iOS 18, so it never runs the extension: only the app's uploader, and `extension-host` and the extension's
contract recordings have nothing to reach there. Every on-device measurement this repo cites is the SE2's
unless it says otherwise.

## Containment — why this never ships

`-Psnapsync.rig=true` adds BOTH the `:test:rig` dependency and the source directory it contributes
into `:app:ios`. Without the property it adds **neither**, so a production build contains no rig
source at all — not a stub, not an inert branch. Measured: `_kclass:app.snapsync.rig.RigServer` is
present in the binary with the property and there are **zero** `app.snapsync.rig` symbols without it.

This is also why `SNAPSYNC_RIG_PORT` is not a launch trigger (production Kotlin declares none): it is
read by a file that **does not exist** in a production build, so it is inert by construction rather
than by a runtime check.

## Building and connecting

On the build runner, before `xcodebuild` (Xcode's Gradle invocation picks it up). It goes in the
runner's `GRADLE_USER_HOME`, which outranks the project's — **never** in the repo's tracked
`gradle.properties`, where one forgotten revert would link the channel into every shipped build:

```
printf "snapsync.rig=true\n" >> ~/.gradle/gradle.properties
```

Then, with the build installed and launched:

```
# under `ch bg`, backgrounded: it blocks for as long as the forward is up. <host port> is the PHONE's own
# (devices.json: SE2 18101, XS 18102), so two phones' rigs can be up at once; 18099 is the app's, on the phone.
ch bg uvx --python 3.14 pymobiledevice3 usbmux forward <host port> 18099 --udid <udid>
```

Then, in **every** call that talks to the channel (shell state does not persist between calls):

```
B=http://127.0.0.1:18101        # the SE2's host port; the XS's is 18102
R="test/cli/build/install/rig/bin/rig --url $B"   # the typed services' command line: ./gradlew :test:cli:installDist -q
curl -sS --max-time 180 "$B/health"
```

The guard requires `--udid` on the forward — `usbmux forward` ignores `PYMOBILEDEVICE3_UDID` and reaches
whichever phone it finds first. Stop the forward before releasing the lock.

⏱️ **Use a uniform, generous `--timeout` (180 s).** A delivery awaited with `--await-release` legitimately blocks until the app
releases the OS completion handler — after the wake's own work, or (heartbeat) after its whole tail, or on
the operating system's expiry. No clock of the app's own bounds it any more (`changes/own-work-per-wake`),
so a short timeout makes a transport failure indistinguishable from a slow wake — the same
absence-collapse this repo keeps paying for, reintroduced where nobody looks for it.

🔢 **Port 18099 is a DEVICE-ONLY default** — the port the rig binds *on the phone*. One app instance runs per
device, so a fixed port needs no discovery; the host side is the phone's own port above. All simulators on a host **share the host's loopback**, so a simulator must override it —
`SIMCTL_CHILD_SNAPSYNC_RIG_PORT=<port>`, because `simctl launch <dev> <bundle> KEY=VAL` passes
**argv, not environment**. The bind address is `127.0.0.1` and nothing else, gated.

📄 **On a simulator, read the port instead of assuming it.** The rig publishes the port it actually
bound to `Documents/rig.port` in its own container, reachable with
`xcrun simctl get_app_container <dev> app.snapsync data`. **No file means the bind failed** — which is
the one case a fixed default hides, since a colliding instance would answer your `curl` with the very
port you asked for. Full simulator runbook: load **`ios-simulator`**.

## The endpoints

Most of the channel is **typed services** over kRPC (`ws://…/rpc`), which curl cannot reach: drive them with **`rig`**
(`:test:cli`, "The control channel's typed services" below — exit codes, flags, `--timeout`). What is left on HTTP is
liveness and the advertisement: the string verbs (`/os/<root>/<member>`, `POST /device/<verb>`) are gone (3g-a).

```
GET  /health                            rig=up, port, boot instant — liveness ONLY
GET  /device                            what THIS host honours and refuses (= `rig rig advertise`), each refusal with
                                        its reason; 500 names a gap

$R dev-controls reset                      void durable sync state
$R dev-controls pin --app false --extension true   switch one uploader off (see below); `unpin` for both
$R screen tap --intent '<UiIntent JSON>'   what a finger does — the ONE person verb, e.g. '{"type":"LeaveEvent"}'
$R screen shown                            what the screen shows now
$R host state                              the real UiState + readiness + download progress + build facts
$R host logs [--process extension] [--bytes N]   a process's device log, BOTH processes (agents only — never a test)
$R host disk [--depth 2] [--largest 20]    what the app's files take on the REAL disk, per container: `shared` (the App
                                           Group — iOS's "Documents & Data", not USB-pullable) and `app`. iOS only
$R host process-metrics --fields '{"k":"v"}'   a synthetic MetricKit report through the app's own handler. iOS only
$R gallery read [--cutoff …] [--resources]      the library, through the app's own policy
$R gallery seed --n N --kind bulk|policy|noise
$R gallery add-moving --photo '{"date":…,"files":[{"name":…,"data":<base64>}]}'   ONE photo from a camera's own files —
                                           iPhone: a still (+ its movie → a Live Photo); Android: one motion-photo file
$R --timeout 0 gallery wipe --scope all|assets|albums [--limit N] [--offset N]
$R adapters current | choose --choice "$(cat file)" | clear   the launch-time adapter choice (choose/clear EXIT the app)
$R contract list [--grant G] [--precondition P]    the contracts registered for THIS host
$R --timeout 0 contract run --name <name> [--host H] [--grant G] [--params '{"k":"v"}']
                                           run a port contract in-app; prints its RECORDING (device) or its OUTCOME TABLE
                                           (simulator app) — see below
```

A host classifies everything it is asked: a `/device` verb it cannot honour answers **`409` with the reason**; a typed
method it cannot honour is **refused** (`rig` exit `3`, `refused: <service>.<method> is refused here: <reason>`) — never
a success that did nothing. On this host an operator lever is honoured only for a system the adapter choice mocks
(below) and refused, naming the real system, otherwise; on a device, the simulator-only upload-job verbs are refused too.

**`host state` is the reduced state, not a mirror of it** — `UiState` is `@Serializable` where it is
declared, so the encoder is compiler-generated. Beside it: download progress, **readiness** (derived from
`ui`), the build facts (which backend this build points at), and the OS's own view of the extension
registration. It carries **no ledger counts** any more (dropped in 11g2 with `permission`, `inviteUrl`,
`eventName` and `transientError`, the last four being inside `ui`): whether bytes landed is the backend's to say —
on a device, read it from the backend (`snapsync-device`, "did the upload land"), not from the app's bookkeeping.

📄 **`host logs --process extension` needs no relaunch, no copy step and no `apps pull`.** An unreadable or absent log
fails with a stated reason, never an empty answer. It reads the **current** file only — a rolled `.1` sibling is not
reachable this way.


## `contract run` — recording a port contract on the device

Capability `docs/architecture.md`. A contract whose clauses need the **entitled** Keychain (`SecureStore`: the
legacy-protection upgrade, a real write read back) cannot run in CI — a simulator test executable is
refused every Keychain call. So it runs here, in-app, and every `SecItem*` call the adapter makes is
recorded with iOS's answer; CI then **replays** that recording against the current adapter on every build.

```bash
$R --timeout 0 contract run --name SecureStore > test/contracts/recordings/SecureStore@IOS_DEVICE_APP.rec
git diff test/contracts/recordings/        # review it like code, then commit it UNEDITED
```

- The body **is** the file: a provenance header (device, iOS, build, Kotlin, date), a `# live <CLAUSE>:`
  line per clause saying how it went on the device, then one `[CLAUSE_ID]` block of `call -> answer` lines.
  Never hand-edit it — the replay matches calls exactly and in order.
- **Re-record when CI says `Diverged`**: the adapter now asks iOS something the recording does not hold.
  A `Failed` on replay is different — iOS's recorded answer violates the clause — and re-recording will not
  fix it; the code or the clause is wrong.
- A `# live …: Failed(…)` line means the clause failed **on the device**. Commit it anyway if that is the
  truth; the replay will then fail CI until the code or the clause is fixed, which is the point.
- Two runs in a row should differ only in the header. If a block moves between runs, a volatile key is
  unmasked — add it to `VOLATILE_KEYS` in `adapter/ios/ext-safe/src/rig/kotlin/…/KeychainTape.kt`.
- `failed: no contract '<name>' is registered here` means this build has no such contract — check the build carries
  `-Psnapsync.rig=true`; a refusal (exit `3`) names the precondition that failed.
- **`BackgroundScheduler`** records the same way (`$R --timeout 0 contract run --name BackgroundScheduler > test/contracts/recordings/BackgroundScheduler@IOS_DEVICE_APP.rec`),
  with **Background App Refresh ON**: with it off, iOS accepts every request and keeps none (measured, SE2 / 26.6.2),
  so every clause expecting a pending wake would fail — the run is refused and says so. Its delivering clauses take over
  the app's own launch registration for the clause (a rig build's root wake is tappable — `rootWake`) and ask iOS to launch and
  expire the task through `BGTaskScheduler`'s debugging calls, which work from inside the app (measured, SE2 / 26.6.2,
  2026-10-13); the launch and the expiry are recorded as event lines. It runs against the production heartbeat
  identifiers (`BGTaskScheduler` accepts only identifiers the plist lists), so ⚠️ **the run leaves the rig build's
  heartbeat CANCELLED** — the app's next trigger (a foreground, a completed cycle) re-arms it. Its volatile key
  (`begin`, the absolute earliest-begin date) is masked in `adapter/ios/app-only/src/rig/kotlin/…/SchedulerContracts.kt`.
- **`UploadExtensionRegistry`** records once per photo grant, into a file named for it. With **full** access:
  `$R --timeout 0 contract run --name UploadExtensionRegistry > …/UploadExtensionRegistry@IOS_DEVICE_APP.GRANTED.rec`; then switch the
  app to **Limited** access in Settings, relaunch, and record `…LIMITED.rec`; then switch back. The body's
  `# file:` header names the file each run belongs in. The full-grant run disables and re-enables the extension
  (wiping in-flight upload jobs), so it is refused while the device is a member of an event — reset first
  (`rig dev-controls reset`), deliberately.
  On the **XS** (below iOS 26.1) the same verb records `…UploadExtensionRegistry@IOS_DEVICE_APP.BELOW_IOS_26_1.rec`
  whatever the grant: there is no mechanism, so it asks iOS nothing but whether the OS carries it — which every
  grant's recording now holds as its first call too.
- **`NetworkMonitor`** records once per network condition, which YOU state — the run never reads it from the adapter
  under test. Wi-Fi joined: `$R --timeout 0 contract run --name NetworkMonitor --params '{"network":"online"}' > …/NetworkMonitor@IOS_DEVICE_APP.ONLINE.rec`;
  then airplane mode on **and Wi-Fi off** (iOS keeps Wi-Fi on in airplane mode once you have re-enabled it there):
  `… --params '{"network":"offline"}' > …/NetworkMonitor@IOS_DEVICE_APP.OFFLINE.rec`; then back online. **Blocked** needs mobile
  data, so the XS (the SE2 has no SIM): Wi-Fi off, mobile data on, Settings → Mobile Data → SnapSync off:
  `… --params '{"network":"blocked"}' > …/NetworkMonitor@IOS_DEVICE_APP.BLOCKED.rec`; then switch both back. The control channel rides
  USB, so it keeps answering offline. A `# live …: Failed` line means the phone was not in the stated condition — do
  not commit that run.

- **`AppSettings`**: start the run (background), and when Settings shows SnapSync's page, tap back into the app — the run
  records iOS's answer to the `openURL` and the app leaving the front, and ends once the app is in front again.
- **Runs that wait for the phone to leave your hands.** Each holds background time and waits, reading the app's own
  state, for the person to put the phone there — so START the run first (in the background, `--timeout 0`), confirm
  `→ contract.run` in `host logs`, and only then ask for the step. Every run also keeps its recording in the
  app's `Documents/contracts/<file>.rec`: when the connection drops (it does on a lock), pull it with `apps pull`.
  - **`ProcessInfo`** (`…LOCKED.rec`): lock the phone. Needs a passcode with **Require Passcode: Immediately** — with a
    delay iOS keeps the data readable past the ~30 s of background time and the run never sees it sealed (measured).
  - **`BackgroundTime`**: go to the home screen; iOS's expiry comes ~30 s later and is recorded as an event line.
  - **`TransferSession` (device app) + `SharePresenter`** — the relaunch, two steps: `--params '{"step":"arm"}'` prepares a transfer to
    `https://snapsync.stho.net/contract-relaunch` (a 404 path of our own domain — a transfer whose connection fails is
    retried and never relaunches anything, measured) and EXITS the app; iOS relaunches it in the background (35 s–2 min)
    and that process records both. Pull `TransferSession@IOS_DEVICE_APP.rec` and `SharePresenter@IOS_DEVICE_APP.rec` from
    `Documents/contracts/` (the relaunched app is suspended, so `{"step":"collect"}` answers only once it is opened).
  - **`Download` (device app)** — the same two steps for the download session: `{"step":"arm"}` prepares a fetch of
    `https://snapsync.stho.net/contract-relaunch-download` in a session of its own and EXITS the app; pull
    `Download@IOS_DEVICE_APP.rec` from `Documents/contracts/` once iOS has relaunched it (~30 s).
  - **`ProcessMetrics`**: `{"step":"arm"}` subscribes and waits, across launches, for MetricKit's next delivery (a
    queued report arrives within seconds; otherwise about a day); `{"step":"collect"}` or pull the kept file.
- ⚠️ **The upload extension does not run below ~20 % battery**, even plugged in: `dasd` answers its runner
  `Battery Level Policy … Decision: MNP` (measured 2026-10-08 at 19–20 %), so the extension run below times out with no
  sign on the phone. Charge first; read `syslog live` for `assetresourceuploadextensionrunner` to tell.

### Recording INSIDE the upload extension — `--host IOS_DEVICE_PHOTOKIT_EXT`

The upload-job contract (`OsUploadQueue`) records in the upload extension, the process production calls
PhotoKit's job API from. The rig cannot reach that process, so the app requests the run through the App Group and
re-registers the extension, which makes the OS invoke it; the extension runs the contract instead of its upload
cycle and writes the recording back, and the verb answers it:

```bash
$R --timeout 0 contract run --name OsUploadQueue --host IOS_DEVICE_PHOTOKIT_EXT \
  > test/contracts/recordings/OsUploadQueue@IOS_DEVICE_PHOTOKIT_EXT.rec
```

- **The build must be baked to the loopback upload base** — `snapsync.deployment=local` with
  `deployments/local.json`'s domain at `127.0.0.1:18099` (the rig's port): the jobs upload to the rig's own
  receiver, which answers each fixture route (`TransferFixture`) with the status in its path. Any other base is
  refused. Revert `deployments/local.json` before committing.
- Preconditions: full photo grant, no membership (the refusal names the fix), and at least one photo in the library (a
  usable resource is the newest photo; the run seeds none).
- It records every clause of the states one call can hold — the presented ones, prepared across calls, a transfer held
  until the call returns, and the queue at its cap (the run creates `PHAssetResourceUploadJob.jobLimit` jobs first: 200 on
  the SE2, 2026-10-13) — one OS invocation each, about twenty minutes in all.
- A failure `timeout: …` means no result within 90 s. It says which half failed: the OS never invoked the extension — it
  **backs off 6–11 min after a call it killed**, and ignores triggers meanwhile — or the run was taken and killed
  at the ~60 s budget. Wait out a backoff; do not hammer it.
- The run leaves the extension registered under the loopback base; a normal build's join re-registers it.
- It records a creation (`IDLE`), the limit (`AT_CAP`: jobs to accepting routes until PhotoKit refuses one, bounded
  at 500 — the count is logged, and is the one measurement of PhotoKit's in-flight limit) and the presented states.
  Those jobs upload after the call returns and are left presented; the next real upload cycle acknowledges them as
  routes no row maps to.

## `contract` on the simulator app — the PhotoKit contracts, live

On a **simulator** the same verb runs the photo-library contracts against real PhotoKit under a real full
grant. The app bundle is the only simulator process `applesimutils` can grant photo access to. Nothing is
recorded: the answer is the outcome table, and the `journeys (ios)` CI job runs exactly what `contract list`
names, on every push. `scripts/sim-contracts` is that job, and running it on a Mac session reproduces it.

```bash
R="test/cli/build/install/rig/bin/rig --url http://127.0.0.1:$PORT"
$R contract list                          # CandidateSource, UploadDiscovery, … (this host's)
$R contract run --name AlbumManager       # "# host: IOS_SIM_APP" then one line per clause
```

- **A refusal (exit `3`)** means the precondition failed and **no clause ran**: the process is not the
  simulator app, or it does not hold `GRANTED`. Grant with `applesimutils … --setPermissions "photos=YES"`
  **before** launch; `simctl privacy grant photos` writes a TCC row PhotoKit never consults.
- The contracts **seed photos and never delete them** (a delete raises a confirmation someone must tap).
  Each clause owns a one-day capture window in 1980–1999, so repeat runs only add assets. Use a fresh
  simulator when the library's size matters.
- `SecureStore` and `BackgroundScheduler` are **not** in the simulator's list: they record only on an
  entitled device, and asking for either here is refused.
- **The transfer contracts** (`TransferSession`, `Download`) run both `URLSession` transports
  against a loopback peer, `scripts/transfer-fixture.py`, whose address the run takes as its `fixture` parameter:
  `python3 scripts/transfer-fixture.py --port 8123 --log /tmp/fx.log &` then
  `$R contract run --name Download --params '{"fixture":"http://127.0.0.1:8123"}'`. Without the
  parameter the run is **refused whole**. The simulator target binds a DEFAULT session, so these
  evidence everything but the background session's lifecycle (see `TransferSessions.kt`).

## `extension-host` — the OS-driven upload tier's own root

The channel reaches **two** composition roots, and the service says which. `os` delivers to `SnapSyncRoot` —
everything the Swift app shell forwards. `extension-host` invokes `UploadExtensionRoot`: the upload extension's,
directly rather than waiting for the OS to schedule an appex.

```
$R extension-host process      run one upload cycle; ANSWERS the tri-state result the OS reads (completed|processing|failed)
$R extension-host terminate    the OS killing a cycle
```

`process` **answers**: the OS invokes `process()` synchronously and acts on its return, so the rig receives that
value rather than inferring it from a poll — the same reasoning that makes an awaited delivery receive its completion
handler.

It runs on **its own serial thread, never main**. The extension process has no main lane, and running its
`runBlocking` on the app's UI thread would freeze the app for the whole cycle.

**Not gated on any mechanism.** Both uploaders write the one App-Group ledger by design, so this cycle is
just another cycle over it; the extension root's own admission decides what it may do — under anything but a
full grant it withholds (records and acknowledges, creates nothing), exactly as when the OS invokes it.

**On a device** this drives the **real** OS job queue — forcing a cycle on demand instead of waiting for
the OS to schedule one. **On a simulator** the queue is substituted and you play the OS through the typed
`upload-jobs` service: `upload-jobs process --finished …` hands the finished job sets in and answers the created jobs,
`upload-jobs perform` moves their bytes. Load `ios-simulator` for that half — the shape, the levers and the two host
divergences are documented there. `extension-host process` takes no job sets: they are `upload-jobs process`'s.

## Driving an event end to end

Everything below assumes a rig build installed and launched (`snapsync-device`), the phone's forward up, and `B`
defined in the same call (see "Building and connecting").

```
# JOIN — the warm universal-link path, same decode -> gate -> join a scanned QR takes
$R --url $B os continue-link --url "https://snapsync.stho.net/join#v=3&d=<payload>"   # R: `rig`, below

# CREATE — exactly as a user creates one: the form's taps, then Create, then the gate it opens
$R run - <<'EOF'
screen tap --intent '{"type":"CreateName","name":"Trip"}'
screen tap --intent '{"type":"RangeSweep","anchor":"2026-08-23","day":"2026-08-30"}'
screen tap --intent '{"type":"ConfirmCreate"}'
screen tap --intent '{"type":"KeepOpenFor","days":7}'
EOF
$R --json host state | jq '.ui.layer'      # wait for JoiningEvent(eventId, Detailed)
$R run - <<'EOF'
screen tap --intent '{"type":"ShareOn","on":true}'
screen tap --intent '{"type":"ReceiveOn","on":false}'
screen tap --intent '{"type":"SaveToAlbum","on":false}'
screen tap --intent '{"type":"ConfirmJoin"}'
EOF

# MINT ONLY — create, read the id, then abandon the gate
$R screen tap --intent '{"type":"CancelJoin"}'

# LEAVE
$R screen tap --intent '{"type":"LeaveEvent"}'

# ANY OTHER TAP: its UiIntent (ui/state's UiIntent.kt; each case's serial name is its type), e.g.
# {"type":"RenameEvent","eventId":"…","name":"…"}, {"type":"ConfirmSwitch"}, {"type":"RetryLoad"}, {"type":"RetryJoin"},
# {"type":"MobileData","on":false}  (the device's choice, as the menu's switch flips it; UiState.mobileData reads it),
# {"type":"SendDiagnostics","note":"…","screen":"rig"}  (a rig build carries no DSN: it SAVES Documents/diagnostic-report.json).
# A tap that hands the person to the platform's own UI — {"type":"ShareInvite"}, {"type":"OpenSettings"},
# {"type":"ChoosePhotos"}, {"type":"OpenLink","link":"PRIVACY_POLICY"}, {"type":"RequestPermission"} — is a real tap on a
# device: the share sheet, Settings or the picker appear, and only a finger brings the person back. The JVM host's
# platform UI is a mock, which records the hand-off (`system-ui`'s reads, and the world's events).
```

In Kotlin, `:test:control`'s `Person` names the forms (`createEvent`, `confirmJoin(Choices(…))`, `setRange`,
`reconfigure`, `rename`) as these taps: `client.person().createEvent("Trip", "2026-08-23T00:00", "2026-08-30T00:00")`.

⚠️ **`create` is non-idempotent** — every call mints a **new** backend event. There is no launch variable
to forget to unset any more, but there is also nothing stopping a loop from minting a hundred.

⚠️ **Never join an event you did not create.** A `direction=download` join imports that event's photos into
this device's library and registers this device on someone's real membership.

## The photo library

```
$R gallery read                                       # raw census: total, screenshots, recordings
$R gallery read --cutoff 2026-07-01T00:00:00Z         # + per-asset policy verdict
$R gallery read --cutoff … --resources                # + each asset's resources

$R --timeout 0 gallery seed --n 4000 --kind bulk      # walk-cost: tiny 2001-dated assets
$R gallery seed --n 20 --kind policy                  # policy probe: +1h, straddling 3 MP
```

`--kind policy` seeds assets dated an hour ahead — past any cutoff an event created today can carry — and
**alternating** above/below the 3 MP image floor, so the resolution rule is the only thing that can separate
them. Expect exactly the even-indexed half admitted.

The read reports **which rule refused** each excluded asset, in the rule's own vocabulary, so
`refusedBy: "MinImageArea(3000000)"` sits in the same row as the `pixelArea` that triggered it.

⚠️ **`--resources` is ~110 ms per asset** (one PhotoKit round-trip each) — about 17 minutes across a
9525-asset library. The response reports what it paid. Ask for it when you need a filename, which IS the
upload/ledger key; otherwise don't.

⚠️ Under a **`LIMITED`** grant, `total` is the hand-picked **selection**, not the library. The grant is in
the response for that reason. A fetch under `.limited` can also surface iOS's own limited-access alert.

## Emptying the library

```
ch bg $R --timeout 0 gallery wipe --scope all        # all|assets|albums; NO bound
ch bg $R --timeout 0 gallery wipe --scope assets --limit 1   # one asset — the smallest thing that prompts
      $R gallery wipe --scope assets --limit 0               # selects nothing — the probe, below
```

`--limit`/`--offset` are optional; omit them for the whole match. A scope `rig` cannot read is a usage error naming
what is accepted (`all|assets|albums`) before anything reaches the phone — a mistyped bound must never fall back to
"no window" and delete the whole library.

🏃 **Run it under `ch bg`, backgrounded, and end your turn.** This is the one call in this channel where
that is right, and it is not the usual `ch bg` rule (CLAUDE.md: don't wrap work the workspace is genuinely
doing). The wipe is not waiting on a machine — it is waiting for **a person to walk to the phone and tap**.
That is the definition of idle: the workspace should go idle so CodeHydra *notifies* the operator they are
needed, instead of reading busy while nothing happens and nobody is told. Foreground is not an option
anyway: **the request has no deadline** and the harness clamps a foreground Bash call at 10 min, so waiting
inline gets you `Exit code 143` and no result.

⏳ **Do not bound it either — `--timeout 0`** (except on the `--limit 0` probe, which answers in ~50 ms). The
wait is unbounded on purpose: a bound never distinguished "not tapped yet" from "never presented" — it only
picked a moment to stop listening, and it expired twice on an alert that was on screen and correct because
nobody was standing at the phone. Let it wait; the operator's tap ends it.

🚨 **IRREVERSIBLE, and NOT headless.** iOS raises its own confirmation and **someone must tap the device**.
Measured (SE2, iOS 26.6): an `all` wipe raises **one confirmation per kind** — batching does not collapse
them. The request **blocks** until you answer, then reports `committed` and the matched counts. A tapped
Cancel comes back as `committed:false` with `errorCode:3072`, which is the operator answering, not a bug.

`limit`/`offset` carve a sub-range out of the matched assets — the only way to ask how many a device will
delete in one transaction, and the way to bisect if one asset ever poisons a batch. Omitted, the whole
match goes in one transaction, exactly as before.

⚠️ **If the confirmation never appears, RESTART THE PHONE.** Measured 2026-08-24: this hung through every
combination of scope, count, lane, launch style and ordering, and a `diagnostics restart` fixed it with
nothing else changed. It is transient state in the OS photo-library daemon (Apple threads 840122 / 806349 /
732820 — it survives app kills and reinstalls, and clears on a restart), not the command. Before rebooting,
spend one call telling the two failures apart: **wipe with a window that selects nothing** (`--limit 0`, or
`--scope albums` with no albums). That submits an empty change block, needs no confirmation, and answers in
~50 ms. If THAT hangs too, the whole change pipeline is down and a restart is not the answer.

⚠️ **If YOU give up, the alert does not.** Killing the request, timing out, even killing the app leaves the
confirmation on screen — a later tap still deletes, with nothing listening. Only a tap or `Don't Allow` ends
it, and `gallery read` is the only truth about what happened. (There is no `answered` field any more:
the response arrives when the platform answers, so `committed` plus `errorCode` say everything. A tapped
Cancel is `committed:false` + `errorCode:3072`.)

The scope is typed (`WipeScope`): an unrecognized one never leaves your shell — the only command here where that
matters this much, because it is the only one that cannot be undone.

## Resetting durable state

```
$R dev-controls reset
```

Voids the ledger, the discovery cursor, the membership config (**locally** — no backend is notified), and
prunable download rows. **Keeps** every row carrying an import handle, so downloaded photos are not
re-uploaded. Answers `{"reset":true}` — the ledger counts it once returned went with the ledger's removal from the
protocol; what cleared shows on the screen and in the uploads that follow.

⚠️ **Order matters and nothing enforces it any more.** Crossing backends, reset **before** leaving: after a
reset the device is unjoined, so a leave is a no-op rather than a `DELETE` aimed at the backend you are
leaving behind. There is no coordinator imposing that order now that each command is its own request.

## Some systems mocked in the real app — launch-time adapters

A rig build can run with **some systems mocked and the rest real**, chosen at launch (`docs/testing.md`, "Launch-time
adapters"). The systems: `backend library files databases preferences keychain integrity crash-reporter process-info network
device-conditions clock wake background-time extension-registry upload-queue upload-session downloads lifecycle links push screen
system-ui`. The adapter choice is the file `rig/adapters` in the App Group: one `system=mock|real` per line, a missing
one real.

```bash
# REAL photos, a MOCKED backend (the shared zone is never touched) — the smallest coherent such choice:
$R adapters choose --choice $'backend=mock\nupload-queue=mock\nupload-session=mock\ndownloads=mock\npush=mock\nintegrity=mock\nextension-registry=mock\n'
# → {"written":…,"next":…}  — the app EXITS. Launch it again (simulator: `xcrun simctl launch`;
#   phone: the `snapsync-device` launch step). Every start of any process reads the choice from then on.
curl -s $B/health                        # adapters=mocked: backend,…   (or adapters=REFUSED …)
$R adapters current
$R adapters clear                         # back to all real; exits
```

- **Coherence is checked** (refused with every broken rule): a mocked backend needs mocked transfers, push service and
  Secure Enclave; a mocked library needs mocked uploads; a REAL extension registration keeps every system the
  extension writes real — so mock `extension-registry` with anything shared, and drive the extension with
  `extension-host process`. A choice that does not parse fails, naming the problem.
- **Refused while joined**: leave or `rig dev-controls reset` first, so no membership is carried into another set
  of systems.
- **An invalid adapters file composes NOTHING** (a script wrote a bad one, or a state file is unreadable): `/health`
  says `adapters=REFUSED …`, everything that would reach the app is refused naming why, the `adapters` service (and
  `host logs`, `host disk`) still work. Never a fall-back to real.
- **Mocked means operator-driven**: nothing a mock plays happens on its own. The levers of the JVM host section below
  are honoured here for exactly the systems the choice mocks (`GET /device` lists which); `os` deliveries go through the
  mock where the system is mocked. With `upload-session` mocked the app's own uploader still runs, and its transfers
  wait for you: `rig --url $B upload-session session` lists them, `upload-session complete` lands them (the typed
  transfer services, below). The extension's cycle runs with `extension-host process`, its jobs land with
  `upload-queue complete-job`. Both uploaders take the same photos,
  so to drive one alone switch the other off (`dev-controls pin`, below — the JVM host honours it too).
- Mock state persists across relaunches (`rig/state/`, `rig/databases/` in the App Group), written by the app.
- Measured 2026-09-28 on the SE2 (iOS 26.6.2): the choice above over the phone's REAL library — 55 resources (Live
  Photo motion included) through the mocked queue into the mocked backend, all still there after a SIGKILL and
  relaunch; `adapters/clear` returned the app to all real. A choice that mocks the extension registration never
  touches the real one.
- On the phone only the verb can write the choice (the App Group is not USB-reachable); on a simulator a script may
  write it before a launch into the `folder` that `adapters current` names (`ios-simulator`).

## Triggers return what the PLATFORM returns

The app root's entries are the **`os` typed service** (`OsLevers`; `rig os <method>`, below), which every host serves
— each delivery through the system's mock where the host mocks it, the platform's adapter where it is real:

```bash
$R --url $B os foreground                 # also: background, push-token --token <hex>, push-token-failure --description …
$R --url $B os continue-link --url <link>
$R --url $B os silent-push --event <id> --await-release            # → {"heldMs": …, "atRelease": {"stagedFiles": …, "uploadSessionHandbacks": …}}
$R --url $B os background-task --identifier app.snapsync.upload.heartbeat --await-release
$R --url $B os background-transfers --identifier app.snapsync.upload.session --await-release
```

⚠️ **`foreground` answers at once and does NOT wait** — and it is the delivery you will reach for most.
The platform hands it no completion handler, so neither does the rig. **Poll `host state`.**

The three entries the OS *does* hand a completion handler — `silent-push`, `background-task`,
`background-transfers` — answer once delivered; with `--await-release` they block until the app releases the handler
and answer `heldMs` and `atRelease` — the files staged and the upload session's handbacks as read on the releasing
thread, each `null` where its system is real (on the JVM host, a test waits on the world's `CompletionReleased`
instead). A push and a transfer
wake release after their **own work** (the push's union read and enqueue; the session's staging and drain report) and
run the rest — import, top-up, walk — as the tail afterwards, so `heldMs` does not include the tail; poll
`host state` for its effects. The heartbeat releases after its tail. The last two take the identifier the OS would
deliver: `app.snapsync.upload.heartbeat` (the busy heartbeat) or `app.snapsync.heartbeat.idle` (the idle heartbeat, an
app refresh) — the same wake to the core; the download backstop is deleted — and `app.snapsync.upload.session` for the
app uploader's session (any other channel routes to the downloads). An unknown task identifier is completed at once
and logged.

🧭 **The rig classifies nothing.** A release carries no outcome, so "released after the own work" vs
"released on the operating system's expiry" is **not** derivable from `heldMs`. The authoritative answer is
the runtime's expiry line `operating system expiry: cancelling trigger=…`, which it writes on the expiry path
and no other — read it via `host logs` after the call's `[rig] → os.<method>` marker. Every
call writes that marker, so it doubles as the log cursor.

Excluded members answer with **the reason they are excluded**, not a bare 404 — `onLaunch` re-registers
`NSNotificationCenter` observers documented as never removed, so re-invoking it corrupts the process under
test (reset is a relaunch), and `onRequestPermission` raises a system alert only a finger can answer.

⚠️ **A tap answers once it is handed over, exactly as a screen's does.** It starts work and does not wait for it,
because the UI itself has no completion signal to expose. Poll `host state`.

## Ordering trap

`onForeground` fires **before** the membership config resolves — measured at 17:53:25.23 against
17:53:27.45. A caller that triggers and then asserts reads a membership-less state and concludes
nothing happened. Poll `host state`'s `ready.configResolved` instead of sleeping.

## If it does not answer

`connection refused` is ambiguous between "app not running", "port forward not set up" and "the rig
failed to bind". The rig logs a bind failure at `Error` naming the address and port, and that log is
pullable **without** the rig (`apps pull … Documents/debug.log`) — read it before guessing. The usual
cause is a previous instance still alive holding the port; SIGKILL it (the global `device` skill's restart recipe, in its `ios.md`).

## Switching an uploader off

Both uploaders run where both exist (the app's on every OS, the extension beside it from iOS 26.1 under a full
grant). To exercise one alone there is a development switch per uploader, with no production writer:

```
$R dev-controls pin --app true|false --extension true|false
$R dev-controls unpin                     # both run, as a shipped build
$R dev-controls controls                  # the switch as it stands
```

`--app false` makes the app's uploader withhold (it records completions, creates nothing). `--extension false` makes
the extension unregistrable, and the reconcile the pin triggers **deregisters it now** — the extension cannot
read app memory, so off must be a deregistration (which wipes its in-flight OS jobs: the test's intent).

To drive the app's uploader alone on a ≥26.1 device: `--app true --extension false`, then fire
`rig os background-task --identifier app.snapsync.upload.heartbeat` (the app uploader's heartbeat). The switch dies with the process.

Note `host state`'s `build.uploadTier` is a **build fact** — which uploaders this OS carries
(`app` or `app+extension`) — and does not move with the switch.

## The JVM host — the same protocol with no device

`:test:rig` also has a `jvm()` target: `JvmRigHost` serves the **unchanged** server and routes over the app
the JVM root `:app:jvm` composes — the same `snapSyncHost` the iOS root calls — over the device as mocks
(`JvmMocks`). No phone, no lock, no build on a Mac:

```bash
./gradlew :test:rig:runJvmHost -Psnapsync.rigBackend=mock   # or deno: the REAL api/ via :test:edge
# prints one line once bound:  RIG-JVM READY <port>
curl -s localhost:<port>/device            # honoured + refused (reasons) for THIS host
```

- `mock` (the default) is the in-memory backend mock; `deno` starts the real `api/` (`serve.ts --ephemeral`,
  loopback-only). On `deno`, a lever only the backend mock's operator can pull (`backend.offline`, …) is refused
  "unavailable on this backend".
- Same `os` shapes and typed services as the app. `os` drives the mocks' deliveries (the OS played);
  `os continue-link` reaches the `Links` port's handler, `extension-host process` invokes the extension's entry port,
  whose cycle creates jobs on the upload-queue mock.
- Every lever and read below is a mock's operator face — what the OS, the photo library or the backend does or
  recorded, never the composed app. A lever answers at once; what the app makes of it is **observed, not awaited**
  (poll `host state`, the backend, the library, the OS's reads). The download reconcile and the status read are
  the foreground's: `os foreground`. `gallery read` is the library's answer under the person's grant.
- **The OS is played, its expiry and its record included** (the app host only for what its adapter choice mocks —
  `played-os` needs `background-time` mocked): see "The operating system's typed services" below.
  - The rest of what the device holds is a read on the service that owns it: `screen shown` (null until a screen was
    shown anything), `download-session downloads` (`up`, `started`, `inFlight`), `upload-session session` (its
    `handbacks`), `databases opened`, `files staged-files`, `files app-storage-bytes` (the bytes the app's files take,
    its diagnostics — logs, memory trail, saved report — left out: what the storage page shows a person). A delivery
    awaited with `--await-release` answers `atRelease` — the files staged and the upload session's handbacks as read
    at the release.
  - `host relaunch [--scene false]` (JVM host only) — process death and a cold launch; `--scene false` a cold
    background launch: no screen is built. Read only the OS's reads until an `os foreground`: `host state` and a tap
    read the screen and would assemble it.
  - `host reinstall` (JVM host only) — delete the app and launch it again: files, databases and user defaults gone;
    Keychain, photo library and backend kept.
- The **operator levers** (on the app host, only for the systems its adapter choice mocks): the transfers' — the
  upload-job queue, the app's upload session and the download session — are typed services (below, "The transfers'
  typed services"), and so are the photo library's and the device's stores' (below);
  `host invite [--event …]` (JVM host only: the event's whole invite, key included, as its creating device shares it).
- The **integration surface's** levers and reads (the same rule on the app host):
  - the backend, the photo library, the crash reporter, the system UI, the network, the clock, the device's
    conditions, the process information, the build and its development controls, and the device's stores:
    **typed services**, below.
- The app's private files are no fact a test reads: what the outside sees of them is `appStorageBytes`.
- `contract` is refused on this host (naming `JVM`): JVM contract bindings run under Gradle.
- Typed client for tests: `:test:control`'s `RigClient` and
  `client.levers(<Service>)` for every typed service, over kRPC (a refusal throws `LeverRefused`); `client.person()`
  taps the forms.
- Stop it by killing the JavaExec process. Don't `pkill -f runJvmHost` from a shell whose own command line
  contains that string: it matches itself.

### The control channel's typed services

A person's taps, the host's own verbs (`screen`, `host`, `gallery`, `adapters`, `contract`, `upload-jobs` — each
classified by the host method by method) and the backend's levers and reads are **typed services** (`docs/testing.md`,
"Typed services"), served over **kRPC** on `ws://<host>:<port>/rpc` — not as `/device` verbs, so **curl cannot reach
them**. From a shell, drive them with **`rig`** (`:test:cli`), whose commands are generated from the interfaces in
`:test:levers`:

```bash
./gradlew :test:cli:installDist -q        # once per checkout, and after a :test:levers change
R=test/cli/build/install/rig/bin/rig      # define it in the SAME call that uses it: shell state does not persist
$R --help                                 # the services; `$R backend --help`, `$R backend fail --help` for more

B=http://127.0.0.1:<port>                 # the JVM host's printed port, or the phone's forward (18101 SE2, 18102 XS)
$R --url $B backend offline --on          # → ok
$R --url $B backend fail --operation createEvent --fault maintenance --times 2
$R --url $B public-backend foreign-device --device 6f1c1e9e-4a7b-4c55-9d3e-1b2c3d4e5f60 --assets A1,A2
$R --url $B backend switches              # a read prints its JSON; --json prints it on one line
$R --url $B rig advertise                 # what this host honours and refuses, typed services included
$R --url $B run steps.txt                 # one command per line ('#' comments; '-' reads stdin), one connection
```

- **`--url` is required** — there is no default host and no environment variable.
- **`--timeout <seconds>`** bounds one call (default 60; `0`: as long as it takes — a contract run, a large seed, a
  wipe the phone's owner must confirm).
- **Exit codes:** `0` answered · `1` failed (the host's message as it arrived — from an iOS host, kRPC's stand-in
  exception's) · `2` usage (an unknown service, method or flag, or a value its type cannot take; the message names
  what there is) · `3` refused by this host (`refused: <service>.<method> is refused here: <reason>`).
- **Flags:** a method or flag by its command spelling (`fail-listing`) or its Kotlin name (`failListing`); an enum
  value in any case (`maintenance`, `server-error`, `MOTION_PHOTO`); a list by a repeated flag or comma-separated
  (`--assets A1,A2`); a bare `Boolean` flag is `true` (`--on`; `--on false` to switch it off); a structure as its
  JSON (`--intent '{"type":"LeaveEvent"}'`; a list of structures one flag each, or as a JSON array).
- **A foreign `device` must be a canonical UUID.** Each call starts a JVM: about 0.6 s; a `run` file pays it once.

From Kotlin, `:test:control`'s typed client calls the same interfaces:

```kotlin
RigClient("http://127.0.0.1:$PORT").use { client ->
    client.levers(BackendLevers).offline(true)
    client.levers(PublicBackendLevers).foreignDevice("6f1c1e9e-4a7b-4c55-9d3e-1b2c3d4e5f60", listOf("A1"))
    client.levers(RigLevers).advertise()
}
```

What a host serves is in its advertisement — `GET /device` lists each typed method as `<service>.<method>`
(`backend.offline`, `public-backend.foreignDeviceLeave`), honoured or refused with the reason. A refused service
throws `LeverRefused`; an omitted `event` is the joined one, an omitted `device` this one, any other omitted argument
`null` (a `Boolean` is never implied: pass `on = true`, or `--on` from `rig`).

- `backend` (`BackendLevers`) — the backend mock's operator; refused over the real `api/` ("unavailable on this
  backend"): levers `offline(on)`, `failListing(on)`, `failUnion(on)`, `minAppVersion(minimum?)`
  (none: the gate is off), `nextEventId(id)` (the next create mints this id, once), `hold(call)` / `release(call)`
  (`BackendCall.EVENT|CREATE|JOIN|LEAVE`: the call waits unanswered until released — the only way to catch a screen
  the app shows while it waits), `fail(operation, fault, times?)` (the next `times` — once by default — requests to that
  `api/openapi.yaml` operationId answer `BackendFault.SERVER_ERROR|MAINTENANCE|UNREACHABLE|MALFORMED|ENDED_FOR_GOOD|CLOSED`;
  an operation the API does not declare throws), `recover(operation?)` (lifts what `fail` set — for every operation when
  omitted: the service works again), `sweep(event?)`, `complete(event?)`, `wipeBytes(device?)`,
  `refuseNextCredential()`, `refuseAttestation(reason?, detail?)` (no reason: the phone is genuine again); reads
  `switches()` (every lever's state, the held calls), `membership(event?, device?)` (departed, publishes, the manifest),
  `eventState(event?)` (union and detail reads, union fetches and position, finished, completed),
  `deviceConfig(device?)` (token, env, writes), `pushes()`.
- `public-backend` (`PublicBackendLevers`) — the backend's public surface, so honoured over the real `api/` too:
  `union(event?)`, `event(event?)` (registered, name, closedAt), `objects(device?, event?)`,
  `foreignDevice(device, assets, event?, kind?, filename?, final?, uploading?)` (a fellow member with complete photos —
  `PhotoKind.MOTION_PHOTO`: each a real Google motion photo; answers the event), `foreignDeviceLeave(device, event?)`,
  `foreignDeviceReceived(device, event?)`.

Gone with the move: every `backend/…` and `foreign-device[/…]` curl verb; `backend/deposit` (an own photo's bytes land
unacknowledged with `upload-queue complete-job` and no extension invoke).

### The transfers' typed services

The operating system's background transfers are typed services too, each honoured where its system is mocked (always
on the JVM host; on the app host for what its adapter choice mocks) and refused naming the real system elsewhere.
Nothing lands until you land it:

- `upload-queue` (`UploadQueueLevers`) — the OS's upload-job queue, which the extension creates jobs on, each named
  `<assetId>/<role>` (what its destination names, the one fact the OS keeps): levers `jobLimit(limit?)` (the in-flight
  cap; none: no cap), `failCreate(on)`, `completeJob(job?)` (the OS performs a job's request — every live one when
  omitted — so its bytes cross to the backend's byte route), `failJob(job, error)` (`network`, `http:503`, …); read
  `queue()` (live jobs, every created one, the cap).
- `upload-session` (`UploadSessionLevers`) — the app uploader's background session: `complete(key?)` (lands a
  transfer — every live one when omitted — each end reported to the app); read `session()` (live keys, every created
  one, hand-backs).
- `download-session` (`DownloadSessionLevers`) — the background download session: `finish(description?, status?,
  expected?, received?, base64Bytes?)` (every in-flight transfer when omitted, nothing while this launch has not
  brought the session up; no status/length is a healthy `200`; `base64Bytes` is what each brought — a real motion
  photo, say — and a fixed 16×16 JPEG otherwise), `reportEventsDrained()`; read `downloads()` (up, started, in flight).

```bash
$R --url $B upload-queue job-limit --limit 2
$R --url $B upload-queue complete-job                   # every live job lands
$R --url $B download-session finish --status 502 --received 137
$R --url $B download-session finish --base64-bytes "$(base64 -w0 motion.jpg)"
$R --url $B download-session report-events-drained
```

### The photo library's typed service

- `library` (`LibraryLevers`) — the photo library mock's operator; refused where the library is real (a real
  library has no switch for how it answers, and its photos are the member's). Levers: `add(id, kind?, date?)` (a photo
  the person takes — `LibraryPhotoKind.PHOTO|LOW_RES|SCREENSHOT|SCREEN_RECORDING|HD_VIDEO|LIVE_PHOTO|GIF`, a camera
  photo by default, dated 2026-06-01T10:00:00Z unless given), `remove(asset)`, `permission(grant)` /
  `requestAnswer(grant)` (`PhotoGrant.NOT_DETERMINED|DENIED|LIMITED|GRANTED` — the grant in Settings, and the answer to
  the one permission dialog), `changeSelection(assets)` (the partial selection as the picker delivers it; fails while
  no selection observer is open), `failNextEnumeration()`, `holdEnumeration(on)` (every walk waits — the status screen
  before anything is counted), `byIdReadable(on)`, `failNextImport(afterCreating?)`, `suspendNextImport(afterCommit?)`
  (parked before the commit lands, or after it — the shape a process death leaves), `resumeImport(succeeded?)`,
  `attemptCap(cap)`, `placeIn(title, asset)` (another app's album — `WhatsApp`), `holdAdds(on)`, `albumKind(kind)`
  (`AlbumKind.COLLECTION|FOLDER`: read as the app starts — relaunch after it), `deleteAlbum(album)`,
  `renameAlbum(album, title)` (a folder album only). Reads: `library()` (photos, grant, selection, observer, pickers
  shown, holds), `albums()` (each album the app made: its photos now, and every photo it asked to add), `imports()`
  (what was imported, the import parked now).

```bash
$R --url $B library add --id A1 --kind screenshot
$R --url $B library change-selection --assets A1,A2
$R --url $B library albums
```

Gone with the move: `selection/change`, `permission`, `gallery/add|remove|fail-next-enumeration|hold-enumeration`,
`import/fail-next|suspend-next|resume|await-parked` (an import parked is the `LibraryImportParked` event, or
`library.imports().parked`), `album/place|hold-adds|kind|delete|contents`. The real library's own verbs stay strings:
`device/gallery/seed`, `device/gallery/wipe`, `device/gallery/add-moving` and the `device/gallery` read.

### The device's surroundings — typed services

What the operating system tells the app about the device, and what the app hands the platform, are typed services too
(`:test:levers`' `SurroundingsLevers.kt` and `BuildLevers.kt`), each refused where its system is real:

- `network` (`NetworkLevers`) — `set(access)` (`NetworkState.ONLINE|RESTRICTED|OFFLINE|BLOCKED`: restricted is mobile
  data, a hotspot or Low Data Mode, blocked the network withheld from this app), read `access()`. A notice follows the
  watch's 5 s grace, only while the app is foregrounded — on the JVM host's mocked time, once `world.advance` has moved
  past it. Pair `OFFLINE` with the backend's `offline(true)` to play a network that is really gone.
- `device-conditions` (`DeviceConditionsLevers`) — `set(fields)`, each by the bug report's key (`power_saving`,
  `background_refresh`, `standby_bucket`, `battery_optimization_exempt`, `battery_percent`, `battery_charging`,
  `thermal`) to a value, `unsupported` or `failed:<reason>`; `hold(on)` leaves every read unanswered; read `reading()`.
  From `rig`, a map or a structure is its JSON: `$R --url $B device-conditions set --fields '{"power_saving":"true"}'`.
- `clock` (`ClockLevers`) — `set(at)` (an ISO instant), read `now()`. **Refused on the JVM host**, whose loop owns the
  clock: there `world.advanceTo(at)` moves it, with every timer due on the way.
- `process-info` (`ProcessInfoLevers`) — `protectedData(available)`, `footprint(footprint?)`, read `info()`.
- `crash-reporter` (`CrashReporterLevers`) — reads `started()`, `sent()` (each dump the reporter's backend received:
  note, state, ledger, both logs, their bytes).
- `system-ui` (`SystemUiLevers`) — read `record()` (what the share sheet was handed, the URLs opened, the Settings page
  opened and in front); `leaveSettings()`, and the refusals `noWindow(on)`, `noSheet(on)`, `unclaimed(urls)`.
- `dev-controls` (`DevControlsLevers`, every host — the build's own) — `pin(app, extension)` / `unpin()` (each uploader
  on or off; the uploads are reconciled with the switch at once), `inviteLinkHints(honoured)` (`false` plays a shipped
  build; relaunch to be sure the new answer is read), `encryptNewEvents(on)` (`false`: the next event this device
  creates is PLAIN — its invite the fragment form; a rig build encrypts by default, its invite carrying `#k=` — unlike a
  shipped one, which creates plain events until encrypted creation is re-enabled (`PENDING_CLEANUPS.md`). The mocked
  download session's fixed JPEG does not open as an encrypted file, so a mocked-download world stages nothing of an
  encrypted event: create a plain one to see downloads land on a device; the JVM host seals them itself), `reset()`,
  read `controls()`.
- `build` (`BuildLevers`) — `declare(version)` (the marketing version the build declares on every request — play an
  old build, or an update), read `declared()`; only where the backend is the mock, which reads it.

Gone with the move: `device/clock/advance`, `device/network`, `device/conditions`, `device/diagnostics/sent`,
`device/app-version`, `device/invite-link-hints`, `device/encrypt-new-events`, and `os-record`'s `systemUi`.

### The device's stores' typed services

Each honoured wherever its system is mocked — always on the JVM host, on the app host where its adapter choice mocks
it — and refused, naming the real system, everywhere else:

- `databases` (`DatabasesLevers`) — `lock(on)` (every database unreadable: a device before its first unlock since
  boot; an open fails, so does every statement on one already open), `fail(database, table, kind, times?)` (the next
  `times` — once by default — statements of `WriteKind.INSERT|UPDATE|DELETE` on that table fail as SQLite fails a write
  whose lock another process holds: thrown, nothing changed, the database still open — the `Databases` contract's
  `A_REFUSED_WRITE_THROWS_AND_CHANGES_NOTHING`; a database not created yet, or a table it does not hold, throws; each
  failure is told on the stream as `DatabaseFaulted`. No read fails: under iOS's write-ahead log a reader is never
  blocked); read `switches()` (locked, the faults not yet spent), read `opened()` (every database any process opened,
  by name, in order).
- `files` (`FilesLevers`) — `appendLog(process, text)` (`LogProcess.APP|EXTENSION`: text a process's device log
  carries, what a diagnostic dump reads back); reads `stagedFiles()` (the download staging directory's files) and
  `appStorageBytes()`.
- `keychain` (`KeychainLevers`) — `forget(item)` (`KeychainItem.EVENT_KEY`: the joined event's key gone, the membership
  kept — a restore onto a new phone).
- `older-install` (`OlderInstallLevers`) — `stagedBacklog(device?, asset?)` (an install upgraded from a build before
  per-asset byte release: a confirmed import whose staged files survive; answers the bytes it left on disk).

```bash
$R --url $B databases fail --database ledger.db --table membership --kind delete
$R --url $B files append-log --process app --text 'a line'
$R --url $B keychain forget --item event-key
```

Gone with the move: `device/databases/locked`, `device/membership/undeletable`, `device/event-key/lose`,
`device/logs/append`, `device/staging/seed-legacy-backlog`, the `device/staging` fact, and `device/os-record`'s
`databasesOpened`, `stagedFiles` and `appStorageBytes` (3g-a).

### The operating system's typed services

The operating system as the app meets it (`docs/testing.md`, "Typed services") — the same `rig` and `client.levers(…)`:

- `os` (`OsLevers`) — the deliveries to the app's entry points ("Triggers return what the PLATFORM returns", above).
  Needs nothing: every host serves it.
- `played-os` (`PlayedOsLevers`) — what only a played OS does; needs `background-time` mocked (a real OS's expiry is
  its own): `expire()` (time is up for every held completion handler and background-time hold — hold a
  delivery open with `--await-release` in a second call and expire it), `expireNext()` (the next handler arrives
  already expired), the read `completions()` (`handed`, `released`, `releasedAgain`, `held`), and `forceQuit()` (the
  member force-quits the app: the process dies, the session's transfers are cancelled and reported at the next
  opening, and every app-launching delivery is refused until `os foreground`; the extension is still invoked. Only a
  host that owns the app's process honours it — the JVM host's).
- `extension-host` (`ExtensionHostLevers`) — `process()` (the OS invokes the extension's `process()`: answers
  `completed|processing|failed`) and `terminate()`. The JVM host and the iOS app host serve it; Android refuses it. The
  simulator's scripted job sets are `upload-jobs process`'s, see `ios-simulator`.
- Each OS system's face, needing its system mocked: `lifecycle` (`everActive()`), `links` (`startInstalled(referrer)`,
  the install referrer only a store delivers, once per installation), `push` (`kind(kind)` — `apns|fcm`, read as the
  app composes, so it takes effect at a relaunch; `refuseTokens(reason?)` — none: tokens again; the read `push()`:
  registrations, kind, refusal), `wake` (the read `wakes()`: heartbeats accepted, and every request pending with its
  cadence and delay), `background-time` (the read `holds()`, each by the name the app began it under, verbatim;
  `expireAll()`), `extension-registry` (the read `registered()`, `null` where the platform has no extension; and
  `carried(on)`, whether the OS carries the extension at all, iOS 26.1 and later, read at launch so it takes effect at the
  next relaunch).

Gone with the move: `device/os-record`'s completions, `heartbeatsScheduled`, `pendingWake`, `backgroundTimeHolds` and
`pushRegistrations` — each is its service's read above — and, with the string protocol (3g-a), `os-record` itself
(`screenShown`, the transfer sessions: `screen shown`, `download-session downloads`, `upload-session session`), the
`/os/app/*` verbs (`os`), `/os/app/onExpiry` (`played-os`) and `/os/photokit-ext/*` (`extension-host`).

### The world's events — waiting without polling (JVM host only)

The JVM host runs the app on **one loop** (`LaneLoop`): every lane of both processes, and the channel's own, is a lane
of it, so the loop knows when the app is **settled** — nothing running or runnable, nothing outside the app in flight
but an operator's hold (`docs/testing.md` §7, "How a test waits"). Every request is a task of that loop: a `POST` or a
typed call is an **act**, announced first; a `GET` runs there unannounced. Two consequences when driving it by hand:

- **Time.** `JvmRigHost.start(time = LoopTime.REAL)` — what `runJvmHost` and the old `rigTest` use — keeps the wall
  clock: timers fire after their real duration and `world.advanceTo(at)` only moves the clock the app reads. Under
  `LoopTime.MOCKED` (what `behaviour { }` uses) nothing fires until `world.advance(millis)` or `world.advanceTo(at)`
  moves the clock, each timer at its own instant, settling between them.
- **Lanes.** `-Dsnapsync.lanes=turns|shuffled:<seed>|parallel` (the integration suite: `-Psnapsync.lanes=…`) picks how
  the lanes take turns. A dispatch onto an app lane from a thread the loop does not own is a **lane violation**: told on
  the stream, and a test fails on it.

The `world` service (`WorldLevers`, kRPC, refused by a device host):

```kotlin
RigClient("http://127.0.0.1:$PORT").use { client ->
    val world = client.levers(WorldLevers)
    world.events().collect { println(it) }   // from the host's start: Acted, UiChanged, BackendReceived, BackendHeld,
                                              // UploadJobCreated, SessionTransferCreated, DownloadStarted, Library…,
                                              // Shared, UrlOpened, SettingsOpened, DumpReceived, DatabaseFaulted,
                                              // the OS's (WakeScheduled, HoldBegun, CompletionReleased, …),
                                              // Settled (its state: appStorageBytes), LaneViolation
    world.advance(5_000)                      // an act: the mocked clock 5 s on
    world.advanceTo("2026-06-02T09:00:00Z")   // an act: the mocked clock to an instant (back: fires nothing)
    world.whyNotSettled()                     // what runs, what is queued, what it waits on outside
}
```

The library's events: a walk, an add or an import parked on a hold (`LibraryEnumerationHeld`, `LibraryAddHeld`,
`LibraryImportParked`), a photo an import created (`LibraryAssetCreated`), an album the app made
(`LibraryAlbumCreated`) and a photo that joined one, once per photo (`LibraryJoinedAlbum`); the access dialog raised
(`LibraryAccessAsked`) and the picker presented under a partial grant (`LibraryPickerShown`).

`tagActs(tag)` / `actsTagged(tag)` mark a caller's acts so it finds where they begin in the stream — the behaviour DSL's
own bookkeeping. A test never reads the stream by hand: it states a `behaviour { given / whenever / then }`
(`:test:control`'s `app.snapsync.control.behaviour`). Phase 14's `rig await` is to wait on the same stream.

