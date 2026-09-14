# crash-reporting Specification

## Purpose

Failures on users' devices used to be invisible: errors reduce into `UiState` and land in an on-device
`debug.log` readable only over USB, so an App Store user's crash or persistently failing upload left no
trace the operator could see. This capability closes that gap: **production builds** of both iOS
processes report crashes, `Error`/`Assert`-severity log events, and breadcrumbs to the operator's
Bugsink instance (Sentry-protocol, errors-only — no tracing, performance, replay, or profiling). The
channel is privacy-bounded by construction: every UUID-shaped token is scrubbed before transmission (an
eventId IS the upload capability; the device id is the GDPR-request correlator), the SDK's random
per-install `user.id` is the one deliberate exception (affected-device counts, linked to nothing), and
the DSN is a build-scope value the deployment resolver bakes into Release archives from a CI secret (capability `deployment-configuration`), emitted only when the build channel names a distributed build — so absence is enforced by the renderer rather than by CI merely not exporting it — a build without it starts no SDK and
opens no connection, which is the entire dev/production split. Capture rides the existing Kermit
logging seam; lifecycle crosses the `DiagnosticsReporter` port both shared compositions start first —
the same port that carries the operator-initiated diagnostic dump's send (capability
`diagnostic-logging`), while automatic capture keeps riding the Kermit seam.
Because the Bugsink instance ingests no dSYMs, delivery parks each build's symbols for offline
symbolication (capability `ios-testflight-delivery`).

Decision record: `changes/archive/2026-07-21-add-crash-reporting`; the event's build and process
identity (and why `dist` is deliberately left to the SDK):
`changes/archive/2026-07-29-add-release-and-process-to-crash-reports`; the port's rename to
`DiagnosticsReporter`, its send operation, and the diagnostic dump's carve-out from the scrub:
`changes/archive/2026-07-29-add-diagnostic-dump`; the carve-out's move onto an explicit
`non-redacted` event tag (and the measurement that a scope tag reaches `beforeSend`):
`changes/archive/2026-07-31-add-bug-report-description`; OS-attributed process termination from platform metrics (`ProcessMetricSource`, the threshold, the
crash-surviving report context, and the measurements behind them): `changes/archive/2026-09-14-add-os-exit-attribution`.

## Requirements

### Requirement: Production builds report crashes and errors to the operator's Bugsink instance

Both iOS processes — the app and the background-upload extension — SHALL initialize the Sentry Kotlin
Multiplatform SDK at process start when (and only when) a crash-reporting DSN is present in the process's
bundle configuration, sending unhandled crashes to the operator's Bugsink instance. Initialization SHALL
cross a `DiagnosticsReporter` port (`:domain` `ports/`) that the **shared composition** starts as its first
act — `snapSyncApp` and `uploadCore` each call `start()`, so both tiers and both processes are covered by
the One-shared-composition law rather than by shell memory, and the world harness observes the same call
through its fake. The port's `start()` SHALL be **idempotent** (the app process composes both entry
points) and a complete **no-op when the DSN is absent or blank**, so a build without a baked DSN — every
dev-sideload, dev-loop, and simulator build — sends nothing and starts no SDK. Features that Bugsink
cannot ingest — tracing, performance, session replay, profiling — SHALL be disabled in the SDK
configuration. `sendDefaultPii` SHALL remain off.

The port SHALL additionally carry a **send operation** for the operator-initiated diagnostic dump
(capability `diagnostic-logging`), which the same adapter seats. The port is named for the need it
serves — reporting a process's diagnostics off-device — rather than for crashes alone, because it now
carries both an automatic and a deliberate channel. The send operation SHALL be a no-op on a build
whose reporting is unconfigured, on the same rule as `start()`.

Capture of **automatic** events SHALL continue not to cross this port: errors reach the channel
through the logging seam (the reporting adapter registers a log writer when it starts), so features
stay free of per-call-site instrumentation. Only the deliberate dump crosses the port explicitly.

#### Scenario: A production build reports a crash

- **WHEN** a Release build with a baked DSN crashes in either process
- **THEN** on next launch of that process the crash event reaches the Bugsink instance, carrying
  `environment=production` plus the release, build number, and process identity required below

#### Scenario: A dev build sends nothing

- **WHEN** a build without a baked DSN launches (dev-sideload, dev build loop, simulator)
- **THEN** the init function no-ops: no SDK starts, no network connection to Bugsink is ever made, and
  the dump send is likewise inert

#### Scenario: Both processes report independently

- **WHEN** the upload extension fails while the app is not running
- **THEN** the extension's own SDK client captures and (possibly on a later invocation) delivers the
  event, without requiring the app process

#### Scenario: A dump crosses the port explicitly

