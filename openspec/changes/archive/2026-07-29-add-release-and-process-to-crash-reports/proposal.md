## Why

A crash report reaching the operator's Bugsink instance cannot today say **which version** produced
it or **which of the two processes** it came from. The `crash-reporting` spec already promises the
first — it asserts every event carries "the release (`app.snapsync@<marketing>+<build>`)" — and that
promise has been **false since the capability shipped**: the stored event `SNAPSYNC-1` carries
`release = null`. The cause is measured, not guessed: sentry-kmp's Apple layer assigns
`cocoaOptions.releaseName = kmpOptions.release` unconditionally
(`SentryOptionsExtensions.apple.kt:31`), so our unset value **clobbers** the bundle-derived default
sentry-cocoa had already computed (`SentryOptions.m:178`). The second — app vs background-upload
extension — was never in the contract at all, so a triage session has to infer the reporting process
from the stacktrace.

The build number is **already present and correct** (`dist = "519"` on that same event), and this
change deliberately does not touch it — for a reason that is easy to get backwards and expensive to
get wrong (see below).

## What Changes

- **The reporting adapter sets `options.release`** from the process bundle's
  `CFBundleShortVersionString`, assigned only when non-blank. Because
  `SentryCrashIntegration.m:233-244` writes `options.releaseName` into the SentryCrash `userInfo`,
  the value is baked into a crash report **at crash time**, so a crash delivered after an app update
  still reports the version it actually crashed on.
- **Every event carries a `process` tag** whose value is the reporting process's bundle identifier
  (`app.snapsync` or `app.snapsync.BackgroundUpload`), set on the global scope at `start()`.
  sentry-cocoa persists scope tags into fatal events, so it survives a crash.
- **`options.dist` is deliberately NOT set**, and the spec now says so explicitly. This is the
  counterintuitive half: `dist` is the only one of the two fields whose options value **overwrites**
  rather than fills (`SentryClient.m:747` is unguarded, where `:739` for release is guarded).
  Leaving it unset is what lets the converter fall back to the crash report's own `app_build`
  (`SentryCrashReportConverter.m:158`) — i.e. what makes `dist` **crash-time** and therefore makes
  the `dsyms-<dist>` lookup correct. Setting it would silently attribute a crash to whatever build
  happened to be installed at delivery time.
- **The `crash-reporting` spec's release claim is corrected** to state the marketing version the
  build carries, without naming a version format — the format is `ios-testflight-delivery`'s
  contract, and duplicating it there is exactly how the current false claim arose.
- **The `/bugsink` skill surfaces `release` and the `process` tag** in its drill-in view (dev
  infrastructure, non-gating, no spec). Drill-in only: both fields live on the **event**, and the
  issue objects the list view reads carry neither.

Not breaking: no shipped behavior changes for users, no payload field is removed, and a build
without a baked DSN still starts no SDK.

## Capabilities

### New Capabilities

(none)

### Modified Capabilities

- `crash-reporting`: the release requirement is corrected from a false, format-naming claim to the
  marketing version the build carries; a new requirement pins the `process` tag; a new requirement
  pins that `dist` is left to the SDK **and why**, so the asymmetry is not "fixed" into a defect.

## Impact

- **Code**: `adapter/ios/ext-safe/src/iosMain/kotlin/app/snapsync/logging/SentryCrashReporting.kt`
  only — two assignments inside `start()`. No new port, no `:domain` change, no new dependency, no
  composition-root change (all three `SentryCrashReporting()` construction sites are untouched,
  because the process identity derives itself from the bundle rather than being passed in).
- **Specs**: `openspec/specs/crash-reporting/spec.md`.
- **Dev infrastructure**: `.claude/skills/bugsink/SKILL.md`.
- **Not touched**: `ios.yml`, `ios-appstore-promote.yml`, `Config.xcconfig`, the versioning scheme,
  the `dsyms-<build>` contract, the scrubbing rules, and `SentryLogWriter`'s severity mapping.
- **Verification is post-merge.** Dev builds bake no DSN, so nothing local or ssh-mac can exercise
  this path; the next real Bugsink event confirms all three fields at once.
- **Bugsink side effect**: today every event lands in the empty release (`«no version»`, so
  `project.has_releases` is false and the release UI is dark). Setting a release lights it up, and
  because `MARKETING_VERSION` only advances when a `vX.Y` tag appears, releases map one-to-one onto
  shipped App Store versions — giving `is_resolved_by_next_release` the meaning "fixed in the next
  version I ship".
