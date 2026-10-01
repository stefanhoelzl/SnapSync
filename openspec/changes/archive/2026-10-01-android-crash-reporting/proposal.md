# Proposal

## Why

Android is a supported platform and is about to ship through Google Play (programme phase 5), but an Android build
reports nothing when it crashes: its composition binds an inert reporter and its build carries no reporting
destination. The operator would learn about Android failures only from store reviews. The `privacy-security`
promise that governs automatic failure reports is also written for Apple distribution only ("the App Store or
TestFlight", "iOS version"), so an Android build that reported would already be outside the contract.

## What Changes

- Builds distributed through **Google Play** report crashes and errors automatically, to the same operator
  service and project as iOS, with the same identifier scrubbing, size bounds and diagnostic dump. A report says
  which platform and which process it came from.
- On Android, an app that **freezes until the system closes it** is reported, as the counterpart of a crash.
  Nothing else is added: no tap or gesture trail, no screenshot, no view hierarchy, no performance data.
- A report names the **build it crashed in**, even when it is delivered after the app has been updated.
- Every other Android build (development, sideload, emulator, the CI branch builds) reports nowhere, exactly as
  a non-distributed iOS build does.
- The Sentry translation that today lives in the iOS adapter moves to ONE shared adapter module used by both
  platforms, so there is still only one place where "nothing unshaped leaves" is enforced. The reporter reads no
  platform API: the release, environment, platform and process come from the build's own description, through
  the crash-reporting service.
- The site's Privacy Policy describes automatic failure reports for both stores (required by `privacy-security`
  in the same release as the change to what leaves the device).

No breaking change for iOS: the iOS reports keep their release, environment, `process` tag and crash-time build
number; they gain a `platform` tag.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `privacy-security`: "Automatic failure reports are minimal and anonymous" names Google Play beside the App
  Store and TestFlight as the distributed builds that report, says "OS version" instead of "iOS version", and
  names the app freezing until the system closes it as a failure that is reported.

## Impact

- **Code**: a new adapter module `:adapter:generic:sentry` (iOS and Android targets) holding the reporter moved
  out of `:adapter:ios:ext-safe`; `model/`'s crash options and a platform vocabulary; the `BuildInfo` port gains
  the platform and process; `CrashReporting` fills the options from it; `IosBuildInfo`, `AndroidBuildInfo` and
  the mocks answer the new members; the Android prod adapter set binds the reporter; the Android manifest turns
  off the SDK's own auto-start.
- **Build and deployment**: the deployment resolver renders the DSN and the channel for Gradle, still gated on a
  distributed channel. No Android job sets that channel until the Play delivery job exists (phase 5d), so in
  this change no Android build actually carries a DSN.
- **Dependencies**: sentry-kmp 0.27.0's Android artifact brings sentry-android 8.41.0; its native-crash module
  (`sentry-android-ndk`) is excluded.
- **Tests**: `CrashReporterContract` gains an Android emulator binding, a clause for the reported release,
  environment and tags, and a clause for the crash-time build number; the iOS binding moves with the reporter.
- **Architecture**: `ModuleSetTest`'s permitted map, `docs/architecture.md`, the CLAUDE.md Modules list and the
  generated `architecture/` diagrams.
- **Site**: the Privacy Policy's automatic-failure-report paragraph.
- **Out of scope**: R8 keep rules (5b), Play delivery and the R8 mapping artifact (5d), the rest of the Privacy
  Policy's Android gaps (it names only Apple for push and integrity today; a Play-listing concern, 5g).
