---
name: snapsync-device
description: >-
  SnapSync on the connected iPhone — the project facts that sit on top of the
  global `ios-device` skill: bundle ids and process names, reading the app's and
  the extension's logs, verifying that an event link really delivers and that an
  upload really landed, and the headless per-build loop. Use together with
  `ios-device` whenever a task touches SnapSync on the physical phone: "install
  on the phone", "launch the app", "read debug.log", "test on the SE2", "did the
  upload land", "does the link open the app". To DRIVE a running app — join,
  create, leave, reset, seed, wipe — load `rig-channel`.
---

# snapsync-device — SnapSync on the phone

⚠️ **Load the global `ios-device` skill first.** It owns everything generic, and this skill repeats
none of it: the **device lock** (CodeHydra's global `ios-device` lock — take it with a *background* Bash
call, not under `ch bg`: `ch lock take ios-device "<why>"`; required before any device command, held by
the workspace until `ch lock release ios-device` at the end of device work), the guard, building on the
runner, **signing on Linux**, `install`, launch, restart, screenshots, measured timeouts, and the usbmux
traps. What follows is only what is true of SnapSync.

To build, load `ssh-mac-build` (the SnapSync half: `.ios-device.yml`, deployments, the rig property). To
point a build at a local backend, load `local-backend`. To drive a running app, load `rig-channel`.

What cannot be done headlessly: taps and UI gestures need a signed **WebDriverAgent**
(`developer wda`), and the PhotoKit extension's `process()` timing is OS-owned — a re-provision reliably
triggers an invocation, but you cannot force *when*.

## The facts the global recipes need

| what | value |
|---|---|
| app bundle id | `app.snapsync` |
| extension bundle id | `app.snapsync.BackgroundUpload` |
| app process (`crash pull --match`, `syslog --process-name`) | `SnapSync` |
| extension process | `BackgroundUploadExtension` |
| the SE2's UDID | `00008030-0018703A1A7A402E` (iOS 26.6) |

⚠️ **SnapSync ignores SIGTERM.** A `dvt launch --kill-existing` layers a new instance on the still-alive
old one and sticks on a black launch screen. Always use the global skill's SIGKILL-first restart recipe;
`install` already SIGKILLs before it replaces the app.

## Building for the phone

Build with **`snapsync.rig=true`** in the runner's `~/.gradle` when you intend to drive the app (see
`ssh-mac-build`). ⚠️ **A build without it is undriveable, and that is deliberate.** Production Kotlin
declares no `SNAPSYNC_*` variable, and a guard fails the build if one returns. You can still install and
launch a release build and watch it; you just cannot make it do anything.

Two variables survive, and neither is read by shipped code:

