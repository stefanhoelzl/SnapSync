---
name: rig-channel
description: >-
  Force an OS-callback entry point on the connected iPhone, and read the app's
  real live state over HTTP — the build-time-only control channel (:test:rig,
  -Psnapsync.rig=true). Use when the task means "make the app foreground /
  silent-push / run the background task", "why did no upload cycle run", "read
  the extension's log without a relaunch", "what is the app's UiState right
  now", or anything touching /state, /logs, /trigger or usbmux forward 18099.
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
the lease exactly as `ios-device` describes — as a **background** call, **always** under `ch-bg`:

```
ch-bg scripts/device-lease "<why you need the phone>"      # blocks; THIS process is the lease
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

## The endpoints

```
GET  /health                       composition mode, upload tier, upload base, port, boot instant
GET  /state                        the real UiState + readiness + ledger + download + read-models
GET  /logs?process=app|extension   pass-through to DeviceLogSource.tail, BOTH processes
GET  /triggers                     what is wired, and what is excluded WITH ITS REASON
POST /trigger/<name>?arg=…         the real @PlatformEntry member, invoked as Swift invokes it
```

**`/state` is the reduced state, not a mirror of it** — `UiState` is `@Serializable` where it is
declared, so the encoder is compiler-generated. It also carries what `UiState` deliberately omits: the
ledger aggregates (the only assertion that proves bytes landed), download progress, and **readiness**.

📄 **`/logs?process=extension` needs no relaunch** — one request instead of `SNAPSYNC_EXPORT_LOGS=1` +
relaunch + `apps pull`. An unreadable or absent log is a `404` with a stated reason, never an empty
`200`.

## Triggers return what the PLATFORM returns

⚠️ **`onForeground` returns `202` and does NOT wait** — and it is the trigger you will reach for most.
The platform hands it no completion handler, so neither does the rig. **Poll `/state`.**

The four entries the OS *does* wait on — `onSilentPush`, `runDownloadBackstop`, `runUploadHeartbeat`,
`handleBackgroundUrlSession` — block until the app releases the handler and return `heldMs` and
`deadlineMs`.

🧭 **The rig classifies nothing.** `OsReceipt.release` carries no outcome, so "released because the work
finished" vs "released on the deadline" is **not** derivable from `heldMs`. The authoritative answer is
the expiry line `… OS handler released on its <deadline> deadline …`, which `OsReceipt` emits on the
expiry path and no other — read it via `/logs` after this request's `[rig] → /trigger/…` marker. Every
request writes that marker, so it doubles as the log cursor.

Excluded entry points answer with **the reason they are excluded**, not a bare 404 — `onLaunch`
re-registers `NSNotificationCenter` observers documented as never removed, so re-invoking it corrupts
the process under test. Reset is a relaunch.

## Ordering trap

`onForeground` fires **before** the membership config resolves — measured at 17:53:25.23 against
17:53:27.45. A caller that triggers and then asserts reads a membership-less state and concludes
nothing happened. Poll `/state`'s `ready.configResolved` instead of sleeping.

## If it does not answer

`connection refused` is ambiguous between "app not running", "port forward not set up" and "the rig
failed to bind". The rig logs a bind failure at `Error` naming the address and port, and that log is
pullable **without** the rig (`apps pull … Documents/debug.log`) — read it before guessing. The usual
cause is a previous instance still alive holding the port; SIGKILL it (`ios-device` covers that).

## 🚫 Do not force the URLSession tier on iOS ≥26.1 yet

The OS's extension registration survives relaunch **and** reinstall, and `UploadArm` cannot deregister
it while the composition passes `osUploadProducer = { null }` under the force flag — so both tiers
would write one App-Group ledger, breaching `sync-ledger`'s single-writer invariant. A follow-on change
(`ComposedProducers` distinguishing selectable-from-stoppable) fixes it; until then, don't.
