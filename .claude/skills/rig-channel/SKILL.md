---
name: rig-channel
description: >-
  Drive the app running on the connected iPhone, over HTTP — the build-time-only
  control channel (:test:rig, -Psnapsync.rig=true). This is how you join, create,
  leave, reset device state, seed or wipe the photo library, read the selection
  policy, force an OS callback, and read live UiState. Use when the task means
  "join an event on device", "create an event", "seed photos", "wipe the
  gallery", "reset the device", "make the app foreground / silent-push / run the
  background task", "why did no upload cycle run", "read the extension's log", or
  anything touching /os, /user, /device or usbmux forward 18099 — or the SAME
  protocol served by the JVM host over a world (`:test:rig:runJvmHost`, no
  device, no lock). To install or launch a build first, load `snapsync-device`.
---

# rig-channel — driving the app's entry points over HTTP

The entry points that carry every interesting on-device behaviour **cannot be fired from the
sandbox**. The app-driven upload cycle is kicked only by a `BGProcessingTask` heartbeat or a
background-`URLSession` relaunch, so a headless host fires **neither**: a fully joined device with
access granted and the tier armed will sit there running **no cycle at all**, and nothing says so.

`:test:rig` is a Ktor CIO server that runs inside the app and lets you drive those entry points and
read the state back. Its protocol is specified (`docs/testing.md`, "One control protocol,
served by two hosts") and served by **two hosts**: this app host, and a JVM host over a `:test:world` world
(see "The JVM host" below). Every surface is a projection of a contract specified elsewhere, so there is no
second way-to-drive that can rot or lie.

To **build** the IPA, load `ssh-mac-build`. To install/launch it, load `snapsync-device` (which has
you load the global `ios-device` skill first).

## Take the device lock first

This skill drives a phone every project on this machine shares, so everything here is inside the
global `ios-device` guard's fence. Take CodeHydra's `ios-device` lock exactly as that skill describes —
as a **background** Bash call, **never** under `ch bg` (waiting for the phone is this workspace being
busy):

```
ch lock take ios-device "<why you need the phone>"      # queues FCFS; exits once this workspace holds it
```

The **workspace** holds the lock, not a process: it stays held across calls and turns until you release
it. `ch lock ls` shows the holder, age, reason and waiters. When the device work is done:

```
ch lock release ios-device
```

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
uvx --python 3.14 pymobiledevice3 usbmux forward 18099 18099 &   # backgrounded; it blocks
B=http://127.0.0.1:18099
curl -sS --max-time 180 "$B/health"
```

⏱️ **Use a uniform, generous `--max-time` (180 s).** A receipted trigger legitimately blocks until the app
releases the OS completion handler — after the wake's own work, or (heartbeat) after its whole tail, or on
the operating system's expiry. No clock of the app's own bounds it any more (`changes/own-work-per-wake`),
so a short curl timeout makes a transport failure indistinguishable from a slow wake — the same
absence-collapse this repo keeps paying for, reintroduced where nobody looks for it.

🔢 **Port 18099 is a DEVICE-ONLY default.** One app instance runs per device, so a fixed port needs no
discovery. All simulators on a host **share the host's loopback**, so a simulator must override it —
`SIMCTL_CHILD_SNAPSYNC_RIG_PORT=<port>`, because `simctl launch <dev> <bundle> KEY=VAL` passes
**argv, not environment**. The bind address is `127.0.0.1` and nothing else, gated.

📄 **On a simulator, read the port instead of assuming it.** The rig publishes the port it actually
bound to `Documents/rig.port` in its own container, reachable with
`xcrun simctl get_app_container <dev> app.snapsync data`. **No file means the bind failed** — which is
the one case a fixed default hides, since a colliding instance would answer your `curl` with the very
port you asked for. Full simulator runbook: load **`ios-simulator`**.

## The endpoints

Namespaced by **who is on the other side of the call**. That is not decoration: `/os` and `/user` have
populations sitting in source, so a guard derives them and an unwired member is a red build; `/device` has
no population to derive from, so it is hand-listed and small.

```
GET  /health                            rig=up, port, boot instant — liveness ONLY
GET  /device                            what THIS host honours and refuses of the shared vocabulary
                                        (RigVocabulary), each refusal with its reason; 500 names a gap

POST /os/<root>/<entry>?arg=…           the real @PlatformEntry member, invoked as the platform invokes it
                                        <root> is app | photokit-ext — WHOSE entry point, since the
                                        channel reaches two composition roots
POST /user/<command>?…                  the real StatusContainerHost command, as a tap invokes it

GET  /device/state                      the real UiState + readiness + ledger + downloads + build facts
GET  /device/logs?process=app|extension pass-through to DeviceLogSource.tail, BOTH processes
GET  /device/gallery[?cutoff=…][&resources=true]   the library, through the app's own policy
POST /device/reset                      void durable sync state
POST /device/gallery/seed?n=&kind=bulk|policy
POST /device/gallery/wipe?scope=all|assets|albums[&limit=&offset=]
POST /device/uploaders?app=&extension= switch one uploader off/on (see below)

GET  /contract                          the contracts registered for THIS host, one name per line
POST /contract/<name>                   run a port contract in-app; answers its RECORDING (device) or its
                                        OUTCOME TABLE (simulator app) — see below
```

There is **no inventory route** for excluded members: asking for one returns **the reason it is excluded**.
What `GET /device` lists is different — the shared vocabulary both hosts speak — and a verb in it that this
host cannot honour answers **`409` with the reason**, never `404` and never a success that did nothing. On
this host the world levers are refused; on a device, the simulator-only upload-job verbs are too.

**`/device/state` is the reduced state, not a mirror of it** — `UiState` is `@Serializable` where it is
declared, so the encoder is compiler-generated. It also carries what `UiState` deliberately omits: the
ledger aggregates (the only assertion that proves bytes landed), download progress, **readiness**, the
build facts (which backend this build points at), and the OS's own view of the extension registration.

📄 **`/device/logs?process=extension` needs no relaunch, no copy step and no `apps pull`.** An unreadable
or absent log is a `404` with a stated reason, never an empty `200`. It reads the **current** file only —
a rolled `.1` sibling is not reachable this way.


## `/contract/<name>` — recording a port contract on the device

Capability `docs/architecture.md`. A contract whose clauses need the **entitled** Keychain (`SecureStore`: the
legacy-protection upgrade, a real write read back) cannot run in CI — a simulator test executable is
refused every Keychain call. So it runs here, in-app, and every `SecItem*` call the adapter makes is
recorded with iOS's answer; CI then **replays** that recording against the current adapter on every build.

```bash
curl -s -X POST localhost:18099/contract/SecureStore > test/contracts/recordings/SecureStore@IOS_DEVICE_APP.rec
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
- `404` means this build has no such contract — check the build carries `-Psnapsync.rig=true`.
- **`BackgroundScheduler`** records the same way (`…/contract/BackgroundScheduler > test/contracts/recordings/BackgroundScheduler@IOS_DEVICE_APP.rec`).
  It runs against the production heartbeat identifier (`BGTaskScheduler` accepts only identifiers the
  plist lists), so ⚠️ **the run leaves the rig build's upload heartbeat CANCELLED** — the app's next trigger
  (a foreground, a completed cycle) re-arms it. Its volatile key (`begin`, the absolute earliest-begin
  date) is masked in `adapter/ios/app-only/src/rig/kotlin/…/SchedulerContracts.kt`.
- **`UploadExtensionRegistry`** records once per photo grant, into a file named for it. With **full** access:
  `…/contract/UploadExtensionRegistry > …/UploadExtensionRegistry@IOS_DEVICE_APP.GRANTED.rec`; then switch the
  app to **Limited** access in Settings, relaunch, and record `…LIMITED.rec`; then switch back. The body's
  `# file:` header names the file each run belongs in. The full-grant run disables and re-enables the extension
  (wiping in-flight upload jobs), so it answers `409` while the device is a member of an event — reset first
  (`POST /device/reset`), deliberately.

### Recording INSIDE the upload extension — `?host=IOS_DEVICE_PHOTOKIT_EXT`

The upload-job contract (`Upload`) records in the upload extension, the process production calls
PhotoKit's job API from. The rig cannot reach that process, so the app requests the run through the App Group and
re-registers the extension, which makes the OS invoke it; the extension runs the contract instead of its upload
cycle and writes the recording back, and the verb answers it:

```bash
curl -s --max-time 120 -X POST "localhost:18099/contract/Upload?host=IOS_DEVICE_PHOTOKIT_EXT" \
  > test/contracts/recordings/Upload@IOS_DEVICE_PHOTOKIT_EXT.rec
```

- **The build must be baked to the loopback upload base** — `snapsync.deployment=local` with
  `deployments/local.json`'s domain at `127.0.0.1:18099` (the rig's port): the jobs upload to the rig's own
  receiver, which answers each fixture route (`TransferFixture`) with the status in its path. Any other base is
  refused (`409`). Revert `deployments/local.json` before committing.
