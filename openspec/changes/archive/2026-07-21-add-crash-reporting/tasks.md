# Tasks: add-crash-reporting

## 1. Dependency viability (gate for everything else)

- [x] 1.1 Add `io.sentry:sentry-kotlin-multiplatform` (0.27.0 — newer than proposed 0.26.x) to
  `gradle/libs.versions.toml` and `:adapter:ios:ext-safe`; `compileIosMainKotlinMetadata` passes under
  Kotlin 2.4.0 (SDK built with 2.1.21; klib forward-compat holds). `./gradlew build` re-verified in 3.3.
- [x] 1.2 sentry-kmp 0.27.0 pins **sentry-cocoa 8.58.2** (buildSrc Config.kt at tag 0.27.0); recorded
  in design.md and as `sentry-cocoa` in `libs.versions.toml` for task 4.1.

## 2. Scrubbing (pure, commonTest-first)

- [x] 2.1 Add the pure UUID-redaction function to `:domain` `model/` beside the logging infra: any
  UUID-shaped token in a string → fixed marker. commonTest coverage: plain UUIDs, UUIDs embedded in
  URLs/paths, multiple per line, near-misses left intact (runs on JVM + iosSimulatorArm64).

## 3. Port + adapter (`:domain` `ports/` + `:adapter:ios:ext-safe`) — revised to a port during apply

- [x] 3.1 Implement `SentryLogWriter` (Kermit `LogWriter`): `Error`/`Assert` → `captureMessage`/
  `captureException` (throwable attached; message travels as an error breadcrumb beside it);
  `Verbose`–`Warn` → breadcrumb. All outgoing text routed through the 2.1 redactor.
- [x] 3.2 (Revised — operator decision during apply.) `ports/CrashReporting { start() }` in
  `:domain`, **required** in `AppPorts` + `UploadPorts`; `snapSyncApp`/`uploadCore` start it first
  (idempotent — the app process composes both). `SentryCrashReporting` in ext-safe: process-level
  dedupe, DSN/environment from the bundle, `sendDefaultPii` off, failed-request capture off,
  `beforeSend`/`beforeBreadcrumb` scrubbing, SDK-default `user.id` kept, `Logger.addLogWriter` on
  start. `InMemoryCrashReporting` fake in `:adapter:generic:fake`; world passes it at both
  composition sites; the three iOS roots pass the Sentry adapter.
- [x] 3.3 `./gradlew build` green: extension-safety, ports→model, FakeHonesty, MixedPortImpl, shell
  guards, and the diagrams gate (regenerated `architecture/di.md`).

## 4. Xcode + shells

- [x] 4.1 sentry-cocoa added via SPM (`XCRemoteSwiftPackageReference`, `exactVersion` 8.58.2 — the
  1.2 pin) on both targets; the `Sentry` product is SPM-static (the dynamic product is separately
  named `Sentry-Dynamic`), so the re-sign loop's no-nested-dylibs assumption holds. CI mac proves
  resolution + link (nothing on Linux can).
- [x] 4.2 DSN bundle plumbing: `SENTRY_DSN` (empty) + `SENTRY_ENVIRONMENT` (development) defaults
  in `Config.xcconfig`, `$(…)`-substituted keys in both targets' Info.plists; the adapter reads its
  own process's bundle.
- [x] 4.3 (Folded into 3.2's port wiring.) Roots pass `SentryCrashReporting()` into the port
  bundles; `Logger.setLogWriters` lines unchanged; shell guard tests pass unmodified.
- [x] 4.4 On-device verification (SE2, ssh-mac loop, 2026-07-21): DSN-injected Debug IPA + a temp
  (never-committed) `SNAPSYNC_SENTRY_TEST` hook forced one Error line (two UUID literals) and one
  SIGABRT. **Operator confirmed in Bugsink**: the Log Message event shows `eventId=‹uuid›
  deviceId=‹uuid›` (scrub verified), the SIGABRT crash event arrived, plus one WatchdogTermination
  (an artifact of the dev loop's foreground SIGKILLs, not a bug). Control build without the
  override: both Info.plists carry an EMPTY `SENTRY_DSN`, app runs normally, nothing reports.
  Findings: the SPM `Sentry` product links STATICALLY into both binaries (nm-verified; no load
  command), but Xcode also embeds an unreferenced dynamic `Frameworks/Sentry.framework` — dead
  weight; the ssh-mac re-sign runbook needs one extra inside-out step signing it (CLAUDE.md
  updated). Extension-side reporting rides the identical port/composition path; its OS-scheduled
  invocation was not separately forced.

## 5. CI delivery (spec delta: ios-testflight-delivery)

- [x] 5.1 `SENTRY_DSN` repository secret created (`gh secret set`, verified via `gh secret list`).
- [x] 5.2 Injected via a new `sentry-dsn` input on the `ios-archive` composite (paired with
  `SENTRY_ENVIRONMENT=production`); `ios.yml` passes it only when `BUILD_CONFIG == 'Release'`, so
  the dev-IPA path and ssh-mac keep the empty Config.xcconfig default.
- [x] 5.3 `ios-deliver` (main-only by construction) publishes `dsyms-<run_number>` from the
  unpacked archive, retention 90 days (platform max — longer-lived versions need a promote-time
  parking spot; risk recorded in design.md).

## 6. Privacy policy (spec: crash-reporting)

- [x] 6.1 Edit `backend/src/landing.html` `#privacy`: add the crash-and-error-reports bullet
  (Art. 6(1)(f), identifiers scrubbed, random per-install id disclosed), add Bugsink to the
  processors list, bump the last-updated date. Keep the no-analytics/no-tracking sentence.

## 7. Docs + wrap-up

- [x] 7.1 Update `CLAUDE.md`: `:adapter:ios:ext-safe` module line gains the Sentry writer/init; note
  the production-only DSN posture next to the APS_ENVIRONMENT note.
- [x] 7.2 Manual: operator declared Crash Data / Diagnostics in the App Store Connect
  privacy nutrition label (no CLI covers it).
- [x] 7.3 `./gradlew build` green (all architecture gates), `npx --yes @fission-ai/openspec@1.5.0
  validate --specs --strict` green.
