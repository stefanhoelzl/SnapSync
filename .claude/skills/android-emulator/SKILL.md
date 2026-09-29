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

Only the **rig build** (`-Psnapsync.rig=true`) runs. It composes the real app (`snapSyncProcess`, then
`snapSyncHost`, in `SnapSyncRoot`, built in `Application.onCreate`) over the **mocks**, bar the two systems Android has
real adapters for: the **screen** and its **foreground life**. The choice is fixed — there is no `POST
/device/adapters` here (it answers 409) — and the mocks live in memory, so an exit forgets them. The mocked clock stands
at the **epoch**: an event window you create is a 1970 one.

A build **without** the property compiles, links and **refuses at start** (`app/android/src/prod`): Android has no
adapters for the backend, storage, gallery or push yet. Do not "fix" that by composing mocks into it —
`MockContainmentTest` fails the build.

## One-time setup (already done on this box — check before redoing)

```bash
export ANDROID_HOME=~/.cache/android-sdk ANDROID_AVD_HOME=~/.cache/android-sdk/avd
ls $ANDROID_HOME   # build-tools cmdline-tools emulator platform-tools platforms system-images
cat local.properties   # sdk.dir=<same path> — gitignored; ./gradlew build needs the SDK too
```

From scratch: unzip `commandlinetools-linux-*_latest.zip` into `$ANDROID_HOME/cmdline-tools/latest`, accept the
licenses (`yes | sdkmanager --sdk_root=$ANDROID_HOME --licenses`), install `platform-tools`, `platforms;android-36`,
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
ADB=$ANDROID_HOME/platform-tools/adb scripts/android-smoke     # install, launch, forward, create + join, screenshot
```

`scripts/android-smoke` is exactly what the `android-emulator` CI job runs (`.github/workflows/android.yml`). By hand:

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

## Common tests on the emulator

Every module with a `commonTest` runs it on ART too, as `ios-test` runs it on the iOS simulator (`snapsync.android`
declares the device test). With the emulator up:

```bash
./gradlew connectedAndroidDeviceTest -Psnapsync.androidDeviceTests=true --continue     # all modules, ~7 min cold
./gradlew :domain:model:connectedAndroidDeviceTest -Psnapsync.androidDeviceTests=true  # one module
# reports: <module>/build/reports/androidTests/connected/
```

- The property is **required**: it raises the libraries' minSdk to 30 for that run, because D8 writes a test name's
  spaces only from DEX 040. Without it the dexing fails naming a backtick test.
- An ASCII apostrophe in a backtick test name **cannot be dexed at any API level** — write `’` (U+2019), which DEX
  accepts. The JVM and Kotlin/Native take either, so only this run notices.
- ⚠️ Stop the emulator before a full `./gradlew build` on this box: the two together got the Gradle daemon OOM-killed.

To check R8 over the whole graph: `./gradlew :app:android:assembleRelease -Psnapsync.rig=true`, `zipalign` and
`apksigner sign --ks ~/.android/debug.keystore --ks-pass pass:android` the unsigned APK, install it, run the smoke.