- Preconditions: full photo grant, no membership (`409` names the fix), and at least one photo in the library (a
  usable resource is the newest photo; the run seeds none).
- `504` means no result within 90 s. Its body says which half failed: the OS never invoked the extension — it
  **backs off 6–11 min after a call it killed**, and ignores triggers meanwhile — or the run was taken and killed
  at the ~60 s budget. Wait out a backoff; do not hammer it.
- The run leaves the extension registered under the loopback base; a normal build's join re-registers it.

## `/contract` on the simulator app — the PhotoKit contracts, live

On a **simulator** the same verb runs the photo-library contracts against real PhotoKit under a real full
grant. The app bundle is the only simulator process `applesimutils` can grant photo access to. Nothing is
recorded: the answer is the outcome table, and the `ios-contracts` CI job runs exactly what `GET /contract`
lists, on every push. `scripts/sim-contracts` is that job, and running it on a Mac session reproduces it.

```bash
curl -s localhost:$PORT/contract                          # CandidateSource, UploadDiscovery, … (this host's)
curl -s -X POST localhost:$PORT/contract/AlbumManager     # "# host: IOS_SIM_APP" then one line per clause
```

- **`409` + `refused: …`** means the precondition failed and **no clause ran**: the process is not the
  simulator app, or it does not hold `GRANTED`. Grant with `applesimutils … --setPermissions "photos=YES"`
  **before** launch; `simctl privacy grant photos` writes a TCC row PhotoKit never consults.
