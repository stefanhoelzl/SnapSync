## RENAMED Requirements

- FROM: `### Requirement: Deadline expiry is logged`
- TO: `### Requirement: Operating-system expiry is logged`

## MODIFIED Requirements

### Requirement: Uniform platform-invocation logging

Every platform invocation, app entry point, and background trigger SHALL be logged with a uniform
enter/exit convention recording the entry-point name, its parameters, its result, and its elapsed
duration. This SHALL cover the upload-platform methods, the extension `process()` cycle, the
opportunistic tail's steps and its operating-system stop, the schedulers, the app entry points, the download controller, and the
app-driven upload controller.

The **enter line SHALL precede any decision** the entry point makes, and SHALL record the raw
inputs the platform supplied — including the fields a filter is about to test. An entry point that
declines to act SHALL still name its outcome on exit (spec `module-architecture`, "Absence is never
silent"). Recording only successful paths is what made a reported defect undiagnosable: an event
link that never reached the join gate was indistinguishable from one iOS never delivered, because
the filter that discarded it wrote nothing.

An entry point is a declaration the **platform** calls, and the set is identified by these rules rather
than by a maintained list:

1. every member of a composition-root object invoked from outside that root's own file — which
   covers both Swift→Kotlin doors (the app delegate and scene delegate, and the Compose entry the
   Swift view calls);
2. every overridden member of a class conforming to a platform callback protocol;
3. every observer body registered with a platform notification or change-observer centre.

A declaration reached only from our own Kotlin is **not** an entry point; what distinguishes one is
that the platform is on the other side of the call. Read-model members that presentation polls are
therefore excluded, while the platform's request for the root view is not.

**This obligation is maintained by review, not by a build gate.** The guard that derived the entry-point
set and asserted each was marked and logged has been retired (capability `architecture-guards`): it
enforced diagnosability rather than behaviour, and an unlogged entry point ships correct behaviour. The
consequence is stated rather than left implicit — a new entry point that decides and returns without
logging will not fail any build, and a defect of the shape described above will again be undiagnosable
from a device log.

Where a composition root reaches its process's **inbound port** by delegation (`module-architecture`,
"OS entry points cross an inbound port"), the port's implementation in `compose/` SHALL write that entry's
enter and exit lines, so the obligation sits in code that is covered rather than in the shell; the
`@PlatformEntry` marker SHALL sit on the port's members. The marker is documentation of this obligation,
checked by review; no guard derives or checks its population.

**User taps SHALL be instrumented as entry points too**, decorated where the command bundle is
built (spec `module-architecture`, "Commands cross one door": instances are decorated only in
`compose/`), so that every line in the device log traces to a named trigger.

#### Scenario: An entry point declines to act
- **WHEN** a platform entry point receives a delivery and a filter discards it
- **THEN** the log carries both the enter line with the raw platform inputs and an exit line naming the
  outcome, so "discarded" is distinguishable from "never delivered"

#### Scenario: A new Swift-to-Kotlin door is added
- **WHEN** a new delegate method forwards to a new composition-root member
- **THEN** that member is instrumented with the enter/exit convention as part of the change, and its
  absence is caught in review rather than by a build failure

#### Scenario: A delegated entry is logged by the core

- **WHEN** the OS invokes an inbound-port member on a composition root
- **THEN** the enter line, with the raw inputs, and the exit line naming the outcome come from the port's
  implementation in `compose/`, and the root contributes no logging body of its own


### Requirement: Operating-system expiry is logged

The app SHALL log one line every time the operating system signals that a wake's time is up — a `BGTask`'s
`expirationHandler`, or the expiration handler of a background task begun through the background-time port
(capability `ios-app-shell`, "Time is up is learned only from the operating system") — naming:

- **which signal** fired, and the **entry point** whose wake it ended (the push, the transfer channel, or the
  background task's identifier);
- **what was running** when it fired — the wake's own work or the tail, and the unit in flight (for example the
  tail step and the asset being imported, or the walk) — and whether that unit **completed or was abandoned**
  by the stop (a walk is abandoned; an import completes);
- **what was left**: the work the stop left for a later wake — at least the staged downloads not yet imported,
  whether the top-up ran, and whether the walk ran.

The line SHALL report the operating system's signal, never a deadline of ours; there is none (capability
`ios-app-shell`). A stop that fires silently is indistinguishable from work that completed, so the mechanism that
ends a wake cleanly would be invisible in exactly the dumps that exist to explain it — and this line is also how
the change that removed the app's own deadlines is measured in the field, since its benefit could not be
reproduced on the test device.

Decision record: `changes/own-work-per-wake` (D3, D4; the measurement risk).

#### Scenario: An expiry is attributable

- **WHEN** the operating system signals expiry for a wake
- **THEN** the log records the signal, the entry point it belongs to, the unit that was running and whether it
  completed, and what was left for a later wake

#### Scenario: An expiry during the walk says so

- **WHEN** the operating system signals expiry while the tail's discovery walk is in flight
- **THEN** the line names the walk as abandoned, so a dump distinguishes "no new photos" from "the walk never
  finished"

#### Scenario: No deadline line exists

- **WHEN** a wake's own work or tail takes longer than any former receipt deadline
- **THEN** no line reports a deadline of ours, because no such deadline releases anything

### Requirement: An import that never returns is attributable

Each per-asset photo-library import SHALL be traced with the uniform enter/exit invocation logging, naming
the asset and reporting the duration on exit — so an import that entered and never exited is visible in a
pulled log and in a diagnostic dump, and is distinguishable from one that was never attempted.

This is the primary route by which a never-reporting import becomes visible. Nothing bounds such an import in
time (capability `photo-download`), and no deadline of ours exists to fire on it; the operating-system expiry
line names the unit in flight only if the operating system's signal arrives while it runs, and a process
suspended or killed without one leaves only this entry line.

#### Scenario: A stuck import is identifiable from the log

- **WHEN** an import is entered and its completion never arrives
- **THEN** the log carries that import's entry line naming the asset, with no matching exit line

#### Scenario: An ordinary import reports its duration

- **WHEN** an import completes normally
- **THEN** the log carries matching entry and exit lines for it, the exit carrying the elapsed duration