- **WHEN** the operator confirms a diagnostic dump on a build with a configured DSN
- **THEN** the dump is transmitted through the port's send operation, while automatic error capture
  continues to ride the logging seam unchanged

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

### Requirement: Error-severity log lines become events; lower severities become breadcrumbs

A Kermit `LogWriter` registered in both processes SHALL map the existing logging seam onto the reporting
channel: log lines at `Error` or `Assert` severity SHALL be captured as events (with the throwable
attached when one is present); lines at lower severities SHALL be recorded as breadcrumbs attached to
subsequent events. No new `:domain` port is introduced for capture — the Logger seam is the single
capture surface, so every error already reduced into state and logged is reported without
per-call-site instrumentation.

Because this mapping is per-call and unbounded, the severity a call site chooses **is** the decision about
whether an occurrence is reported. A condition that is routine, expected, and self-healing SHALL NOT be
logged at `Error`; the discipline the tree already applies is that the expected outcome logs at `Info` or
`Warn` and only the unexpected path logs at `Error`. No dedupe window, sampling, or suppression is
introduced in the writer: a suppressed error is the silence this capability exists to break.

**The ambient log context SHALL NOT form part of the captured event's message.** The entry-point prefix
the writer adds (`[<entryPoint>] `, capability `diagnostic-logging`) is context about *which trigger was
running*, not about *what went wrong* — but the reporting backend groups by message text, so including it
splits a single cause into one issue per trigger. The captured event SHALL carry the redacted log message
alone, and the entry point SHALL travel as an event **tag** so it stays filterable and visible. The
prefixed text SHALL still be recorded as the accompanying breadcrumb, so the event's own trail is
unchanged. This applies to both capture paths — a captured message and a captured throwable — so one rule
governs, rather than one rule per path.

#### Scenario: A handled upload failure becomes an event

- **WHEN** a feature reduces a failure into state and logs it at `Error` severity with a throwable
- **THEN** an event with that message and throwable reaches Bugsink, with the preceding lower-severity
  log lines attached as breadcrumbs

#### Scenario: Routine log lines alone send nothing

- **WHEN** a process logs only `Verbose`–`Warn` lines and no event is captured
- **THEN** those lines are held as local breadcrumbs only and no event is transmitted

#### Scenario: One cause arrives as one issue regardless of which trigger produced it

- **WHEN** the same `Error`-severity line is logged under several different entry points
- **THEN** the captured events carry the same message text and differ only by their entry-point tag, so
  they group as one issue rather than one issue per entry point

#### Scenario: The entry point remains recoverable from the event

- **WHEN** an event captured from a log line is examined
- **THEN** the entry point that was running is readable from the event's tag, and the prefixed log line
  itself is present among the breadcrumbs

### Requirement: Outgoing text is scrubbed of every UUID-shaped token

Every UUID-shaped token in the message text of an **automatically captured** event or breadcrumb SHALL
be replaced with a fixed redaction marker before it leaves the device — one content-blind rule covering eventIds (which are the upload
capability), device ids (the GDPR-request correlator), membership ids, and any identifier a future log
line introduces. The redaction function SHALL be pure and commonTest-covered. **Deliberate exception**:
the SDK's auto-generated random per-install `user.id` SHALL be kept as-is — it powers per-issue
affected-device counts, is generated by the SDK, and is linked to neither the backend device identity
nor any personal identity. It is the one identifier allowed through, and this exception is intentional —
do not "fix" it into the scrub rule.

The **operator-initiated diagnostic dump** (capability `diagnostic-logging`) SHALL be exempt from this
requirement and transmitted verbatim, **including its message text** — which carries the operator's
written description of the problem, and with it any identifier they quoted. The exemption is narrow
and rests on the difference in consent: automatic events are sent without the user's knowledge, while
a dump is a deliberate, confirmed act whose value is precisely the identifiers a scrub would destroy.

The exemption SHALL be carried by an **explicit marker set on the event** by the sender and consulted
by the scrubbing step, named for the property it claims (that this event is not to be redacted) rather
than for the feature claiming it. It SHALL NOT rest on the payload's placement: the exemption
previously held only because the scrub reached message text but not structured context sections, an
incidental property a later widening of the scrub would have destroyed silently. With an explicit
marker, widening the scrub to cover context sections is safe, because an exempt event is skipped
whatever the scrub covers.

Both halves of the wiring SHALL be **pinned by tests naming the exemption** — that the sender sets the
marker, and that the scrubbing step consults it. Either half missing empties or mangles every future
dump with no failing test and no visible error.

#### Scenario: An eventId in a log line never reaches Bugsink

- **WHEN** an automatically captured error message or breadcrumb contains a UUID (eventId, device id, or any other)
- **THEN** the transmitted payload carries the redaction marker in its place, and no UUID from message
  text appears in the stored event

#### Scenario: Affected-device counts still work

