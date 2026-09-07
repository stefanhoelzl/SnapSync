## ADDED Requirements

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

The attribution event SHALL NOT restate the report it crossed on, because the context already carries
it.

#### Scenario: A crash carries the attribution that preceded it

- **WHEN** a report is delivered, and the process later crashes and is reported on a subsequent launch
- **THEN** the delivered crash carries that report as context, so the crash and the OS's account of
  recent terminations are read together

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
