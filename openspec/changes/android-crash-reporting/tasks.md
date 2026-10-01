# Tasks

## 1. Build facts reach the reporter through CrashOptions (D2)

- [x] 1.1 Add `Platform { IOS, ANDROID }` to `model/` and widen `CrashOptions` with `release`, `environment`, `dist`
      and `tags`; verify `./gradlew :domain:model:jvmTest` passes
- [x] 1.2 Add `platform` and `processId` to the `BuildInfo` port and answer them in `IosBuildInfo` (bundle id, null
      when absent), `AndroidBuildInfo` (package name) and the mocks (`BuildInfoMock` answers `IOS`); verify
      `./gradlew compileIosMainKotlinMetadata` and `:adapter:android:compileAndroidMain` succeed
- [x] 1.3 Make `CrashReporting` take `BuildInfo` and fill `CrashOptions` (release, environment, `platform`/`process`
      tags, `dist` from `diagnostics.buildNumber` on Android only), updating `snapSyncProcess`; add services tests over
      the in-memory reporter asserting the options per platform, including no `process` tag for a null process id;
      verify they pass in `./gradlew build`

## 2. The shared reporter module (D1)

- [x] 2.1 Create `:adapter:generic:sentry` (iosArm64, iosSimulatorArm64, android) with sentry-kmp, excluding
      `sentry-android-ndk`; add it to `ModuleSetTest`'s permitted map; verify `./gradlew :test:architecture:test`
      passes and `./gradlew :adapter:generic:sentry:dependencies` shows no `sentry-android-ndk`
- [x] 2.2 Move `SentryCrashReporter` and its translation into the module's `commonMain`, replacing the three platform
      reads with `CrashOptions` (release, environment, global tags) and keeping the ⚠️ `dist` comment, now scoped to
      when `dist` is null; verify the module compiles for all three targets
- [ ] 2.3 Move `provisionSentryCocoa` and the iOS contract binding (`SentryCrashReporterContractTest`,
      `resetProcessStart`) into the module, drop sentry-kmp from `:adapter:ios:ext-safe`, and point `:app:ios` and
      `:app:ios:extension` at the new module; verify `./gradlew compileIosMainKotlinMetadata` and
      `./gradlew :adapter:generic:sentry:iosSimulatorArm64Test` on a Mac (`ssh-mac-build`) pass
- [ ] 2.4 Add clause `WIRE_BUILD_FACTS_RIDE_THE_EVENT` to `CrashReporterContract` (release, environment, `platform` and
      `process` tags reach the ingest) and bind it on the mock and iOS hosts; verify `ContractCoverageTest` and the iOS
      binding pass
- [x] 2.5 Update `docs/architecture.md`, the CLAUDE.md Modules list (new module; ext-safe no longer holds the crash
      seat) and regenerate `architecture/` with `./gradlew architectureDiagrams`; verify the diagrams test passes

## 3. Android binds the reporter (D3, D5)

- [x] 3.1 Render `sentryDsn` and a `sentryEnvironment` derived from `channel` to a new escaping rendering,
      `build/deployment.json`, in `scripts/resolve-deployment.py` (never to the RAW `deployment.properties`), the DSN
      still only for `channel == release`; verify with a resolver test that `dev` emits no DSN, `release` emits it
      read back intact, and the properties never carry it
- [x] 3.2 Add `BuildConfig.SENTRY_DSN`/`SENTRY_ENVIRONMENT` in `app/android/build.gradle.kts`, hand them to
      `AndroidBuildInfo` (blank DSN → null), and set its `reporterEnvironment`; verify
      `./gradlew :app:android:assembleRelease` builds and the default build's `BuildConfig.SENTRY_DSN` is empty
- [x] 3.3 Bind the shared reporter among the Android root's real adapters and update the prod boot line; carry the
      Android SDK behaviour in the module's `androidMain` manifest (auto-init off, user-interaction breadcrumbs off,
      the performance provider removed) and the shared options (ANR on, screenshot and view hierarchy off); verify on
      the emulator (`android-emulator`) that a build with no DSN starts no SDK and logs the new boot line, and that a
      `release`-channel build with a DSN starts it
- [x] 3.4 Bind `CrashReporterContract` on `ANDROID_EMU` as a device test of the new module, with an Android reset
      (close the SDK, wipe its cache); verify every existing clause passes under `./gradlew androidPlatformTest`
- [x] 3.5 Add clause `RESTART_CACHED_EVENT_KEEPS_ITS_BUILD` (capture as build X with the ingest unreachable, restart as
      build Y, the delivered event says X), bound on `ANDROID_EMU`, `NotRunHere` on iOS; verify it passes under
      `./gradlew androidPlatformTest`, or, if it fails, leave `dist` unset on Android and carry the build number the
      way the clause then proves, recording the outcome in the reporter's KDoc
- [x] 3.6 Update `docs/deployment.md`'s DSN section for Android (the escaping JSON rendering, and that only a delivering
      job resolving `channel = release` arms it); verify it names the same gate the resolver enforces

## 4. The Privacy Policy

- [x] 4.1 Update the automatic crash-and-error-report paragraph of the site's Privacy Policy to name Google Play,
      say "OS version", and name the app freezing until the system closes it; verify `site-build` (the site's build
      task) passes

## 5. Integration

- [ ] 5.1 Run `./gradlew build` and `npx --yes @fission-ai/openspec@1.13.2 validate --specs --strict`; verify both are
      green, then push the branch and verify `ci`, `test (ios)` and `test (android)` pass