- The contracts **seed photos and never delete them** (a delete raises a confirmation someone must tap).
  Each clause owns a one-day capture window in 1980–1999, so repeat runs only add assets. Use a fresh
  simulator when the library's size matters.
- `SecureStore` and `BackgroundScheduler` are **not** in the simulator's list: they record only on an
  entitled device, and asking for either here answers `409`.
- **The transfer contracts** (`Upload`, `Download`) run both `URLSession` transports
  against a loopback peer, `scripts/transfer-fixture.py`, whose address the verb takes as `?fixture=`:
  `python3 scripts/transfer-fixture.py --port 8123 --log /tmp/fx.log &` then
  `curl -s -X POST "localhost:$PORT/contract/Download?fixture=http://127.0.0.1:8123"`. Without the
  parameter the run is **refused whole** (`409`). The simulator target binds a DEFAULT session, so these
  evidence everything but the background session's lifecycle (see `TransferSessions.kt`).

## `/os/photokit-ext` — the OS-driven upload tier's own root

The channel reaches **two** composition roots, and the path segment says which. `/os/app/<member>` is
`SnapSyncRoot`'s — everything the Swift app shell forwards. `/os/photokit-ext/<member>` is
`UploadExtensionRoot`'s: the upload extension's, invoked directly rather than waiting for the OS to
schedule an appex.

```
POST /os/photokit-ext/processRawValue   run one upload cycle; ANSWERS with the tri-state result
                                        the OS reads, plus what the cycle created
POST /os/photokit-ext/onTerminate       the OS killing a cycle
```

`processRawValue` is the channel's only **answering** trigger: the OS invokes `process()` synchronously and
acts on its return, so the rig receives that value rather than inferring it from a poll — the same
reasoning that makes a receipted trigger receive its completion handler.

It runs on **its own serial thread, never main**. The extension process has no main lane, and running its
`runBlocking` on the app's UI thread would freeze the app for the whole cycle.

**Not gated on any mechanism.** Both uploaders write the one App-Group ledger by design, so this cycle is
just another cycle over it; the extension root's own admission decides what it may do — under anything but a
full grant it withholds (records and acknowledges, creates nothing), exactly as when the OS invokes it.

**On a device** this drives the **real** OS job queue — forcing a cycle on demand instead of waiting for
the OS to schedule one. Send no body there; the OS holds the queue and a body is refused rather than
ignored. **On a simulator** the queue is substituted and you play the OS: hand the finished job sets in,
get the created jobs back, and move their bytes with `POST /device/upload-jobs/perform`. Load
`ios-simulator` for that half — the shape, the levers and the two host divergences are documented there.

## Driving an event end to end

