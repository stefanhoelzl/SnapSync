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
  anything touching /os, /user, /device or usbmux forward 18099. To install or
  launch a build first, load `ios-device`.
---

# rig-channel — driving the app's entry points over HTTP

The entry points that carry every interesting on-device behaviour **cannot be fired from the
sandbox**. The app-driven upload cycle is kicked only by a `BGProcessingTask` heartbeat or a
background-`URLSession` relaunch, so a headless host fires **neither**: a fully joined device with
access granted and the tier armed will sit there running **no cycle at all**, and nothing says so.

`:test:rig` is a Ktor CIO server that runs inside the app and lets you drive those entry points and
read the state back. It is **dev infrastructure: non-gating, no spec** — every surface is a
mechanical projection of a contract specified elsewhere, so there is no second way-to-drive that can
rot or lie.

To **build** the IPA, load `ssh-mac-build`. To install/launch it, load `ios-device`.

## Take the device lease first

This skill drives the one phone, so everything here is inside `scripts/device-guard`'s fence. Take
the lease exactly as `ios-device` describes — as a **background** call, **always** under `ch bg`:

```
ch bg scripts/device-lease "<why you need the phone>"      # blocks; THIS process is the lease
```

## Containment — why this never ships

`-Psnapsync.rig=true` adds BOTH the `:test:rig` dependency and the source directory it contributes
into `:app:ios`. Without the property it adds **neither**, so a production build contains no rig
source at all — not a stub, not an inert branch. Measured: `_kclass:app.snapsync.rig.RigServer` is
present in the binary with the property and there are **zero** `app.snapsync.rig` symbols without it.

This is also why there is no `SNAPSYNC_RIG_*` entry in `ios-device`'s launch-trigger index:
`SNAPSYNC_RIG_PORT` is read by a file that **does not exist** in a production build, so unlike every
`SNAPSYNC_*` trigger it is inert by construction rather than by a runtime check, and it is deliberately
not part of `LaunchDirectives`.

## Building and connecting

On the ssh-mac runner, before `xcodebuild` (Xcode's Gradle invocation picks it up):

```
echo "snapsync.rig=true" >> gradle.properties
```

Then, with the build installed and launched:

```
uvx --python 3.14 pymobiledevice3 usbmux forward 18099 18099 &   # backgrounded; it blocks
B=http://127.0.0.1:18099
curl -sS --max-time 180 "$B/health"
```

⏱️ **Use a uniform `--max-time` above 120 s.** A receipted trigger legitimately blocks until the app
releases the OS completion handler, and the download backstop's receipt deadline is **120 s**. A
shorter curl timeout makes a transport failure indistinguishable from a receipt that expired — the
same absence-collapse this repo keeps paying for, reintroduced where nobody looks for it.

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
POST /device/upload-mechanism?value=…   pin the resolved upload tier (see below)
```

There is **no inventory route**. Asking for a member that is excluded returns **the reason it is
excluded**, which was the only part that carried information; the names live here.

**`/device/state` is the reduced state, not a mirror of it** — `UiState` is `@Serializable` where it is
declared, so the encoder is compiler-generated. It also carries what `UiState` deliberately omits: the
ledger aggregates (the only assertion that proves bytes landed), download progress, **readiness**, the
build facts (which backend this build points at), and the OS's own view of the extension registration.

📄 **`/device/logs?process=extension` needs no relaunch, no copy step and no `apps pull`.** An unreadable
or absent log is a `404` with a stated reason, never an empty `200`. It reads the **current** file only —
a rolled `.1` sibling is not reachable this way.


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

⚠️ **Refused unless the resolved mechanism is `photokit`**, naming what resolved. Under `url_session` the
app's own arm holds a live `LedgerWriter` and this cycle would be a second one over the same App-Group
ledger. Clear any pin first (`POST /device/upload-mechanism?value=none`) and hold a full photo grant.

**On a device** this drives the **real** OS job queue — forcing a cycle on demand instead of waiting for
the OS to schedule one. Send no body there; the OS holds the queue and a body is refused rather than
ignored. **On a simulator** the queue is substituted and you play the OS: hand the finished job sets in,
get the created jobs back, and move their bytes with `POST /device/upload-jobs/perform`. Load
`ios-simulator` for that half — the shape, the levers and the two host divergences are documented there.

## Driving an event end to end

Everything below assumes a rig build installed and launched (`ios-device`) and `usbmux forward 18099`.

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

The four entries the OS *does* wait on — `onSilentPush`, `runDownloadBackstop`, `runUploadHeartbeat`,
`handleBackgroundUrlSession` — block until the app releases the handler and return `heldMs` and
`deadlineMs`.

🧭 **The rig classifies nothing.** `OsReceipt.release` carries no outcome, so "released because the work
finished" vs "released on the deadline" is **not** derivable from `heldMs`. The authoritative answer is
the expiry line `… OS handler released on its <deadline> deadline …`, which `OsReceipt` emits on the
expiry path and no other — read it via `/device/logs` after this request's `[rig] → /os/…` marker. Every
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
cause is a previous instance still alive holding the port; SIGKILL it (`ios-device` covers that).

## Pinning the upload tier

`SNAPSYNC_FORCE_URLSESSION_UPLOAD` was deleted with the rest of the launch-trigger surface. Its
replacement landed in `d21a511e` as a channel verb — a development pin on the *resolved* mechanism,
with no production writer:

```
POST /device/upload-mechanism?value=photokit|url_session|idle|none
```

It answers with what you asked for AND what the app will actually do, because a pin naming a mechanism
this OS cannot run is clamped by the resolver:

```json
{"pinned":"url_session","resolves":"url_session","permission":"GRANTED","osSupportsOsDriven":true}
```

Verified 2026-08-25 on the SE2 (iOS 26.5.2): pinning `url_session` on a ≥26.1 device under a **full**
grant drove real uploads through the app-driven pump. Fire `POST /os/app/runUploadHeartbeat` to kick it —
`runUploadHeartbeat` is the app-driven tier's entry point and does nothing for the OS-driven one, whose
extension the OS invokes on its own cadence.

Note `/device/state`'s `build.uploadTier` is a **build fact** and does not move with the pin; the pin
response's `resolves` is the live answer.

(Before this verb existed, the app-driven tier was reachable only under a `LIMITED` grant, where the
extension **cannot be registered at all** — `setUploadJobExtensionEnabled` is refused in both
directions with `PHPhotosErrorAccessUserDenied` 3311, so `resolveUploadMechanism` yields the
app-driven mechanism there. That route still works but exercises less: a partial grant feeds discovery
the in-memory selection snapshot instead of walking the library.)
