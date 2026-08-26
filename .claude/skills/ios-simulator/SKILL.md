---
name: ios-simulator
description: >-
  Run SnapSync's real live stack on an iOS simulator — ad-hoc sign it so it has
  an App-Group container, point it at a local backend, seed a photo library and
  grant permissions headlessly, and drive it through the control channel. Two or
  more instances run in parallel, so this is the host for anything needing more
  than one member. Use for "run it on a simulator", "two members", "seed the
  library without tapping", "grant photo access headlessly", "boot a second
  device", or any xcrun simctl work.
---

# ios-simulator — the second control-channel host

The SE2 cannot do three things this can: run **two members of one event at once**, **wipe and seed a
photo library headlessly** (`SNAPSYNC_WIPE_GALLERY` needs a physical tap on the platform's own delete
confirmation), and **set permission state headlessly**. It is also disposable and parallel.

**It needs NO device lease.** Nothing here touches the phone, `scripts/device-guard` does not fence
`xcrun`/`simctl`, and two agents can hold two simulators at once. That is the opposite of `ios-device`,
and it is the main reason this is its own skill.

Everything runs on the macOS side of the **ssh-mac** loop — load `ssh-mac-build` for the session. To
drive the channel once the app is up, load `rig-channel` (the endpoints are identical; only the port
discovery differs). For the backend, load `local-backend`.

## 🚫 What a simulator CANNOT do

State these before writing a scenario against this host, or you will write one that silently cannot run.

- **No `PermissionStatus.LIMITED`.** `simctl privacy` has no `photos-limited` (though `contacts-limited`
  exists). Accepted everywhere-gap: the device needs taps for it too.
- **No APNs token** — `no valid "aps-environment" entitlement string found`. `simctl push` never contacts
  Apple, so a synthetic token through the `onPushToken` trigger is the way in.
