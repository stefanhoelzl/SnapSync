# Proposal: add-crash-reporting

## Why

Failures on real users' devices are invisible today: errors are reduced into `UiState` and written to an
on-device `debug.log` that can only be read over USB — an App Store user's crash or persistent upload
failure leaves no trace the operator can see. With the app distributed via the App Store, the only failure
signal is a one-star review. Crash and error reporting to the operator's Bugsink instance
(Sentry-protocol, EU-hosted at `steho.bugsink.com`) closes that gap.

## What Changes

- Add the Sentry Kotlin Multiplatform SDK to both iOS processes — the app and the upload extension —
  each initializing its own client (mirroring the two `debug.log` writers).
- New `SentryLogWriter` on the existing Kermit seam: `Error`/`Assert` severities become Sentry events
  (with throwable when present), lower severities become breadcrumbs. No `:domain` port; every
  already-logged error is covered.
- Scrubbing: a pure redaction rule replaces **every UUID-shaped token** in event/breadcrumb text before
  send (eventIds — which are the upload capability — device ids, membership ids). The SDK's own random
  per-install `user.id` is deliberately kept (powers "users affected" counts; not linked to identity).
- **Production builds only, enforced by absence**: the DSN is a GitHub secret injected into Release
  archives via the existing `ios-archive` env-injection pattern (like `APS_ENVIRONMENT`). Dev-sideload
  and simulator builds receive no DSN, so the SDK never starts. Bugsink features beyond errors
  (tracing, performance, replay) stay disabled.
- dSYM retention: `main` builds publish their dSYMs as a build-number-keyed workflow artifact, so
  address-only crash reports can be symbolicated offline (Bugsink has no dSYM support yet —
  bugsink/bugsink#20).
- Privacy policy (`backend/src/landing.html`): keep the no-analytics/no-tracking claim, add a
  "crash and error reports" bullet (Art. 6(1)(f), identifiers scrubbed, random per-install id
  disclosed), add **Bugsink** to the processors list, bump the last-updated date.
- Manual follow-up (tasked, not automatable): declare Crash Data/Diagnostics in the App Store Connect
  privacy nutrition label.

## Capabilities

### New Capabilities

- `crash-reporting`: what is captured (crashes, error-severity log events, breadcrumbs), the
  UUID-scrub rule and its deliberate `user.id` exception, production-only gating by DSN absence,
  both-process coverage, and the privacy-policy disclosure the reporting requires.

### Modified Capabilities

- `ios-testflight-delivery`: Release archives additionally bake the `SENTRY_DSN` secret (same
  injection seam as `APS_ENVIRONMENT`; dev/sideload builds keep it empty), and `main` delivery
  retains the archive's dSYMs as a workflow artifact keyed by build number.

(`marketing-site` requires only that a Privacy Policy exists at `#privacy` — its requirements don't
constrain policy content, so the disclosure requirement lives in `crash-reporting`. `diagnostic-logging`'s
debug.log contract is unchanged; the new writer is additive.)

## Impact

- `gradle/libs.versions.toml`: + `io.sentry:sentry-kotlin-multiplatform` (0.26.x; verify Kotlin 2.4.0
  compatibility early — fallback is sentry-cocoa in Swift with a pinned shell-guard exception).
- `:adapter:ios:ext-safe`: SDK dependency, `SentryLogWriter`, `startCrashReportingIfConfigured(dsn)`
  (the null-check lives here — shells stay zero-conditional). No UIKit references, so the
  extension-safety gate stays green.
- `:domain` `model/`: pure UUID-redaction function beside the existing logging infra (commonTest-covered).
- `:app:ios` (`SnapSyncRoot`) and `:app:ios:extension` (`UploadExtensionRoot`): one unconditional init
  call each; `KotlinShellGuardTest` pins updated accordingly.
- `iosApp/` Xcode project: sentry-cocoa via SPM, **statically linked**, both targets (the ssh-mac
  re-sign script assumes no nested dylibs).
- `.github/workflows/ios.yml` + the `ios-archive` composite action: `SENTRY_DSN` secret injection,
  dSYM artifact publication.
- `backend/src/landing.html`: privacy-policy edits.
- `CLAUDE.md` module list: `:adapter:ios:ext-safe` description gains the Sentry writer/init.
