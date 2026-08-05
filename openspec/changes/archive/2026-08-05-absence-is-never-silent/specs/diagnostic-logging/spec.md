## MODIFIED Requirements

### Requirement: Uniform platform-invocation logging

Every platform invocation, app entry point, and background trigger SHALL be logged with a uniform
enter/exit convention recording the entry-point name, its parameters, its result, and its elapsed
duration. This SHALL cover the upload-platform methods, the extension `process()` cycle, the
background-pump triggers, the schedulers, the app entry points, the download controller, and the
app-driven upload controller.

The **enter line SHALL precede any decision** the entry point makes, and SHALL record the raw
inputs the platform supplied — including the fields a filter is about to test. An entry point that
declines to act SHALL still name its outcome on exit (spec `module-architecture`, "Absence is never
silent"). Recording only successful paths is what made a reported defect undiagnosable: an event
link that never reached the join gate was indistinguishable from one iOS never delivered, because
the filter that discarded it wrote nothing.

The set of platform entry points SHALL be **derived, never hand-enumerated** (spec
`module-architecture`, "Commands cross one door"), by rules over the source rather than by a
maintained list:

1. every member of a composition-root object invoked from outside that root's own file — which
   covers both Swift→Kotlin doors (the app delegate and scene delegate, and the Compose entry the
   Swift view calls);
2. every overridden member of a class conforming to a platform callback protocol;
3. every observer body registered with a platform notification or change-observer centre.

A declaration reached only from our own Kotlin is **not** an entry point; what distinguishes one is
that the platform is on the other side of the call. Read-model members that presentation polls are
therefore excluded, while the platform's request for the root view is not.

**User taps SHALL be instrumented as entry points too**, decorated where the command bundle is
built (spec `module-architecture`, "Commands cross one door": instances are decorated only in
`compose/`), so that every line in the device log traces to a named trigger — a platform callback or
a tap — and an unattributed line is itself a signal.

Severity SHALL be chosen so the trail survives its own volume: entry points that fire once per
platform event log at `Info`; entry points that fire once per item — per-asset library-change
callbacks, per-task transfer callbacks — log at `Debug`, so a single large import cannot flush the
crash reporter's bounded breadcrumb window (capability `crash-reporting`) or roll the size-bounded
log before it is read.

#### Scenario: Enter and exit are logged
- **WHEN** an instrumented entry point runs to completion
- **THEN** an enter line records the entry-point name and parameters, and an exit line records the result and the elapsed duration in milliseconds

#### Scenario: Failure is logged with duration
- **WHEN** an instrumented entry point throws
- **THEN** an exit line records the error and the elapsed duration

#### Scenario: The entry is recorded before the decision
- **WHEN** a platform entry point receives an input it will immediately filter out
- **THEN** the enter line — carrying the raw inputs the filter tests — is already written, and the
  exit line names the outcome that discarded it

#### Scenario: An absent entry line is unambiguous
- **WHEN** a device log shows no entry line for a platform callback the user believes occurred
- **THEN** that absence means the platform did not call the app, rather than being ambiguous
  between non-delivery and a silent discard

#### Scenario: A per-item callback does not flood the trail
- **WHEN** an import creates many assets and the platform's change observer fires once per mutation
- **THEN** those entries are recorded at `Debug`, leaving the `Info` trail one line per platform
  event

#### Scenario: A user tap is attributable
- **WHEN** a user tap drives durable work
- **THEN** its lines carry a tap-scoped entry-point context, so the log distinguishes work the user
  initiated from work the platform initiated without reading the source

### Requirement: Ambient entry-point context prefix

Every log line SHALL carry a `[<entryPoint>]` prefix naming the outermost entry point that triggered
the work, so downstream engine, HTTP, and download lines trace back to their trigger. The prefix
SHALL NOT include a process token (the file identifies the process). The ambient mechanism SHALL sit
behind `:domain`'s `ports/LogScope` seam: platform-free code drives the injected `LogScope`
(defaulting to `LogScope.NoOp` off-device), and the process-global ambient context the device-log
writers read (`LogContext`, driven via `IosLogScope`) SHALL live in `:adapter:ios:ext-safe` beside
those writers — `:domain` holds no global mutable state for it (spec `module-architecture`, "State
and authority"; migration step 8 C1).

User-tap entry points SHALL use a distinct context namespace from platform callbacks, so a reader
can tell which side initiated the work without consulting the source.

The mechanism's accepted inaccuracy SHALL be stated rather than inherited: the ambient context is a
process-global with outermost-wins semantics, justified on the grounds that the platform delivers
its entry points serially per process. **User taps are not serial with background work**, so a tap
arriving inside an in-flight platform invocation inherits that invocation's prefix. The mislabeling
therefore falls on the tap, never on the platform work whose trail matters most, and this is
accepted for a dev-only diagnostic log.

#### Scenario: Downstream line inherits the trigger
- **WHEN** a silent push triggers `onSilentPush`, which drives a download reconcile
- **THEN** the reconcile's log lines are prefixed `[onSilentPush]`

#### Scenario: Outermost entry point wins
- **WHEN** an entry point that is already within an active entry-point context invokes a nested instrumented seam
- **THEN** the nested seam's lines keep the outer entry point's prefix rather than overwriting it

#### Scenario: A tap during background work is mislabeled, not the reverse
- **WHEN** a user tap is instrumented while a platform invocation already holds the ambient context
- **THEN** the tap's lines carry the platform invocation's prefix, and the platform invocation's own
  lines remain correctly attributed