- **The OS never invokes the upload extension — so the CHANNEL invokes its root instead.** The tier
  resolves to `photokit` here under a full grant, exactly as it does on a ≥26.1 device, and it now runs:
  `/os/photokit-ext/processRawValue` calls the **real** `UploadExtensionRoot`, so the shared `uploadCore`,
  the entry gate, the re-join reconcile, real PhotoKit discovery, the real selection policy, the real
  App-Group ledger and a real backend are all exercised. **Do not pin `url_session` for a photokit
  scenario any more** — the pin is now only for exercising the app-driven tier, and the trigger refuses
  outright while a pin is in force (two `LedgerWriter`s over one ledger).

  What this target substitutes, and nothing else, is the **OS upload-job subsystem**: the registration
  record and the job queue. You play the OS for both.

  ```bash
  # one cycle: hand in what the OS "finished", get back what the cycle created
  curl -sS -X POST "http://127.0.0.1:$PORT/os/photokit-ext/processRawValue" \
       -d '{"finished":[],"jobLimit":100}'
  # → {"processRawValue":1,"result":"processing","queue":"simulated","created":[{"key":"…-primary.png",
  #    "destination":"http://127.0.0.1:8080/api/v1/file/…","headers":{…}}]}

  # move one job's bytes for real (or ?fail=network to forge a failure and drive the retry chain)
  curl -sS -X POST "http://127.0.0.1:$PORT/device/upload-jobs/perform" \
       -d '{"key":"…","destination":"…","headers":{…}}'

  # present it back as finished, and the next cycle records it
  curl -sS -X POST "http://127.0.0.1:$PORT/os/photokit-ext/processRawValue" \
       -d '{"finished":[{"key":"…","action":"acknowledge","state":"succeeded"}]}'
  ```

  ⚠️ **The transfers `perform` makes use a DEFAULT session, so they die with the process** — the OS's own
  queue genuinely survives. Kill the app mid-transfer and the job is simply lost, where a device would
  have finished it. That is the host, not a fault.

  ⚠️ **The registration record is the rig's, not the OS's.** `POST /device/upload-extension/record?registered=true`
  plants a stale record (so the ritual's leading disable has something to remove) and `&failNextWith=3202`
  arms the next change to fail. It does **not** survive an app restart, where the real record survives
  reinstall and reboot.

  🚫 **What this host still cannot do**: OS scheduling of `process()`, the appex Swift shell and its
  `processingResultRawValue` handoff, the appex memory cap, and cross-process ledger locking. All
  device-only, unchanged.

  📏 **Why the substitution exists, measured 2026-08-26 (iOS 26.5, full grant, clean device, appex embedded
  and signed):** `setUploadJobExtensionEnabled(true)` is **refused** — `false`, `PHPhotosErrorDomain:-1`,
  a code distinct from `3201`/`3202`/`3311` — and with no configuration record
  `creationRequestForJobWithDestination` raises `NSInvalidArgumentException` **inside PhotoKit** and
  terminates the process. It does not return an error. That is why the binding is per compilation target:
  a device binary contains no route to the substitute, and this one contains no route to the real calls.
  Full record: `openspec/changes/…/exercise-os-driven-upload-on-simulator/PROBE-FINDINGS.md`.

  ⚠️ **Do not assert success on `/device/state`'s `download` view.** It is a *progress* read-model and
  returns to `{downloaded:0,total:0}` the moment the queue drains — transfers finish about a second after
  the trigger, so a poll 25 s later reads 0/0 and looks exactly like a failure. Assert on the device log's
  `transfer finished: status=… expected=… received=…` and `imported foreign asset … as …` lines. The
  gallery census is not clean proof either: a simulator ships with stock photos, so a device that imported
  3 reads `total: 9`.

  **The app-driven tier still works here too**, and `POST /device/upload-mechanism?value=url_session` is
  how you reach it (check `resolves`, not just `pinned`). Two members of one event, both directions, were
  measured that way on 2026-08-26: A uploaded three photos and B — joined `DownloadOnly` off A's invite
  link — downloaded and imported all three.

- **No background `URLSession` at all — so this target does not use one.** Bytes DO move here: the
  `iosSimulatorArm64` build binds an ordinary **default** session instead (`transferSessionConfiguration`
  in `:adapter:ios:app-only`, capability `ios-url-session-upload`). Uploads and downloads both work.
  Verified 2026-08-25 end to end for uploads: three photos, ledger `completed=3`, objects in
  `api/.localstore`.

  **What that binding does NOT give you**, and must never be reported as if it did:

  - transfers do **not** survive suspension or process death — they run in-process;
  - the OS never relaunches the app for `handleEventsForBackgroundURLSession`. You can still fire the
    `handleBackgroundUrlSession` trigger, but it exercises adopt + session-identifier routing ONLY;
  - because a default session never sends `didFinishEventsForBackgroundURLSession`, that trigger's
    receipt **always** runs to its 20 s deadline and logs an expiry. **That expiry is the host, not a
    fault.** The app logs the whole caveat at session construction, and `/trigger` returns it in the
    response's `note` beside `transferBinding`;
  - `__NSURLBackgroundSession` is never exercised, so it cannot cover the invalidation defect in
    `changes/archive/2026-07-12-fix-download-session-lifecycle` D5. Measured: after the daemon rejects the
    connection the session does not even report `didBecomeInvalidWithError`.

  Read the binding rather than guessing: `GET /device/state` → `build.transferBinding`
  (`"default"` here, `"background"` on a device).

  **The underlying platform fact is unchanged** — a *background* session still transfers nothing here, so
  if you construct one yourself, **every transfer fails instantly with `NSURLErrorDomain/-1`**
  (`NSURLErrorUnknown`), measured against loopback and a LAN host. The app-side pipeline still runs — tasks
  are created locally and the delegate fires — which is why that reads as "it ran and failed" rather than
  "it never started".

  **The cause, measured from the daemon's own log — which states it outright.** `nsurlsessiond` resolves
  each client's bundle identifier as it evaluates the XPC connection. Apple's processes resolve to a real
  one (`com.apple.trustd`, `com.apple.bird`) and their background sessions work. Everything we can build
  resolves to **`(null)`**, and the daemon then says so in as many words, at error severity (2026-08-25,
  with the client's own pid):

  ```
  Evaluating new XPC connection … from pid <n> … with client bundle identifier (null)
  Process with pid <n> does not have a bundle ID, rejecting connection
  … invalidated … xpc_connection_cancel()
  ```

  ⚠️ This **supersedes** `changes/archive/2026-08-25-correct-simulator-background-session-claims`, which
  recorded as an open risk that the daemon "does not state that as its reason, so the causal link is a
  correlation". It states it; the link is not an inference.

  The connection is dropped *after* being accepted — which is why the client's code is
  `NSCocoaErrorDomain 4097` (`NSXPCConnectionInterrupted`, **not** `Invalid`, which is 4099), followed by
  *"failed to create a background NSURLSessionDownloadTask, as remote session is unavailable"*. Read both
  sides with:

  ```bash
  xcrun simctl spawn <dev> log stream --style compact --level debug \
    --predicate 'process == "nsurlsessiond" OR process == "SnapSync"'
  ```

  **Do not spend time on these — all six were tested and none fix it:** ad-hoc signing · a real Apple
  Development identity · **stripping the signature entirely** (identical `(null)`/4097, so the 2019
  `xamarin-macios#7101` "just sign it" fix does not reproduce) · adding
  `application-identifier`/`team-identifier`/`get-task-allow` (each one individually makes the app
  un-launchable) · a different runtime (26.5 behaves as 26.2) · **any entitlement** — the daemon's own
  binary lists the client entitlements it checks and every one is a privileged *modifier*, never an access
  gate, and a client whose sessions succeed (`mobileassetd`) carries an empty entitlements dictionary.

  A **real installed app bundle declaring a valid `CFBundleIdentifier` still resolves to `(null)`**, so
  this is not something a bundle, a plist or a signature can fix. Nothing in the published literature
  covers an installed third-party app — every attested case is `xctest`, XCUITest or an App Clip.
- **No OS relaunch measurement.** Waking a terminated app for `handleEventsForBackgroundURLSession` needs
  a transfer that outlives the process, and by the line above none can exist here. Device-only.
- **It does not exercise the shipped identity path.** The device id resolves through a *different*
  `SecureStore` binding on this target (an App-Group file, because the Keychain group cannot exist
  here), so a regression in the Keychain binding is invisible on a simulator. Identity is a
  precondition here, not coverage.

## Build, sign, install

The signature is not optional. An **unsigned** simulator build has no App-Group container —
`App Group container 'group.app.snapsync' unavailable` — so no ledger, no config, no live stack. That
is why `screenshots.yml` gets away with `CODE_SIGNING_ALLOWED=NO`: forge boots no live stack.

```
# on the Mac, in the rsync'd repo
echo "snapsync.rig=true" >> gradle.properties

xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp \
  -configuration Debug -sdk iphonesimulator \
  -destination 'generic/platform=iOS Simulator' \
  -derivedDataPath build/ios \
  CODE_SIGNING_ALLOWED=NO \
  build
# NO host override: `BACKGROUND_UPLOAD_URL_BASE=` cannot reach the generated Deployment.plist and is
# silently ignored, baking the PRODUCTION host. Run `python3 scripts/resolve-deployment.py local`
# first instead — its 127.0.0.1:8080 host renders `http://127.0.0.1:8080/api/v1`, the scheme derived
# from the loopback literal (ATS exempts it; nothing else).

APP="$(find build/ios/Build/Products -maxdepth 2 -name '*.app' -path '*-iphonesimulator*' | head -1)"
scripts/sim-sign "$APP"          # appex first, then the .app; no --deep
xcrun simctl install "$DEVICE" "$APP"
```

`scripts/sim-sign` applies `iosApp/Configuration/simulator.entitlements` — the App Group and nothing else,
to the appex first and then the app.

⚠️ **Add NO entitlement a simulator cannot provision.** Each one makes the app un-launchable with a
`SBMainWorkspace` refusal that says nothing about entitlements. Measured: `keychain-access-groups` (both
prefixed and unprefixed, ad-hoc and with the real Apple Development identity) and
`com.apple.developer.associated-domains` (2026-08-25). `RuntimeIdentityTest` asserts the keychain key stays
absent so the "obvious fix" cannot be applied to the mystery it would cause.

That associated-domains result is why **universal links cannot be tested on this host at all** — `simctl
openurl` is accepted and no link entry point fires. Use the channel's `onSceneContinueActivity` trigger
instead: it exercises decode → gate → join without OS delivery.

## ⚠️ `simctl launch <dev> <bundle> KEY=VAL` passes ARGV, not environment

It silently does nothing that way. Use `SIMCTL_CHILD_<VAR>`:

```
SIMCTL_CHILD_SNAPSYNC_RIG_PORT=18101 xcrun simctl launch "$DEVICE" app.snapsync
```

`SNAPSYNC_RIG_PORT` is the **only** variable left, and it is read by the rig's hook — a file that does not
exist in a non-rig build. **There are no `SNAPSYNC_*` launch triggers any more**: production Kotlin declares
none and a guard fails the build if one returns. Everything they used to do — join, create, leave, reset,
seed, wipe — is now a channel verb. Load `rig-channel` for the full surface.

## ⚠️ Photo permission: use `applesimutils`, NOT `simctl privacy`

**`xcrun simctl privacy <dev> grant photos app.snapsync` does not work for PhotoKit.** It writes the TCC
row and the app still reads `notDetermined` — measured 2026-08-25 on iOS 26.2, along with two other
shapes that also fail: a direct `sqlite3` write of `auth_value=2, auth_reason=2, auth_version=1` with the
device **shut down**, and `grant all` plus a restart (which reads **`DENIED`**). TCC is not consulted at
*request* time either: asking for access raises the system alert regardless.

The trap is that it looks like it worked. `simctl` prints nothing, the TCC row is really there, and the
app simply behaves as though you never granted anything.

**This works:**

```
brew install wix/brew/applesimutils
xcrun simctl terminate "$DEVICE" app.snapsync
applesimutils --byId "$DEVICE" --bundle app.snapsync --setPermissions "photos=YES"
xcrun simctl launch "$DEVICE" app.snapsync           # reads GRANTED on this launch
```

Verified end to end: the app reads `GRANTED`, the join gate clears, and the simulator reaches
`configResolved: true`.

**Why this matters more than it looks.** Without granted access the join parks in `ExplainAccess`
(`StatusContainerHost.kt:560`), whose only exit is `onRequestPermission` — deliberately **not** wired into
the channel, because on a device it raises an alert only a finger can answer. A simulator has no finger,
so `applesimutils` is what makes a headless join possible at all.

⚠️ **On any `mode=deferred` stall, SCREENSHOT FIRST.** A pending system alert blocks every subsequent
launch and is instantly visible in a screenshot while being invisible in every log.

## Seeding a library

Use the simulator's own path:

```
xcrun simctl addmedia "$DEVICE" shot1.jpg shot2.jpg shot3.jpg
```

The channel's `POST /device/gallery/seed` exists and may well work here, but it is **unmeasured on a
simulator** — say so rather than assuming. What *was* measured (2026-08-09) is that the retired
`SNAPSYNC_SEED_POLICY` launch trigger never finished: it logged `seeding N POLICY-PROBE asset(s)` and
stopped, because the app suspends ~4 s after launch and the async `PHPhotoLibrary.performChanges` never
completed. A channel request may hold the app alive where a launch variable did not — that is the thing to
check, not to presume. `addmedia` needs neither and is the known-good path.

Generate them at **2400×2000** (4.8 MP) so they clear the 3 MP resolution floor in the selection policy.
They land dated ~now, so they are in scope for an event started earlier in the same session.

**The wipe is `xcrun simctl erase`**, not a launch trigger. The device is disposable; erase and reinstall.

## The backend

App Attest does not exist here, so there is no attestation token and the deployed backend refuses every
join — `api/src/dev` is the only thing that fills an absent one. It fills the absent ENROLMENT too: a
`devices` row is created only by a real attestation, and the push-registration write requires one, so
without the rig a simulator could never register for push (and would never recover, because its refresh
returns early rather than attesting).

**Run it ON THE RUNNER.** The repo is already rsync'd there, and deno installs in seconds:

```
curl -fsSL https://deno.land/install.sh | DENO_INSTALL="$HOME/.deno" sh -s -- -y
cd ~/snapsync/api && nohup ~/.deno/bin/deno task dev:local > ~/deno.log 2>&1 &
```

⚠️ **Warm it before you drive the app.** A cold `deno` server takes seconds to load its npm modules on the
first request, and the app's HTTP client gives up at **5 s** — the create then fails with
`Couldn't reach the server` while `curl` from the same machine works, which reads like a broken tunnel and
is not. One `curl` against `/api/v1/events` first is enough.

The simulator shares the host's loopback, so it reaches `http://127.0.0.1:8080/api/v1` with **no tunnel in
the data path**. ATS exempts loopback, so plain HTTP needs no Info.plist exception. The host is stable, so
no rebuild per session — unlike a cloudflared quick tunnel, whose hostname is random each time.

⚠️ **Do NOT reverse-forward the sandbox's rig for real work.** `ssh -R 8080:127.0.0.1:8080` does work, and
it keeps `.localstore` greppable locally — but it rests on the cloudflared quick tunnel, which is not
stable enough: measured 2026-08-25, it dropped repeatedly in one session (four reconnect attempts to get a
shell back), and every drop takes the forward with it. From inside the simulator that is
`Could not connect to the server` on `lo0` — **indistinguishable from a backend crash**. Use it only for
short backend-change loops where you are watching, never for a download or a relaunch measurement.

The oracle then lives on the Mac: `ssh … 'find ~/snapsync/api/.localstore -type f'`.

## Finding the channel's port

🔢 **All simulators share the HOST's loopback**, so a server bound inside a simulator app binds on the
Mac's loopback. `18099` is the device default and two instances would collide — and not quietly: the
second's bind fails while a `curl` reaches the **first** and answers plausibly, reporting the very port
you asked for.

So read the port each instance actually bound, rather than assuming one:

```
DATA="$(xcrun simctl get_app_container "$DEVICE" app.snapsync data)"
PORT="$(cat "$DATA/Documents/rig.port")"
curl -sS --max-time 180 "http://127.0.0.1:$PORT/health"
```

**No port file means the bind failed** — that is the signal, not an inconvenience. Read `debug.log` in the
same `Documents/` for the `Error` line naming the address and port.

For two instances, give each its own port explicitly and confirm each published what you asked for:

```
SIMCTL_CHILD_SNAPSYNC_RIG_PORT=18101 xcrun simctl launch "$DEV_A" app.snapsync
SIMCTL_CHILD_SNAPSYNC_RIG_PORT=18102 xcrun simctl launch "$DEV_B" app.snapsync
```

Everything past `/health` — `/state`, `/logs`, `POST /os/<root>/<member>`, the receipted-vs-202
split, the `onForeground`-before-membership ordering trap — is in `rig-channel` and is identical here.

## Driving an event

There is no launch variable for this any more. Create and join through the channel, exactly as a user does
(`rig-channel` has the full vocabulary):

```
P=$(cat "$(xcrun simctl get_app_container "$DEVICE" app.snapsync data)/Documents/rig.port")
curl -X POST "localhost:$P/user/create?name=SimRig&startsAt=2026-08-24T00:00&endsAt=2026-08-30T00:00"
curl -s "localhost:$P/device/state" | jq '.ui'          # wait for JoiningEvent(eventId, Ready)
curl -X POST "localhost:$P/user/confirmJoin?cutoff=2026-08-24T00:00:00Z&until=2026-08-30T00:00:00Z&direction=upload&saveToAlbum=false"
```

To put a SECOND instance on the same event, drive its channel through the warm universal-link entry —
`POST /os/app/onSceneContinueActivity?arg=<link>` — which takes the same decode → gate → join path a scanned QR
does. `simctl openurl` cannot be used for this (see the entitlement note above).

⚠️ **Never join an event you did not create.**

## Device identity

Each simulator **mints and keeps its own id** in its App-Group container, so two instances are two members
with no operator input, and an id survives a relaunch of its own instance (`via=read` in `debug.log` on the
second launch). `xcrun simctl erase` discards it, which is the intended way to get a fresh member.

Nothing plants it. The store is chosen by compilation target, so on this host the app simply mints into a
file the way it would mint into the Keychain on a device. If the app reports its identity as unavailable,
the build is not signed — the store's message says so and names `scripts/sim-sign`.