Everything below assumes a rig build installed and launched (`snapsync-device`) and `usbmux forward 18099`.

```
# JOIN — the warm universal-link path, same decode -> gate -> join a scanned QR takes
curl -X POST "localhost:18099/os/app/onSceneContinueActivity?arg=https://snapsync.stho.net/join%23v=3&d=<payload>"

# CREATE — exactly as a user creates one: mint, then confirm the gate it opens
curl -X POST "localhost:18099/user/create?name=Trip&startsAt=2026-08-23T00:00&endsAt=2026-08-30T00:00"
curl -s localhost:18099/device/state | jq '.ui'      # wait for JoiningEvent(eventId, Ready)
curl -X POST "localhost:18099/user/confirmJoin?cutoff=2026-08-23T00:00:00Z&until=2026-08-30T00:00:00Z&direction=upload&saveToAlbum=false"

# MINT ONLY — create, read the id, then abandon the gate
curl -X POST "localhost:18099/user/cancelJoin"

# LEAVE
curl -X POST localhost:18099/user/leave

# THE REST OF /user (both hosts): rename[?event=]&name=, renameStatusConsumed, confirmSwitch, retryLoad, retryJoin,
# setRange?[from=eventStart|now][&until=eventEnd][&cutoff=…Z][&until=…Z]  (sets the form, commits nothing),
# sendDiagnostics?note=&screen=  (a rig build of the app carries no DSN: it SAVES the report to Documents/diagnostic-report.json, sends nothing)
```

⚠️ **`create` is non-idempotent** — every call mints a **new** backend event. There is no launch variable
to forget to unset any more, but there is also nothing stopping a loop from minting a hundred.

⚠️ **Never join an event you did not create.** A `direction=download` join imports that event's photos into
this device's library and registers this device on someone's real membership.

## The photo library

```
curl -s "localhost:18099/device/gallery" | jq                      # raw census: total, screenshots, recordings
curl -s "localhost:18099/device/gallery?cutoff=2026-07-01T00:00:00Z" | jq   # + per-asset policy verdict
curl -s "localhost:18099/device/gallery?cutoff=…&resources=true" | jq       # + each asset's resources

curl -X POST "localhost:18099/device/gallery/seed?n=4000&kind=bulk"    # walk-cost: tiny 2001-dated assets
curl -X POST "localhost:18099/device/gallery/seed?n=20&kind=policy"    # policy probe: +1h, straddling 3 MP
```

`kind=policy` seeds assets dated an hour ahead — past any cutoff an event created today can carry — and
**alternating** above/below the 3 MP image floor, so the resolution rule is the only thing that can separate
them. Expect exactly the even-indexed half admitted.

The read reports **which rule refused** each excluded asset, in the rule's own vocabulary, so
`refusedBy: "MinImageArea(3000000)"` sits in the same row as the `pixelArea` that triggered it.

⚠️ **`resources=true` is ~110 ms per asset** (one PhotoKit round-trip each) — about 17 minutes across a
9525-asset library. The response reports what it paid. Ask for it when you need a filename, which IS the
upload/ledger key; otherwise don't.

⚠️ Under a **`LIMITED`** grant, `total` is the hand-picked **selection**, not the library. The grant is in
the response for that reason. A fetch under `.limited` can also surface iOS's own limited-access alert.

## Emptying the library

```
ch bg curl -sS -X POST "localhost:18099/device/gallery/wipe?scope=all"   # all|assets|albums; NO --max-time
ch bg curl -sS -X POST "…/wipe?scope=assets&limit=1"           # one asset — the smallest thing that prompts
      curl -sS --max-time 30 -X POST "…/wipe?scope=assets&limit=0"   # selects nothing — the probe, below
```

`limit`/`offset` are optional; **omit them entirely** rather than passing an empty value. `&limit=` is the
empty string, not "unset", and it is refused with a `400` on purpose — a mistyped bound must never fall
back to "no window" and delete the whole library.

🏃 **Run it under `ch bg`, backgrounded, and end your turn.** This is the one call in this channel where
that is right, and it is not the usual `ch bg` rule (CLAUDE.md: don't wrap work the workspace is genuinely
doing). The wipe is not waiting on a machine — it is waiting for **a person to walk to the phone and tap**.
That is the definition of idle: the workspace should go idle so CodeHydra *notifies* the operator they are
needed, instead of reading busy while nothing happens and nobody is told. Foreground is not an option
anyway: **the request has no deadline** and the harness clamps a foreground Bash call at 10 min, so waiting
inline gets you `Exit code 143` and no result.