| variable | read by | what it does |
|---|---|---|
| `SNAPSYNC_RIG_PORT` | `:test:rig`'s hook | overrides the channel's bind port (needed per-instance on a simulator, which shares the host's loopback) |
| `SNAPSYNC_FORGE_STATE` | the `SnapSyncForge` target | which forged state that binary renders, for a marketing screenshot |

Both live in build-property-gated source, so a production build contains neither the file nor the read.

**Exercising one uploader alone is a channel call:** `POST /device/uploaders?app=on|off&extension=on|off`
(load `rig-channel`). Both uploaders run by default on ≥26.1 under a full grant; `extension=off` deregisters
the extension so the app's uploader runs alone.

## Reading the logs

The app and extension are separate processes, each writing its **own** verbatim, un-redacted log
(capability `privacy-security`). Each rolls to a `.1` sibling past 10 MB.

```bash
P="uvx --python 3.14 pymobiledevice3"
timeout 15 $P apps pull app.snapsync Documents/debug.log ./debug.log          # the APP's log
```

The extension writes `ext-debug.log` into the shared App Group, which is **not** pullable over USB. On a
rig build, read it through the channel instead — `GET /device/logs?process=extension` (see
`rig-channel`). The channel reads the **current** file only, so a rolled `.1` sibling is not reachable
that way. ⚠️ `apps pull app.snapsync.BackgroundUpload Documents/debug.log` is **dead**: the extension
deletes that stale file on first launch, so the pull fails honestly instead of returning months-old
content.

🚫 **Never debug with `NSLog`**, from Kotlin or Swift: an interpolated format string is redacted
wholesale by os_log and never shows up in syslog. Route diagnostics through Kotlin so they land in
`debug.log`.

⚠️ **`swcd` is NOT visible in the device syslog** (measured: 23,525 lines across an install, zero AASA
activity) — don't retry that.

## Verifying the event link

An invite is an HTTPS **Universal Link** — `https://snapsync.stho.net/join#v=3&d=<base64url>` (capability `join-event`). The payload rides in the **fragment** on purpose: a browser never sends it, so the
`eventId` (which *is* the upload capability) never reaches the backend or its CDN even when someone
without the app opens the link and gets redirected to the App Store.

Two checks run from Linux with **no device**:

```bash
# 1. our origin, THROUGH the pull zone — must be JSON with no redirect
curl -sSI https://snapsync.stho.net/.well-known/apple-app-site-association
# 2. what Apple actually hands a device (it caches, and parse errors show up as a miss)
curl -sS https://app-site-association.cdn-apple.com/a/v1/snapsync.stho.net
```

That second endpoint is the cheap oracle: it 404s until Apple has fetched and **accepted** our AASA, and
200s once it has. It is also why we ship plain `applinks:` with no `?mode=developer` — CDN staleness is
one curl away from being diagnosed rather than an invisible wait.

⚠️ **Apple's own apps are not AASA-wired** — `apps.apple.com` serves an empty file, `maps.apple.com`
404s, `music.apple.com` serves HTML; they are special-cased inside the OS. So an `apps.apple.com` QR is a
**worthless** test target that appears to pass. Test with a real third-party universal link (verify the
domain against the CDN endpoint above first).

Verified on device: the stock **Camera app honors AASA** on a scanned QR, and iOS **delivers the fragment**
to the app. Opening a real link and landing on the event proves the entitlement, the AASA, and fragment
delivery in one observation; a stripped fragment would surface visibly as the invalid-link error, never
silently.

⚠️ **A green AASA proves nothing about delivery.** Both curls above can pass while every link is dead:
iOS matches the AASA, foregrounds the app, and the app drops the URL — indistinguishable from success,
and on an unjoined device the create screen it lands on is the correct resting state. That shipped
(2026-07-16). The link is delivered as an `NSUserActivity` to the **scene** delegate — a SwiftUI
`WindowGroup` is a scene — so `scene(_:willConnectTo:options:)` (app NOT running) and `scene(_:continue:)`
(app running) both carry links. **So does SwiftUI's `.onOpenURL` on the `WindowGroup`, and it is not
optional**: on iOS 18.7.9 the scene delegate's `continue` never fires while the app is already running,
from any source, and `.onOpenURL` is the only path that delivers there (Bugsink `SNAPSYNC-39`/`-43`/`-44`,
builds 681/683/687). It is intermittent on iOS 26.6 (2 of 4), so neither hook is sufficient alone and
both are wired; the duplicates they produce are absorbed by the join gate, which acts on a repeated link
once. `.onContinueUserActivity` is warm-only; `application(_:continue:)` is never called in a SwiftUI
app (re-measured on iOS 18, build 683: zero hits on all three app-delegate continuation callbacks). A
`:test:architecture` guard pins the scene delegate AND the modifier (`EventLinkDeliveryTest`).

**The authoritative on-device check is `debug.log`, not the screen** (spec `sync-status`): read the
`[onOpenUrl]` lines. A **cold** delivery is an `onOpenUrl` sharing a timestamp with
`=== app process start ===`; a **warm** one has no preceding process start. A multi-second gap after a
launch means a *second* scan delivered warm — misreading that gap is how "cold works" was concluded
wrongly the first time. Both cases must appear, exactly once each. Apple's TN3155 exposes approval state
via `swcutil` inside a **sysdiagnose** (`swcutil_show.txt` → `Site/Fmwk Approval: approved`), fetchable
headlessly with `$P developer core-device sysdiagnose` — untried here, but the documented route.

⚠️ **Changing the AASA needs an app REINSTALL.** Devices download it from Apple's CDN at install and
re-check roughly weekly; there is **no invalidation** (TN3155). A changed path/appID does not reach
installed apps on its own.

## Verifying real uploads

By default on-device uploads go to the **deployed HTTPS backend** (the device-facing host baked into
`Deployment.plist`). Confirm one landed by checking the backend's bunny **storage zone** (see
`api/README.md` / `docs/deployment.md`), **not** the app status screen — the screenshot's
status counts are informational, not the authoritative landing check. Connections are HTTPS-only —
default ATS, no `NSAllowsLocalNetworking` exception, on any host.

To test a **backend change** without deploying it, point the device at a local rig instead — load
`local-backend`; there the oracle is `find api/.localstore -type f`.

A signed build's identity is checked on the running app, not in the IPA: `GET /device/state` (rig
channel) reporting a device id proves both bundles claim the same `<TEAM>.app.snapsync.shared` keychain
group. A wrong group reads as **no device id**, and the id is written once and never rewritten.

## The headless per-build loop

Global `ios-device` build (with `snapsync.rig=true`) → `sign` → `install` → `dvt launch app.snapsync` →
join over the channel (`POST /os/app/onSceneContinueActivity?arg=<link>`, using a **fresh event id** you
created, or the reconcile seeds already-stored photos and nothing uploads) → the OS invokes the upload
extension on its own cadence → confirm the objects landed in the backend's storage zone.
