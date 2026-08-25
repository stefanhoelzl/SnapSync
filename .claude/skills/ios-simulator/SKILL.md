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
- **No OS-driven PhotoKit upload tier.** The OS does not invoke the upload extension here at all, so
  uploads do not happen on this host yet — the extension-shaped second process is where they arrive.
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
  BACKGROUND_UPLOAD_URL_BASE=http://127.0.0.1:8080/api/v1 \
  build

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

Every `SNAPSYNC_*` trigger silently does nothing that way. Use `SIMCTL_CHILD_<VAR>`:

```
SIMCTL_CHILD_SNAPSYNC_CREATE_EVENT="$payload" xcrun simctl launch "$DEVICE" app.snapsync
```

## ⚠️ Photo permission: `grant photos` does NOT hold, and the failure looks like a boot hang

`xcrun simctl privacy <dev> grant photos app.snapsync` leaves the app still raising the system
full-access alert — which then sits **modally** and blocks *every subsequent launch* at
`MainViewController(mode=deferred)`. Five consecutive launches read exactly like "the app hangs on boot".

**RULE: on any `mode=deferred` stall, SCREENSHOT FIRST.** The alert is instantly visible and invisible in
every log.

What works:

```
xcrun simctl terminate "$DEVICE" app.snapsync
xcrun simctl shutdown "$DEVICE"
xcrun simctl boot "$DEVICE" && xcrun simctl bootstatus "$DEVICE" -b
xcrun simctl privacy "$DEVICE" grant all app.snapsync
xcrun simctl launch "$DEVICE" app.snapsync
```

## Seeding a library

`SNAPSYNC_SEED_POLICY` is **unusable here**: it logs `seeding N POLICY-PROBE asset(s)` and never
finishes, because the app suspends ~4 s after launch and the async `PHPhotoLibrary.performChanges` never
completes. Use the simulator's own path instead:

```
xcrun simctl addmedia "$DEVICE" shot1.jpg shot2.jpg shot3.jpg
```

Generate them at **2400×2000** (4.8 MP) so they clear the 3 MP resolution floor in the selection policy.
They land dated ~now, so they are in scope for an event started earlier in the same session.

**The wipe is `xcrun simctl erase`**, not a launch trigger. The device is disposable; erase and reinstall.

## The backend

App Attest does not exist here, so there is no attestation token and the deployed backend refuses every
join — `api/src/dev` is the only thing that fills an absent one.

**Run it ON THE RUNNER.** The repo is already rsync'd there, and deno installs in seconds:

```
curl -fsSL https://deno.land/install.sh | DENO_INSTALL="$HOME/.deno" sh -s -- -y
cd ~/snapsync/api && nohup ~/.deno/bin/deno task dev:local > ~/deno.log 2>&1 &
```

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

Everything past `/health` — `/state`, `/logs`, `/triggers`, `POST /trigger/<name>`, the receipted-vs-202
split, the `onForeground`-before-membership ordering trap — is in `rig-channel` and is identical here.

## Device identity

Each simulator mints and keeps its own id in its App-Group container, so two instances are two members
with no operator input, and an id survives relaunch of its own instance. `xcrun simctl erase` discards it,
which is the intended way to get a fresh member.

If the app reports its identity as unavailable, the build is not signed: the store says so and names
`scripts/sim-sign` in the message.
