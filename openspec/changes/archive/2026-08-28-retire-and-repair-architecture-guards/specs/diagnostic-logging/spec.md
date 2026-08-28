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
