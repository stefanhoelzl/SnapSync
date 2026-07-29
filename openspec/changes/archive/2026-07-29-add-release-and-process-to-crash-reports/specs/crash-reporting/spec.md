## MODIFIED Requirements

### Requirement: Production builds report crashes and errors to the operator's Bugsink instance

Both iOS processes — the app and the background-upload extension — SHALL initialize the Sentry Kotlin
Multiplatform SDK at process start when (and only when) a crash-reporting DSN is present in the process's
bundle configuration, sending unhandled crashes to the operator's Bugsink instance. Initialization SHALL
cross a `CrashReporting` port (`:domain` `ports/`) that the **shared composition** starts as its first
act — `snapSyncApp` and `uploadCore` each call `start()`, so both tiers and both processes are covered by
the One-shared-composition law rather than by shell memory, and the world harness observes the same call
through its fake. The port's `start()` SHALL be **idempotent** (the app process composes both entry
points) and a complete **no-op when the DSN is absent or blank**, so a build without a baked DSN — every
dev-sideload, ssh-mac, and simulator build — sends nothing and starts no SDK. Features that Bugsink
cannot ingest — tracing, performance, session replay, profiling — SHALL be disabled in the SDK
configuration. `sendDefaultPii` SHALL remain off.

#### Scenario: A production build reports a crash

- **WHEN** a Release build with a baked DSN crashes in either process
- **THEN** on next launch of that process the crash event reaches the Bugsink instance, carrying
  `environment=production` plus the release, build number, and process identity required below

#### Scenario: A dev build sends nothing

- **WHEN** a build without a baked DSN launches (dev-sideload, ssh-mac, simulator)
- **THEN** the init function no-ops: no SDK starts, no network connection to Bugsink is ever made

#### Scenario: Both processes report independently

- **WHEN** the upload extension fails while the app is not running
- **THEN** the extension's own SDK client captures and (possibly on a later invocation) delivers the
  event, without requiring the app process

## ADDED Requirements

### Requirement: Every reported event carries the build's marketing version as its release

The reporting adapter SHALL set the SDK's release to the **marketing version the running build
carries**, read from that process's own bundle, and SHALL do so only when that value is present and
non-blank — a blank release is worse than none, because it creates an empty release record that
looks like a real one. This spec deliberately names **no version format**: the format is
`ios-testflight-delivery`'s contract, and restating it here is what made the previous version of this
requirement false and kept it false. The build number is carried separately (see below), so release
identifies the **version line** and the build number identifies the **build** within it.

Setting the release explicitly is required rather than incidental: the Kotlin Multiplatform SDK
assigns the underlying release unconditionally from its own options, so leaving it unset **clears**
the value the native SDK derives from the bundle, and the event ships with no release at all.

#### Scenario: A reported event names the version it came from

- **WHEN** any event — a crash, or an `Error`-severity log line — is reported by a build with a baked DSN
- **THEN** the transmitted payload's release is that build's marketing version, and is neither absent
  nor empty

#### Scenario: A crash delivered after an app update still reports the version it crashed on

- **WHEN** a crash is captured on one build, the device updates to a newer build, and the cached
  report is delivered on a later launch
- **THEN** the reported release is the version of the build that **crashed**, not the version of the
  build that delivered it

#### Scenario: A build with no marketing version reports no release

- **WHEN** the running process's bundle carries no marketing version, or a blank one
- **THEN** no release is set, and the event carries whatever the SDK would otherwise have derived —
  never an empty-string release

### Requirement: Every reported event names the process that produced it

Both iOS processes report to one Bugsink project, so an event SHALL identify which of them produced
it. The reporting adapter SHALL attach a `process` tag whose value is the **reporting process's own
bundle identifier**, derived at runtime from that process's bundle rather than passed in by the
composition root — the app process constructs the adapter at more than one site, so a hand-supplied
identity could disagree with itself silently, while a bundle-derived one cannot. The tag SHALL be set
such that it is carried by fatal events as well as handled ones.

#### Scenario: An app-process event is distinguishable from an extension event

- **WHEN** the app process and the background-upload extension each report an event
- **THEN** each event carries a `process` tag holding that process's own bundle identifier, so the
  two are distinguishable without inspecting the stack trace

#### Scenario: A crash carries the process tag

- **WHEN** a process crashes and the report is delivered on a later launch
- **THEN** the delivered event still carries the `process` tag

### Requirement: The build number is the SDK's crash-time value and is never overridden

The reporting adapter SHALL NOT set the SDK's `dist` option. This omission is **deliberate and
load-bearing**, not an oversight: `dist` is the one field whose option value **overwrites** the
event's existing value at send time, whereas the release option only fills an absent one. Leaving
`dist` unset is therefore what allows a crash report's own recorded build number — captured when the
crash happened — to reach Bugsink. Setting it would stamp every cached crash with whichever build was
installed at delivery time.

This matters beyond tidiness: crash symbolication resolves a build's dSYM artifact by the reported
build number (capability `ios-testflight-delivery`), so an overwritten value would silently resolve a
crash against a **different build's** symbols, producing plausible but wrong frames with nothing
signalling the mismatch.

#### Scenario: A crash delivered after an app update reports the build it crashed on

- **WHEN** a crash is captured on one build, the device updates to a newer build, and the cached
  report is delivered on a later launch
- **THEN** the reported build number is that of the build that **crashed**, so its dSYM artifact
  resolves the crash's own addresses

#### Scenario: The build number is present without being configured

- **WHEN** any event is reported by a build with a baked DSN
- **THEN** the payload carries the build number the SDK resolved, and the reporting adapter has set
  no `dist` option to produce it
