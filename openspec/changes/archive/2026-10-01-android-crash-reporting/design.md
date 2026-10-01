# Design

## Context

See proposal.md for why. The current state this design starts from:

- **The reporter is iOS-only.** `SentryCrashReporter` (`:adapter:ios:ext-safe`, iosMain) is a translation over the
  Sentry KMP SDK. Three of its inputs are platform reads: the environment (`bakedSentryEnvironment()`), the release
  (`CFBundleShortVersionString`) and the `process` tag (the main bundle id, absent in the simulator test executable,
  where no tag is set). The rest — the `Sentry.init` options, the `beforeSend`/`beforeBreadcrumb` translation, the
  capture/breadcrumb/context/dump calls, the process-wide idempotence — is plain Kotlin over the KMP API.
- **Every decision is already in the core.** `CrashReporting` (`:domain:services`) owns the DSN gate and the
  handlers; `model/Crash.kt` owns the scrub, the bounds and the dump. `CrashOptions` carries only the DSN and the
  breadcrumb limit. `BuildInfo` already carries `appVersion`, `dsn` and `diagnostics.reporterEnvironment`.
- **Android binds nothing.** `ProdPlatformAdapters` hands the inert `NoCrashReporter` (its boot line says so);
  `AndroidBuildInfo` answers `dsn = null` and `reporterEnvironment = "none"`.
- **The DSN is gated by the channel in the resolver.** `scripts/resolve-deployment.py` renders `sentryDsn` to the
  JSON and the plist only, and only when `channel == release`. `channel` renders to the xcconfig and the plist only;
  the plist's `sentryEnvironment` is derived from it (`production`/`development`). Gradle's
  `build/deployment.properties` receives neither. In `ci.yml`, only `ios-build` sets `SNAPSYNC_CHANNEL` and
  `SENTRY_DSN`; `android-build` builds the rig variant and sets neither.
- **The dependency is already chosen.** sentry-kmp 0.27.0 publishes an Android AAR that depends on
  `io.sentry:sentry-android` 8.41.0 (which aggregates `sentry-android-core` and `sentry-android-ndk`) and
  `io.sentry:sentry` 8.41.0. Our Android library targets use AGP 9's `com.android.kotlin.multiplatform.library`
  through the `snapsync.android` convention plugin.
- **The contract.** `CrashReporterContract` (`:test:contracts`) runs over the real SDK against a loopback ingest;
  today it is bound on `IOS_SIM_KEXE` (`SentryCrashReporterContractTest`, ext-safe iosTest, with sentry-cocoa's
  dynamic framework provisioned for the Gradle-linked test executable) and on the in-memory mock. No clause asserts
  the release, environment, tags or build number.

## Goals / Non-Goals

**Goals:**
- One reporter for both platforms, reading no platform API; every build fact comes in through `CrashOptions`.
- iOS reports unchanged except for the added `platform` tag; the iOS contract binding stays green.
- An Android report carries the build number it crashed in, proven on the emulator.
- The Android DSN gate works the same way as the iOS one: absent unless a CI job resolves a distributed channel.

**Non-Goals:**
- Actually arming an Android build. No Android job resolves a distributed channel until the Play delivery job
  (phase 5d) exists; that job sets `SNAPSYNC_CHANNEL`/`SENTRY_DSN` the way `ios-build` does.
- R8 keep rules (5b), the R8 mapping artifact and `/bugsink` retracing (5d).
- Native (C/C++) crash capture on Android.
- The Privacy Policy's other Android gaps (push and integrity name Apple only) — Play listing work (5g).

## Decisions

### D1. A shared module `:adapter:generic:sentry`, targets iOS and Android

The reporter moves, with its translation and the contract binding, into a new adapter module with `iosArm64`,
`iosSimulatorArm64` and `android` targets (no `jvm`: nothing on the JVM links a real reporter). Its `commonMain`
holds everything the iOS file holds today minus the three platform reads; `androidMain` holds only the
Android-only SDK options (D5); `iosMain` holds nothing platform-reading.

- The iOS roots (`:app:ios`, `:app:ios:extension`) take the reporter from the new module; `:adapter:ios:ext-safe`
  drops its sentry-kmp dependency and the `provisionSentryCocoa` task moves to the new module, because the
  Gradle-linked simulator test executable that needs the framework is now this module's.