- **WHEN** the same install reports two distinct events
- **THEN** both carry the same random per-install `user.id`, so the issue's affected-count reflects
  distinct installs, while that id maps to no backend identifier

#### Scenario: A dump is exempt

- **WHEN** an operator-initiated dump containing event ids and asset ids is transmitted
- **THEN** those identifiers arrive in full — in its context sections and in its message alike — while
  an error captured on the same build in the same session still arrives redacted

#### Scenario: The exemption is a marker, not a placement

- **WHEN** an event carrying the exemption marker is scrubbed
- **THEN** no part of it is redacted, regardless of which fields the scrubbing step covers

#### Scenario: An automatic event carries no marker

- **WHEN** an event is captured automatically through the logging seam
- **THEN** it carries no exemption marker and is redacted as usual

### Requirement: The privacy policy discloses crash reporting before it ships

The public privacy policy SHALL disclose the crash-reporting channel in the same release that enables
it: a "crash and error reports" entry under what-we-process (legal basis Art. 6(1)(f); identifiers
scrubbed before sending; the random per-install identifier disclosed as not linked to identity),
**Bugsink** listed among the processors, and a bumped last-updated date. The existing
no-analytics/no-advertising/no-tracking statement remains (crash reporting is none of those).

#### Scenario: The policy names the channel and the processor

- **WHEN** the landing page's `#privacy` section is served after this change ships
- **THEN** it lists crash and error reports among the processed data (with the scrubbing and the
  per-install identifier described) and Bugsink among the processors

### Requirement: Crash reports remain symbolicatable offline

The delivery pipeline SHALL retain each `main` build's dSYMs keyed by build number (see capability
`ios-testflight-delivery`), because the Bugsink instance cannot symbolicate native stack traces (no
dSYM ingestion) — so any address-only crash report can be symbolicated offline against the exact
build that produced it.

#### Scenario: An address-only crash can be resolved

- **WHEN** a production crash report shows raw addresses for a given build number
- **THEN** that build's dSYMs are retrievable from CI by build number and resolve the addresses with
  standard offline tooling (e.g. `atos`)

### Requirement: The OS's own account of how this process behaved is reported

The app SHALL obtain reports describing how its own process has been behaving — including how its
previous runs ended — from the platform, through a `ProcessMetricSource` port in `:domain:ports`.

The port SHALL be **named for the need and generic over providers**: it yields reports, and a report
SHALL be an open `Map<String, String>` of fields. It SHALL NOT name the platform framework that
happens to supply it today, and it SHALL NOT require a report to describe a time window — a provider
whose reports have no window simply omits those fields. This keeps the seam swappable: the platform
API in use is deprecated by its vendor in favour of a successor unreachable from this codebase's
language, so the provider **will** be replaced while the contract stays put.

The adapter SHALL obtain field values from the **platform's own serialized representation** rather
than by reading named properties one at a time, so a counter the platform adds later appears without
any code change. The consequence is accepted deliberately: zero-valued counters are omitted from that
representation, so an absent field and a zero field are indistinguishable. This is inert under the
threshold below, which fires on presence, and both readings mean "do not fire".

The adapter SHALL NOT read call-stack data from any report. Measured: one delivery of twelve queued
diagnostic payloads serialized to 15.1 MB, rolled the 10 MB device log, destroyed the log history
preceding it, and blocked for 18 s. Attribution therefore states **what** ended the process, never
**which code** was running.

#### Scenario: A report reaches the app

- **WHEN** the platform delivers a report about this process to a subscribing build
- **THEN** its fields are available to the app as a map, whatever the running OS version chose to
  include, and no call-stack data is read

#### Scenario: The platform adds a field we have never seen

- **WHEN** a future OS reports a counter this codebase does not name
- **THEN** that field appears in the report and reaches the device log and the reporting channel,
  without any change to this codebase

#### Scenario: A provider is replaced

- **WHEN** the platform API supplying reports is replaced by a successor
- **THEN** only the adapter changes; the port, the rule, the thresholds and the channels are untouched

### Requirement: A report becomes a reported event only when a threshold is crossed

A pure, unit-tested rule SHALL turn one report into emissions. The rule SHALL be **total** over the
report — it takes the map and returns what to emit — and SHALL hold every threshold as a **named
constant**, so changing one is a single reviewable edit.

A report SHALL cross the threshold when **any** non-normal process-exit counter is present, or when
reported app-hang durations exceed a named ceiling. The report SHALL NOT be evaluated per counter: the
**report is the unit**, so a crossing produces exactly **one** event carrying a fixed message, with the
crossing reasons carried **alongside the report in the attached context** rather than in the message.
Keeping the message fixed is what makes every attribution group into a single issue whose occurrence
count is the measurement; putting the reasons in the context is what keeps them readable without
splitting that issue. This groups every attribution into a single issue whose occurrence count is
itself the measurement, rather than one issue per counter or per combination of counters.