⏳ **Do not put a `--max-time` on it either** (except on the `limit=0` probe, which answers in ~50 ms). The
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
spend one call telling the two failures apart: **wipe with a window that selects nothing** (`&limit=0`, or
`scope=albums` with no albums). That submits an empty change block, needs no confirmation, and answers in
~50 ms. If THAT hangs too, the whole change pipeline is down and a restart is not the answer.

⚠️ **If YOU give up, the alert does not.** Killing the request, timing out, even killing the app leaves the
confirmation on screen — a later tap still deletes, with nothing listening. Only a tap or `Don't Allow` ends
it, and `GET /device/gallery` is the only truth about what happened. (There is no `answered` field any more:
the response arrives when the platform answers, so `committed` plus `errorCode` say everything. A tapped
Cancel is `committed:false` + `errorCode:3072`.)

An unrecognized `scope` is a `400` that names what is accepted — the only value-checked command here,
because this is the only one that cannot be undone.

## Resetting durable state

```
curl -X POST localhost:18099/device/reset
```

Voids the ledger, the discovery cursor, the membership config (**locally** — no backend is notified), and
prunable download rows. **Keeps** every row carrying an import handle, so downloaded photos are not
re-uploaded. Answers with the ledger counts after the fact, so "it cleared" is verifiable.

⚠️ **Order matters and nothing enforces it any more.** Crossing backends, reset **before** leaving: after a
reset the device is unjoined, so a leave is a no-op rather than a `DELETE` aimed at the backend you are
leaving behind. There is no coordinator imposing that order now that each command is its own request.

## Triggers return what the PLATFORM returns

⚠️ **`onForeground` returns `202` and does NOT wait** — and it is the trigger you will reach for most.
The platform hands it no completion handler, so neither does the rig. **Poll `/device/state`.**

The three entries the OS *does* wait on — `onSilentPush`, `onBackgroundTask`, `onBackgroundTransfers` —
block until the app releases the handler and return `heldMs`. A push and a transfer wake release after
their **own work** (the push's union read and enqueue; the session's staging and drain report) and run the
rest — import, top-up, walk — as the tail afterwards, so `heldMs` does not include the tail; poll
`/device/state` for its effects. The heartbeat releases after its tail. The last two take the identifier
the OS would deliver as `arg`: `onBackgroundTask?arg=app.snapsync.upload.heartbeat` (the app uploader's
heartbeat — the only background task; the download backstop is deleted), and
`onBackgroundTransfers?arg=app.snapsync.upload.session` for the app uploader's session (any other channel
routes to the downloads). An unknown task identifier is completed at once and logged.

🧭 **The rig classifies nothing.** A release carries no outcome, so "released after the own work" vs
"released on the operating system's expiry" is **not** derivable from `heldMs`. The authoritative answer is
the expiry line `… OS handler released on the operating system's expiry …`, which `OsCompletions` emits on
the expiry path and no other — read it via `/device/logs` after this request's `[rig] → /os/…` marker. Every
request writes that marker, so it doubles as the log cursor.

Excluded members answer with **the reason they are excluded**, not a bare 404 — `onLaunch` re-registers
`NSNotificationCenter` observers documented as never removed, so re-invoking it corrupts the process under
test (reset is a relaunch), and `onRequestPermission` raises a system alert only a finger can answer.

⚠️ **`/user` commands are intents and return `202`, exactly as a tap does.** They start work and do not wait
for it, because the UI itself has no completion signal to expose. Poll `/device/state`.

## Ordering trap

`onForeground` fires **before** the membership config resolves — measured at 17:53:25.23 against
17:53:27.45. A caller that triggers and then asserts reads a membership-less state and concludes
nothing happened. Poll `/device/state`'s `ready.configResolved` instead of sleeping.

## If it does not answer

