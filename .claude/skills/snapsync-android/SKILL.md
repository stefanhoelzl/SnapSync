---
name: snapsync-android
description: >-
  Run SnapSync's Android rig build from this Linux box — on an emulator (SDK under
  ~/.cache, booted headlessly, no lock) or on the A40, the real Android phone
  (under its lock, through the global `device` skill) — build and install the rig
  APK, forward the control channel's port over adb and drive it. Use for "run it
  on Android", "boot the emulator", "try it on the A40", "install the Android
  build", "adb", "screenshot the Android app", or anything under app/android or
  :adapter:android that needs the app actually running.
---

# snapsync-android — the Android control-channel host

The Android app runs on an **emulator** on this Linux box (KVM) — the counterpart of `ios-simulator`, with no Mac —
or on the **A40**, a real Samsung phone on USB. Either way the same control channel (`rig-channel`: `/os`, `/user`,
`/device`) is served from inside it.

| | emulator | A40 |
|---|---|---|
| lock | **none** — it belongs to the workspace that booted it | `A40`, a global CodeHydra lock (load the global `device` skill, `SKILL.md` then `android.md`) |
| adb serial `S` | `emulator-5554` (the first one; `$A devices` lists it) | the A40's, from the `device` skill's `devices.json` |
| host port `H` (→ the app's 18099) | `18199` | `18103` |
| install | `$A -s $S install -r <apk>` | `~/.claude/skills/device/install A40 <apk>` |

🚫 **Every adb command names its device** (`-s $S`, or `ANDROID_SERIAL=$S` for a script or gradle). The `device`
skill's guard denies a bare `adb …` — with a phone attached as well, it would reach whichever device adb picks — and
denies any command on the A40 from a workspace that does not hold its lock. `adb` is not on PATH, and shell state does
not persist between calls, so define the shorthands in **the same call** that uses them:

```bash
A=$ANDROID_HOME/platform-tools/adb; S=emulator-5554; H=18199     # the emulator
```

Never `adb kill-server` (the server is shared by every workspace), and remove your forwards before you leave
(`$A -s $S forward --remove tcp:$H`) — they belong to the server and outlive the call.

## What the app is here

Only the **rig build** (`-Psnapsync.rig=true`) runs. It composes the real app (`snapSyncHost` over
`AppPorts`, in `SnapSyncRoot`, built in `Application.onCreate`) over an **adapter choice**, read at every start
from the adapters file (`rig-channel`'s `device/adapters*`, as on the iOS app host):

- **No file** (a fresh install, or after `POST /device/adapters/clear`): every system mocked but the **screen** and its
  **foreground life**, fresh in memory — an exit forgets them. The mocked clock stands at the **epoch**: an event window
  you create is a 1970 one.
- **A file** may make real only what Android has a real adapter for — today `screen`, `lifecycle`, `clock`, `files`,
  `databases`, `preferences`, `keychain` (the Keystore-sealed secure store), `integrity` (Keystore key attestation),
  `backend` (the real api over OkHttp), `links` (the activity's VIEW intents; `/os` link verbs then deliver through it),
  `library` (MediaStore — `DCIM` is the member's default gallery), `network` (the default-network callback), `system-ui`, `wake`, `background-time` and
  `upload-session` (WorkManager, and in-process uploads). Every other system must be named `mock`: an omitted system reads as `real`,
  and a choice leaving one real is refused. Write it while not joined; the app exits, and you start it again
  (`am start`). A file-chosen launch saves its mocks' state, so it survives a force-stop.

### The real backend: a local api

`backend=real` talks to the build's RESOLVED deployment — `prod` by default, where an Android attestation is refused
(no signing digest is named until Play). Build against the local rig instead and reach it from the emulator's own
loopback (load `local-backend` for the api side):

```bash
./gradlew :app:android:assembleDebug -Psnapsync.rig=true -Psnapsync.deployment=local   # base http://127.0.0.1:8080/api/v2
(cd api && ch bg deno task dev:local)                                                  # run_in_background
$A -s $S reverse tcp:8080 tcp:8080     # the device's 127.0.0.1:8080 is now the host's — no 10.0.2.2, no tunnel
# then the choice above with integrity=real and backend=real; the api logs `attest: <id> attested (android, software)`
```

`adb reverse` works the same over USB, so the A40 reaches the local api with no tunnel either.

Plain HTTP to `127.0.0.1` is allowed only in the rig build (`test/rig/src/android-hook/res/xml/rig_network_security.xml`,
merged under the property). The emulator's KeyMint attests in SOFTWARE under a per-AVD test root, which only the local
rig's `androidAttestationTrust: any` accepts. The A40 attests in its own hardware — `any` accepts that too; what the
api logs for it is not yet measured. The rig's fallback bearer fills a token for a request that carries none,
so a working request proves nothing about attestation — the api's `attest:` log line does.

```bash
curl -sS -X POST localhost:$H/device/adapters/current      # what this launch runs, and what may be real
printf 'screen=real\nlifecycle=real\nclock=real\nfiles=real\ndatabases=real\npreferences=real\nkeychain=real\n' > choice
for s in backend library integrity crash-reporter process-info wake background-time extension-registry \
         upload-queue upload-session downloads links push system-ui; do echo "$s=mock"; done >> choice
curl -sS -X POST --data-binary @choice localhost:$H/device/adapters
$A -s $S shell am start -W -n app.snapsync/app.snapsync.android.MainActivity
$A -s $S shell run-as app.snapsync find files databases -type f   # the real stores (run-as: debuggable build)
```

A build **without** the property composes every real adapter and starts (`app/android/src/prod`), with no crash
reporter until phase 5. It talks to the RESOLVED deployment (`prod` by default): never join an event with it you did not
create, and `$A -s $S shell pm clear app.snapsync` after trying it. Its push service starts only when the deployment names a
Firebase project (`deployments/components/android.json`); without one it logs "gets no push" and runs on.

**Seeding a real library:** `POST /device/gallery/seed?n=&kind=` inserts the app's OWN photos into `DCIM/Camera` (same
kinds as iOS). Never `adb push` a photo to test with: MediaStore hides a shell-owned photo from every other app, so
the app never sees it (measured 2026-09-29). A shell write does still fire the library-change wake, which is how to
cold-start the process through WorkManager. The emulator's AOSP camera saves to `Pictures/` (outside `DCIM`) and
writes no location; the A40's Samsung camera is a real one, saving to `DCIM/Camera` — so its own photos ARE the member's
default gallery, and a `library=real` choice there shares them. Do not "fix" that by composing mocks into it —
`MockContainmentTest` fails the build.

## One-time setup (already done on this box — check before redoing)

```bash
export ANDROID_HOME=~/.cache/android-sdk ANDROID_AVD_HOME=~/.cache/android-sdk/avd
ls $ANDROID_HOME   # build-tools cmdline-tools emulator platform-tools platforms system-images
grep android.home ~/.gradle/gradle.properties   # systemProp.android.home=<same path> — how every worktree's build
                                                # and IDE sync find the SDK (a per-checkout local.properties is lost
                                                # in each new workspace, and an IDE daemon lacks the shell's env)
```

From scratch: unzip `commandlinetools-linux-*_latest.zip` into `$ANDROID_HOME/cmdline-tools/latest`, accept the
licenses (`yes | sdkmanager --sdk_root=$ANDROID_HOME --licenses`), install `platform-tools`, `platforms;android-37` (compileSdk),
`emulator` and `system-images;android-36;google_apis;x86_64`, then
`avdmanager create avd -n snapsync-api36 -k "system-images;android-36;google_apis;x86_64" -d pixel_6`. Gradle fetches
the build-tools it wants itself.

## Boot

```bash
export ANDROID_HOME=~/.cache/android-sdk ANDROID_AVD_HOME=~/.cache/android-sdk/avd
ch bg $ANDROID_HOME/emulator/emulator -avd snapsync-api36 -no-window -no-audio -no-boot-anim -no-snapshot \
  -gpu swangle_indirect -memory 3072 -no-metrics > ~/.cache/android-sdk/emulator.log 2>&1
```

Run it with the Bash tool's `run_in_background: true` — a `&` inside a foreground call dies with that call's shell.
Then wait: `until [ "$($A -s $S shell getprop sys.boot_completed | tr -d '\r')" = 1 ]; do sleep 3; done` (≈25 s).

- ⚠️ **`-gpu swangle_indirect` is not optional here.** The default `swiftshader_indirect`, `guest` and `off` all
  **segfault** (exit 139) seconds into the boot: SwiftShader's GLES JIT jumps into heap memory in a render thread
  (core backtrace in `libGLESv2.so`, `gles_swiftshader/`). Measured 2026-09-29 on emulator 37.1.11 **and** 36.1.9, so
  it is this host, not a release. The emulator logs nothing before dying; the core is in `coredumpctl`.
- ⚠️ Never `pkill -f <pattern>` from the same Bash call whose command line contains the pattern: it kills its own
  shell (exit 144). Stop the emulator with `$A -s $S emu kill`.

## The A40

A real phone, shared by every project: the global `device` skill owns its lock, `connect`, `ask`, `install` and the
Android traps (its `android.md`). The loop is that skill's — build first with no lock, then:

```bash
ch lock take A40 "<why you need the phone>"     # BACKGROUND call, not under ch bg; exits once it is yours
~/.claude/skills/device/connect A40             # background too: it may wait for the user to unlock / allow USB debugging
~/.claude/skills/device/install A40 app/android/build/outputs/apk/debug/android-debug.apk    # every take
```

then the recipes below with `S=<the A40's serial>` and `H=18103`, and at the end `forward --remove`, then
`ch lock release A40`. Nothing carries over between holds: reinstall, and re-check the photo grant and the adapter
choice, every time. What a script cannot see (a permission dialog, the screen) is the `device` skill's `ask`.

What differs from the emulator — none of it measured yet, so check before relying on it:

- **Android version.** The A40 tops out at Android 11 (API 30) — exactly our minSdk. So **no partial photo access**
  (that is Android 14+): the grant is full or none. Read it: `$A -s $S shell getprop ro.build.version.sdk`.
- **The library is real and personal.** Seeding (`/device/gallery/seed`) and wiping write to a real phone's
  `DCIM` — wipe only what you seeded. 🚫 Never join an event you did not create (CLAUDE.md).
- **Samsung**: a locked screen lets `am start` succeed behind the lock screen (`ask` the user to unlock, or
  `$A -s $S shell svc power stayon usb`), and its battery management may defer WorkManager work more than the
  emulator does.

## Build, install, drive

```bash
./gradlew :app:android:assembleDebug -Psnapsync.rig=true
```

Then install and drive it by hand (`scripts/android-journeys` does the same end to end, over a local api — below):

```bash
A=$ANDROID_HOME/platform-tools/adb; S=emulator-5554; H=18199              # the A40: its serial and 18103
$A -s $S install -r app/android/build/outputs/apk/debug/android-debug.apk   # the A40: the device skill's install
$A -s $S shell am start -W -n app.snapsync/app.snapsync.android.MainActivity
$A -s $S forward tcp:$H tcp:18099                                         # the device's host port → the app's
curl -sS localhost:$H/health ; curl -sS localhost:$H/device               # host ANDROID_EMU; 409 = refused verb
curl -sS localhost:$H/device/state
$A -s $S exec-out screencap -p > shot.png                                 # then Read it
$A -s $S logcat -s SnapSync:V AndroidRuntime:E                            # the whole app log (LogcatSink)
$A -s $S exec-out run-as app.snapsync cat files/private/debug.log > debug.log # the same log as a file (FileLogSink) — a bug report's app_log
```

Everything past the port is `rig-channel`'s protocol; load it for the verbs, with `B=http://127.0.0.1:$H`. The app's `/os` foreground and background
go through the REAL lifecycle adapter (`AndroidLifecycle.deliverForeground`); every other `/os` verb through its mock.
`/os/photokit-ext/*` is refused: Android has no upload extension. `device/relaunch` is refused: force-stop and start.

## Opening an event link

The activity claims `https://<domain>/join` (the resolved deployment's domain, any port). Nothing verifies the domain
on the emulator or the A40 — the served `assetlinks.json` names no certificate before Play — so deliver the link to the
app explicitly, quoting the fragment for the device shell. With `links=real` in the adapter choice:

```bash
$A -s $S shell "am start -W -a android.intent.action.VIEW -d 'https://127.0.0.1:8080/join#v=3&d=…' app.snapsync"
```

A running app gets it in `onNewIntent` (single-top), a stopped one in `onCreate`; both carried the fragment intact
(measured 2026-09-29).

## Device tests and journeys (what CI runs)

The shared `commonTest` runs on the JVM only. What runs on the emulator is Android's own — `:adapter:android`'s device
tests (the adapters' contract bindings) and the all-real journeys — `android-build`'s platform tests and the `journeys (android)` job
of `ci.yml`:

```bash
./gradlew androidPlatformTest            # boots its OWN managed emulator (pixel6, API 36), runs, tears it down
ANDROID_SERIAL=emulator-5554 ./gradlew :adapter:android:connectedAndroidDeviceTest   # on the emulator you booted
# reports: adapter/android/build/reports/androidTests/ ; either way the build serves scripts/transfer-fixture.py
# on the host (build/transfer-fixture.log), reached from the emulator as http://10.0.2.2:8123

./gradlew :app:android:assembleRelease :test:integration:journeysClasses :test:integration:journeysClasspath \
    -Psnapsync.rig=true -Psnapsync.deployment=local && ./gradlew --stop
ANDROID_SERIAL=emulator-5554 JAVA_HOME=<a JDK 25> ADB=$ANDROID_HOME/platform-tools/adb scripts/android-journeys
# evidence: build/android-journeys/
```

`androidPlatformTest` needs no emulator of yours — stop yours first, the two together are heavy. The journeys script
needs JDK 25 on `JAVA_HOME` (the journeys compile to 25) and deno; it starts the local api itself. `ANDROID_SERIAL` is
not optional: the guard fences `android-journeys` and denies it without one, and adb itself reads it, so every bare
`$ADB` inside reaches that device. Give `scripts/android-force-stop-check` the same prefix — the guard does not see
inside it, but with a phone attached its bare `adb` fails "more than one device". Both scripts forward the host's 18099 itself — the emulator's
convention is 18199 — and are written for the emulator: they uninstall the app and change grants, and have not been
run on the A40.

- The backtick test names' spaces dex only from DEX 040 (API 30) — one reason minSdk is 30. Never lower it below 30
  without renaming every test.
- An ASCII apostrophe in a backtick test name **cannot be dexed at any API level** — write `’` (U+2019), which DEX
  accepts.
- ⚠️ Stop the emulator before a full `./gradlew build` on this box: the two together got the Gradle daemon OOM-killed.

The journeys run the rig **release** build — R8 and resource shrinking as the store build runs them — so a class R8
removed that is reached only by name fails a journey, not the store. `assembleRelease -Psnapsync.rig=true` signs it with
the debug key (`apk/release/android-release.apk`), so it installs as the debug build does; it is not debuggable, so
`run-as` does not reach its stores — use the debug build for that. The plain release stays unsigned (the store's upload
key is its delivery's).
