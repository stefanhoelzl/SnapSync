---
name: android-emulator
description: >-
  Run SnapSync's Android rig build on an Android emulator from this Linux box —
  install the SDK under ~/.cache, boot an emulator headlessly, build and install
  the rig APK, forward the control channel's port over adb and drive it. Use for
  "run it on Android", "boot the emulator", "install the Android build", "adb",
  "screenshot the Android app", or anything under app/android or :adapter:android
  that needs the app actually running.
---

# android-emulator — the Android control-channel host

The counterpart of `ios-simulator`, with no Mac: the Android app runs on an emulator on this Linux box (KVM), and the
same control channel (`rig-channel`: `/os`, `/user`, `/device`) is served from inside it. **It needs NO device lock** —
nothing here touches the iPhone. There is no Android phone; real-device measurements wait for the closed test.

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
  `library` (MediaStore — `DCIM` is the member's default gallery), `system-ui`, `wake`, `background-time` and
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
adb reverse tcp:8080 tcp:8080          # the emulator's 127.0.0.1:8080 is now the host's — no 10.0.2.2
# then the choice above with integrity=real and backend=real; the api logs `attest: <id> attested (android, software)`
```

Plain HTTP to `127.0.0.1` is allowed only in the rig build (`test/rig/src/android-hook/res/xml/rig_network_security.xml`,
merged under the property). The emulator's KeyMint attests in SOFTWARE under a per-AVD test root, which only the local
rig's `androidAttestationTrust: any` accepts. The rig's fallback bearer fills a token for a request that carries none,
so a working request proves nothing about attestation — the api's `attest:` log line does.

```bash
curl -sS -X POST localhost:18099/device/adapters/current      # what this launch runs, and what may be real
printf 'screen=real\nlifecycle=real\nclock=real\nfiles=real\ndatabases=real\npreferences=real\nkeychain=real\n' > choice
for s in backend library integrity crash-reporter process-info wake background-time extension-registry \
         upload-queue upload-session downloads links push system-ui; do echo "$s=mock"; done >> choice
curl -sS -X POST --data-binary @choice localhost:18099/device/adapters
adb shell am start -W -n app.snapsync/app.snapsync.android.MainActivity
adb shell run-as app.snapsync find files databases -type f   # the real stores (run-as: debuggable build)
```

A build **without** the property composes every real adapter and starts (`app/android/src/prod`), with no crash
reporter until phase 5. It talks to the RESOLVED deployment (`prod` by default): never join an event with it you did not
create, and `adb shell pm clear app.snapsync` after trying it. Its push service starts only when the deployment names a
Firebase project (`deployments/components/android.json`); without one it logs "gets no push" and runs on.

**Seeding a real library:** `POST /device/gallery/seed?n=&kind=` inserts the app's OWN photos into `DCIM/Camera` (same
kinds as iOS). Never `adb push` a photo to test with: MediaStore hides a shell-owned photo from every other app, so
the app never sees it (measured 2026-09-29). A shell write does still fire the library-change wake, which is how to
cold-start the process through WorkManager. The emulator's AOSP camera saves to `Pictures/` (outside `DCIM`) and
writes no location. Do not "fix" that by composing mocks into it —
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
Then wait: `until [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = 1 ]; do sleep 3; done` (≈25 s).

- ⚠️ **`-gpu swangle_indirect` is not optional here.** The default `swiftshader_indirect`, `guest` and `off` all
  **segfault** (exit 139) seconds into the boot: SwiftShader's GLES JIT jumps into heap memory in a render thread
  (core backtrace in `libGLESv2.so`, `gles_swiftshader/`). Measured 2026-09-29 on emulator 37.1.11 **and** 36.1.9, so
  it is this host, not a release. The emulator logs nothing before dying; the core is in `coredumpctl`.
- ⚠️ Never `pkill -f <pattern>` from the same Bash call whose command line contains the pattern: it kills its own
  shell (exit 144). Stop the emulator with `adb emu kill`.

## Build, install, drive

```bash
./gradlew :app:android:assembleDebug -Psnapsync.rig=true
```

Then install and drive it by hand (`scripts/android-journeys` does the same end to end, over a local api — below):

```bash
adb install -r app/android/build/outputs/apk/debug/android-debug.apk
adb shell am start -W -n app.snapsync/app.snapsync.android.MainActivity
adb forward tcp:18099 tcp:18099
curl -sS localhost:18099/health ; curl -sS localhost:18099/device      # host ANDROID_EMU; 409 = refused verb
curl -sS localhost:18099/device/state
adb exec-out screencap -p > shot.png                                    # then Read it
adb logcat -s SnapSync:V AndroidRuntime:E                               # the whole app log (LogcatSink)
```

Everything past the port is `rig-channel`'s protocol; load it for the verbs. The app's `/os` foreground and background
go through the REAL lifecycle adapter (`AndroidLifecycle.deliverForeground`); every other `/os` verb through its mock.
`/os/photokit-ext/*` is refused: Android has no upload extension. `device/relaunch` is refused: force-stop and start.

## Opening an event link

The activity claims `https://<domain>/join` (the resolved deployment's domain, any port). Nothing verifies the domain
on the emulator — the served `assetlinks.json` names no certificate before Play — so deliver the link to the app
explicitly, quoting the fragment for the device shell. With `links=real` in the adapter choice:

```bash
adb shell "am start -W -a android.intent.action.VIEW -d 'https://127.0.0.1:8080/join#v=3&d=…' app.snapsync"
```

A running app gets it in `onNewIntent` (single-top), a stopped one in `onCreate`; both carried the fragment intact
(measured 2026-09-29).

## Device tests and journeys (what CI runs)

The shared `commonTest` runs on the JVM only. What runs on the emulator is Android's own — `:adapter:android`'s device
tests (the adapters' contract bindings) and the all-real journeys — the `test (android)` and `journeys (android)` jobs
of `ci.yml`:

```bash
./gradlew androidPlatformTest            # boots its OWN managed emulator (pixel6, API 36), runs, tears it down
./gradlew :adapter:android:connectedAndroidDeviceTest   # on the emulator you booted above
# reports: adapter/android/build/reports/androidTests/ ; either way the build serves scripts/transfer-fixture.py
# on the host (build/transfer-fixture.log), reached from the emulator as http://10.0.2.2:8123

./gradlew :app:android:assembleDebug :test:integration:journeysClasses :test:integration:journeysClasspath \
    -Psnapsync.rig=true -Psnapsync.deployment=local && ./gradlew --stop
JAVA_HOME=<a JDK 25> ADB=$ANDROID_HOME/platform-tools/adb scripts/android-journeys   # evidence: build/android-journeys/
```

`androidPlatformTest` needs no emulator of yours — stop yours first, the two together are heavy. The journeys script
needs JDK 25 on `JAVA_HOME` (the journeys compile to 25) and deno; it starts the local api itself.

- The backtick test names' spaces dex only from DEX 040 (API 30) — one reason minSdk is 30. Never lower it below 30
  without renaming every test.
- An ASCII apostrophe in a backtick test name **cannot be dexed at any API level** — write `’` (U+2019), which DEX
  accepts.
- ⚠️ Stop the emulator before a full `./gradlew build` on this box: the two together got the Gradle daemon OOM-killed.

To check R8 over the whole graph: `./gradlew :app:android:assembleRelease -Psnapsync.rig=true`, `zipalign` and
`apksigner sign --ks ~/.android/debug.keystore --ks-pass pass:android` the unsigned APK, install it, and drive it.