`connection refused` is ambiguous between "app not running", "port forward not set up" and "the rig
failed to bind". The rig logs a bind failure at `Error` naming the address and port, and that log is
pullable **without** the rig (`apps pull … Documents/debug.log`) — read it before guessing. The usual
cause is a previous instance still alive holding the port; SIGKILL it (the global `ios-device` skill's restart recipe).

## Switching an uploader off

Both uploaders run where both exist (the app's on every OS, the extension beside it from iOS 26.1 under a full
grant). To exercise one alone there is a development switch per uploader, with no production writer:

```
POST /device/uploaders?app=on|off&extension=on|off
POST /device/uploaders?reset
```

`app=off` makes the app's uploader withhold (it records completions, creates nothing). `extension=off` makes
the extension unregistrable, and the reconcile the command triggers **deregisters it now** — the extension cannot
read app memory, so off must be a deregistration (which wipes its in-flight OS jobs: the test's intent). The
answer reports the switch and the registration fact it produces:

```json
{"app":true,"extension":false,"extensionRegistrable":false,"permission":"GRANTED","osSupportsOsDriven":true}
```

To drive the app's uploader alone on a ≥26.1 device: `extension=off`, then fire
`POST /os/app/onBackgroundTask?arg=app.snapsync.upload.heartbeat` (the app uploader's heartbeat). The switch dies with the process.

Note `/device/state`'s `build.uploadTier` is a **build fact** — which uploaders this OS carries
(`app` or `app+extension`) — and does not move with the switch.

## The JVM host — the same protocol with no device

`:test:rig` also has a `jvm()` target: `JvmRigHost` serves the **unchanged** server and routes over a
`:test:world` `World`, whose `core` is the real `AppCore` from the same `snapSyncApp`. No phone, no lock, no
build on a Mac:

```bash
./gradlew :test:rig:runJvmHost -Psnapsync.rigBackend=mini   # or deno: the REAL api/ via :test:edge
# prints one line once bound:  RIG-JVM READY <port>
curl -s localhost:<port>/device            # honoured + refused (reasons) for THIS host
```

- `mini` is the in-memory mini-edge; `deno` starts the real `api/` (`serve.ts --ephemeral`, loopback-only).
  On `deno`, a lever only an in-memory store can pull (`device/backend/offline`, …) answers `409`
  "unavailable on this backend".
- Same `/os`, `/user`, `/device/state` shapes as the app. `os/app/onSceneContinueActivity?arg=<link>` reaches
  the inbound port's open-URL entry, `os/photokit-ext/processRawValue` runs the world's upload cycle.
- The **world levers** the app refuses: `device/jobs` (live keys), `device/jobs/complete[?key=]` (the "OS"
  finishes a transfer — a real PUT to the backend), `device/jobs/fail?key=&error=`, `device/jobs/limit?n=`,
  `device/backend/objects[?device=]`, `device/backend/offline?on=`, `device/permission?status=`,
  `device/import/fail-next`, `device/membership/unreadable?on=`, `device/downloads/stage|reconcile`,
  `device/album/place?album=&asset=`, `device/foreign-device?device=&assets=a,b[&event=][&filename=]`,
  `device/status/refresh`, `device/downloads/stage?wait=false` (returns while an import is parked).
- The **integration surface's** world levers and reads (also refused by the app):
  - backend reads (`[event=]` defaults to the joined one, `[device=]` to this one): `backend/union`, `backend/manifest`,
    `backend/device-config`, `backend/event`, `backend/departed`, `backend/publishes`, `backend/pushes`;
    `diagnostics/sent` (the dumps the reporter received);
  - backend levers: `backend/min-app-version[?minimum=]`, `backend/sweep`, `backend/hold-leave`,
    `backend/release-leave`, `backend/fail-listing?on=`, `backend/deposit?asset=`, `backend/legacy-event?name=`,
    `backend/refuse-credential`;
  - OS and library: `clock/advance?to=<instant>`, `app-version?version=`, `relaunch`, `selection/change?assets=a,b`,
    `gallery/add?id=&date=&kind=photo|low-res|screenshot|screen-recording|hd-video|live-photo|gif`,
    `gallery/fail-next-enumeration`, `import/suspend-next[?afterCommit=true]`, `import/await-parked`,
    `import/resume?succeeded=`, `logs/append?process=app|extension` (body = text),
    `staging/seed-legacy-backlog` (the one lever that writes app-private state: an upgraded install's leftovers).
- Device facts the JVM host reads and the app host does not wire yet: `device/staging`, `device/album/contents`.
- `/contract` refuses on this host (`409`, naming `JVM`): JVM contract bindings run under Gradle.
- Typed client for tests: `:test:control`'s `RigClient` (a `409` is a `Reply.Refused`, never an exception).
- Stop it by killing the JavaExec process. Don't `pkill -f runJvmHost` from a shell whose own command line
  contains that string: it matches itself.

