## MODIFIED Requirements

### Requirement: Ambient entry-point context prefix

Every log line SHALL carry a `[<entryPoint>]` prefix naming the outermost entry point that triggered
the work, so downstream engine, HTTP, and download lines trace back to their trigger. The prefix
SHALL NOT include a process token (the file identifies the process). The ambient mechanism SHALL sit
behind `:domain`'s `ports/LogScope` seam: platform-free code drives the injected `LogScope`
(defaulting to `LogScope.NoOp` off-device), and the ambient context the device-log writers read
(`LogContext`, driven via `IosLogScope` and `IosThreadLogScope`) SHALL live in `:adapter:ios:ext-safe`
beside those writers — `:domain` holds no global mutable state for it (spec `module-architecture`,
"State and authority"; migration step 8 C1).

An entry point SHALL claim the ambient context in one of two ways:

- **Process-wide** (the default, `IosLogScope`): the prefix reaches every thread for the duration of
  the invocation, so a trigger's work keeps its prefix across the thread hops of asynchronous work.
- **Thread-scoped** (`IosThreadLogScope`): the prefix reaches only lines logged on the thread the
  entry point was called on. An entry point SHALL use it only when its body does not suspend and
  launches no work whose lines should inherit the prefix. Both MetricKit subscriber callbacks SHALL
  claim thread-scoped.

A thread-scoped claim SHALL take precedence on its own thread over a process-wide claim held
elsewhere. Outermost wins across both kinds: on a thread holding a thread-scoped claim, any further
enter SHALL claim nothing.

User-tap entry points SHALL use a distinct context namespace from platform callbacks, so a reader
can tell which side initiated the work without consulting the source.

The mechanism's accepted inaccuracy SHALL be stated rather than inherited. Platform entry points are
**not** delivered serially with the rest of the process's work: measured on 2026-09-14, a MetricKit
delivery arrived while launch-time work was still logging. So a **process-wide** claim labels every
concurrent line that has no entry point of its own — launch-time work, and a user tap arriving inside a
platform invocation, are both instances. This is accepted for a dev-only diagnostic log. The
thread-scoped claim exists so an entry point that can be exact is exact.

#### Scenario: Downstream line inherits the trigger
- **WHEN** a silent push triggers `onSilentPush`, which drives a download reconcile
- **THEN** the reconcile's log lines are prefixed `[onSilentPush]`

#### Scenario: Outermost entry point wins
- **WHEN** an entry point that is already within an active entry-point context invokes a nested instrumented seam
- **THEN** the nested seam's lines keep the outer entry point's prefix rather than overwriting it

#### Scenario: A thread-scoped claim does not reach other threads
- **WHEN** a MetricKit delivery holds a thread-scoped claim while launch-time work logs on another thread
- **THEN** the delivery's own lines are prefixed `[didReceiveMetricPayloads]` and the launch-time lines
  carry no prefix from it

#### Scenario: A nested process-wide seam cannot escape a thread-scoped claim
- **WHEN** an entry point holding a thread-scoped claim reaches a seam instrumented with the process-wide claim
- **THEN** the seam's lines carry the outer entry point's prefix, and no line on another thread gains it

#### Scenario: A process-wide claim mislabels concurrent un-entered work
- **WHEN** a process-wide entry point holds the ambient context while work with no entry point of its own logs
- **THEN** that work's lines carry the entry point's prefix, which is the stated inaccuracy rather than a
  defect

#### Scenario: A tap during background work is mislabeled
- **WHEN** a user tap is instrumented while a process-wide platform invocation already holds the ambient context
- **THEN** the tap's lines carry the platform invocation's prefix, and the platform invocation's own
  lines remain correctly attributed