Thresholds SHALL start deliberately **tight** and be loosened later with data. Counters whose meaning
is not yet established SHALL be included rather than excluded, because inclusion is how their meaning
is learned and exclusion preserves the unknown indefinitely.

This is **not** dedupe, sampling or suppression, which this capability forbids elsewhere: a counter is
not an error, and crossing a threshold is what constitutes one. No emission is ever withheld once the
rule has decided it.

#### Scenario: A report with no non-normal exits

- **WHEN** a report carries only normal process exits and no hang above the ceiling
- **THEN** no event is transmitted, and the report is still recorded on the device log and carried as
  context

#### Scenario: A report with several crossing reasons

- **WHEN** one report carries two different non-normal exit counters
- **THEN** exactly one event is transmitted, naming both reasons in its attached context, and it
  groups with every other attribution event rather than forming its own issue

#### Scenario: A relaunch storm arrives as one event

- **WHEN** a report covers a period in which the process was killed repeatedly for the same reason
- **THEN** the count rides the single event for that report, rather than producing one event per kill

#### Scenario: A threshold is changed

- **WHEN** a threshold is loosened after its distribution is understood
- **THEN** the change is one named constant, and the rule's tests state the new boundary

### Requirement: The latest report is carried on every event this process reports

The reporting adapter SHALL attach the most recent report as **context on the global scope**, so it
rides every subsequently transmitted event — including a crash captured in that process and delivered
on a later launch. The reporting port SHALL carry an operation for attaching it, because the reporting
SDK is confined to a single module and the attribution adapter is not that module.

This SHALL require no persisted state: the context is set when a report is delivered, and the SDK
snapshots the scope onto events captured afterwards.

The attaching operation SHALL **ensure the reporting channel is started** before it attaches, rather
than trusting the caller to have started it. This path can outrun the composition that normally starts
the channel — reports are subscribed for at process start, while the channel starts when the deferred
graph is forced — and the first real attribution event was measured going out **without the `process`
tag**, which is set when the channel starts. Starting is idempotent and inert without a reporting
configuration, so the guarantee costs nothing and no caller can get the ordering wrong.

The attribution event SHALL NOT restate the report it crossed on, because the context already carries
it.

#### Scenario: A crash carries the attribution that preceded it

- **WHEN** a report is delivered, and the process later crashes and is reported on a subsequent launch
- **THEN** the delivered crash carries that report as context, so the crash and the OS's account of
  recent terminations are read together

#### Scenario: A report arrives before the composition has started the channel

- **WHEN** a report is delivered to a process whose deferred graph has not yet been forced
- **THEN** attaching it starts the channel first, so the attribution event carries the `process` tag

#### Scenario: A build with no reporting configuration

- **WHEN** a report is delivered on a build carrying no reporting configuration
- **THEN** attaching the context and transmitting any event are no-ops, on the same rule as the rest
  of this capability, while the device log is written unchanged

### Requirement: Attribution states which process it covers and which it cannot

The reported attribution SHALL identify the process it describes, from the report's own fields rather
than from a value the composition supplies.

This capability SHALL cover the **app process only**. Extension-process terminations are **not
attributable**, and the reason SHALL be stated rather than left as an omission: reports are delivered
on a cadence of roughly a day, while the extension process exists only for the duration of a single
invocation, so a subscriber registered there would never be alive when a report is handed out.

The expiry trigger SHALL be named: this is revisitable if the platform delivers extension reports
through the host app, or if the extension's process lifetime changes shape.

#### Scenario: An event names its process

- **WHEN** an attribution event or context is transmitted
- **THEN** it identifies the reporting process from the report's own fields

#### Scenario: The extension is killed

- **WHEN** the background-upload extension process is terminated by the OS
- **THEN** no attribution is produced for it, and this is a stated boundary rather than a gap

### Requirement: Attribution changes no behaviour

This attribution SHALL be diagnostics only. It SHALL NOT alter behaviour, SHALL NOT introduce
persisted state, SHALL NOT dedupe, sample, suppress or escalate across reports, and SHALL NOT surface
in `UiState`.

If a future change wants to **act** on termination history — sizing work differently after repeated
memory kills, for example — that is a new capability with its own durable authority, not a widening of
this one. Acting on it would rebuild the "clean shutdown marker" this design rejected, with a
reporting delay of roughly a day added on top.

#### Scenario: Repeated kills change nothing

- **WHEN** reports show the process being killed for the same reason on consecutive days
- **THEN** the app's behaviour is identical to a device that has never been killed, and nothing about
  the history is persisted

#### Scenario: Attribution reaches no screen

- **WHEN** any report is delivered
- **THEN** no `UiState` changes and the user sees nothing