- `:app:android` binds it among the root's real adapters (`SnapSyncRoot`), as `:app:ios` does; a rig build still
  chooses real or mock per its adapter file.
- `ModuleSetTest`'s permitted map, `docs/architecture.md`, the CLAUDE.md Modules list and `architecture/` change
  with it.

*Alternative rejected:* a copy in `:adapter:android`. The translation is where "nothing unshaped leaves" is enforced
(an event that reaches the SDK hooks before handlers are registered is dropped); two copies would drift.

### D2. Build facts through `BuildInfo` → `CrashOptions`

`CrashOptions` widens to `dsn`, `release`, `environment`, `dist` (nullable, D4), `tags` and `maxBreadcrumbs`.
`CrashReporting` fills it from the `BuildInfo` it already reads:

| option | from |
|---|---|
| `release` | `appVersion` (only when non-blank, as today) |
| `environment` | `diagnostics.reporterEnvironment` |
| `tags["platform"]` | new `BuildInfo.platform`: `Platform.IOS` / `Platform.ANDROID` in `model/` (no JVM value; the JVM mocks answer `IOS`, since the harness plays a phone) |
| `tags["process"]` | new `BuildInfo.processId: String?` — the bundle id (app or `.appex`) on iOS, the package name on Android, `null` where there is none; no tag when null |

`CrashReporting` then takes a `BuildInfo` instead of a bare `dsn`, so its constructor has one fewer loose value.
The reporter sets the tags on the global scope right after `Sentry.init`, where the `process` tag goes today.

- **iOS:** `IosBuildInfo` answers `platform = IOS` and `processId` from `NSBundle.mainBundle.bundleIdentifier`.
  `diagnostics.reporterEnvironment` falls back to `"?"` where today's reporter falls back to `"development"`; both
  come from the plist's `sentryEnvironment`, which is always present when a DSN is, so the fallback is unreachable
  on a build that starts the channel.
- **Android:** `AndroidBuildInfo` answers `platform = ANDROID`, `processId` from the context's package name, and
  `dsn`/`reporterEnvironment` from `BuildConfig` fields the root hands in (D3).

*Alternative rejected:* each platform's reporter reads its own facts (today's iOS shape). That keeps a platform read
inside the shared module and makes the reporter untestable without a bundle.

### D3. The Android DSN gate: resolver → Gradle JSON → `BuildConfig` → `BuildInfo.dsn`

- *Changed during implementation.* The resolver's `deployment.properties` is a RAW rendering — values interpolated
  with no escaping, which its inventory reserves for reviewed literals, because the DSN once reached such a grammar
  (the xcconfig) and four TestFlight builds shipped mute. So the DSN does not go there. The resolver gains a new
  rendering, `build/deployment.json` (JSON escapes), carrying `sentryDsn` — emitted only when `channel == release`
  — and a `sentryEnvironment` derived from the same channel, exactly as the plist does. `channel` and `sentryDsn`
  name the new rendering; `deployment.properties` stays reviewed literals.
- `app/android/build.gradle.kts` reads that JSON and emits `BuildConfig.SENTRY_DSN` (empty when absent) and
  `BuildConfig.SENTRY_ENVIRONMENT` as ESCAPED Java string literals; the root hands both to `AndroidBuildInfo`, which
  answers a blank DSN as `null`.
- With no DSN, `CrashReporting.start()` returns before touching the reporter, as on iOS: nothing starts and no
  connection is opened.
- No CI job changes in this change. Today every Android build resolves `channel = dev`, so the gate is closed on
  every one of them; 5d opens it on its delivering job only.

### D4. The crash-time build number is proven, not assumed

iOS leaves `dist` unset because sentry-cocoa applies the dist option at send time, which would re-stamp a cached
crash with the newer build. sentry-java applies options in its event processors at capture time, before an event
is cached, so on Android setting `dist` should stamp the crash-time build. That is a belief until measured:

- New clause `RESTART_CACHED_EVENT_KEEPS_ITS_BUILD`: start the channel as build X with the ingest unreachable, capture
  an event (it is cached), close the SDK, start it again as build Y with the ingest reachable, and assert the event
  that arrives says build X.
- It runs on the Android emulator host in one process. A real crash → relaunch cannot run inside an instrumented
  test (the crash kills the test), so the clause proves the cache/re-start behaviour, not an actual process death.
- `CrashReporting` fills `dist` from `diagnostics.buildNumber` on Android and leaves it `null` on iOS, keeping the
  existing ⚠️ reasoning in place for iOS. The clause is `NotRunHere` on the iOS host.
- If the clause fails on Android, `dist` stays unset there too, and the build number rides in a way the clause
  proves instead (for example inside the release); the spec does not change either way.

### D5. sentry-android's own behaviour

- The module's own Android manifest (so it travels with the reporter into every app that links it) carries
  `io.sentry.auto-init=false` and `io.sentry.breadcrumbs.user-interaction=false`, which sentry-android reads at init,
  and removes sentry-android's `SentryPerformanceProvider` (app-start timing, for tracing — performance monitoring
  the spec rules out). Measured: sentry-kmp's manifest already removes the SDK's init provider, so auto-init was off
  anyway — the meta-data keeps it true should that change; sentry-kmp's own context provider stays, and its Android
  `init` needs it (verified: the contract runs over it on the emulator).
- ANR reporting on (the SDK default): the freeze the modified spec now names.
- Off: screenshot and view-hierarchy attachments and default PII, as on iOS, through the KMP options the shared
  `commonMain` sets (the KMP layer maps them to sentry-android). The KMP failed-request option has no Android mapping;
  sentry-android captures failed requests only through its OkHttp integration, which is not linked.
- Release-health sessions off on BOTH platforms (*added during implementation, at the user's call*). The KMP default
  sends one per launch whether or not anything failed — a usage record the spec's "no usage tracking" rules out,
  which Bugsink drops anyway. Not a contract clause: sentry-android opens a session only for an app in the
  foreground, so the device-test process never shows one either way (measured: the clause passed with sessions on).
  Measured instead on the emulator with a `release`-channel build: with sessions on, a launch cached `session.json`
  and a `session` envelope; off, neither.
- `sentry-android-ndk` excluded from the dependency: SnapSync ships no native code, it adds native libraries per
  ABI, and its frames would need native symbol files we do not produce.

### D6. The contract on Android

`CrashReporterContract` gets an `ANDROID_EMU` binding as a device test of the new module, its own reset (close the
SDK, wipe its cache directory) mirroring iOS's `resetProcessStart`, and the loopback ingest in the instrumented
process. Besides D4's clause, a new `WIRE_BUILD_FACTS_RIDE_THE_EVENT` clause asserts that the release, environment,
`platform` tag and `process` tag from `CrashOptions` reach the ingest — on both hosts, which turns today's belief
about the `process` tag into a measurement wherever a process id exists.

## Risks / Trade-offs

- [Moving the iOS reporter breaks TestFlight crash reporting; every merge uploads a build] → the iOS binding moves
  with the reporter and runs in `test (ios)`; `ios-build`'s DSN readback is untouched; `WIRE_BUILD_FACTS_RIDE_THE_EVENT`
  now covers the release and environment on iOS too.
- [sentry-kmp 0.27.0 does not compile or link on the AGP 9 KMP library target] → settled by the first compile; the
  fallback is sentry-kmp's JVM/Android artifacts consumed directly in `androidMain`, still in the one module.
- [sentry-kmp's Android `init` needs an application context and gets it from a content provider] → verify that
  turning off `auto-init` leaves that provider running; otherwise pass the context in through the root.
- [R8 strips SDK classes on the store build] → out of scope (5b); the SDK ships consumer keep rules, noted for 5b.
- [The build-number clause is a cache/re-start proof, not a process death] → accepted; the same limitation iOS's
  beliefs carry, recorded in the reporter's KDoc.
- [No Android build is armed by this change] → accepted and stated; 5d arms it and is where the end-to-end check
  (a Play build's report reaching Bugsink) belongs.

## Migration Plan

Nothing to migrate: no stored state changes. Rollback is a revert; an Android build carries no DSN until 5d, so
reverting before then changes nothing a user can observe.
