## ADDED Requirements

### Requirement: A trigger flow never outlives its own run

A `flow/` class SHALL NOT declare a `CoroutineScope` parameter or field, and every lambda parameter it
accepts whose return type is `Unit` SHALL be `suspend`. Its entry point SHALL be `suspend` and SHALL
return only when the work it coordinates has finished; concurrency inside a flow SHALL be expressed with
structured concurrency so that fan-out is preserved while the entry point still awaits its children.

The rule is drawn at `Unit`-returning lambdas because those are the only ones that can detach: a lambda
returning a value must produce it synchronously, so a flow's reads (`() -> String?`, `() -> Boolean`) are
unaffected. It deliberately covers effects that happen to be synchronous today — a `BGTaskScheduler`
submit does not suspend — because the flow cannot see which of its `Unit` lambdas the composition backed
with a detached launch, and neither can a gate.

A flow exists to order the work an OS callback triggered, and its caller is a shell that must report
completion back to the operating system. A flow that detaches work returns before that work starts, so
the shell's report is a false statement about work it never observed — and the platform is entitled to
suspend the process on the strength of it.

Both doors matter. Removing the scope alone is insufficient: a non-suspend `() -> Unit` effect lambda
can only detach, so whatever the composition places behind it escapes the flow's lifetime while the
zone gate stays green.

#### Scenario: A flow declares a scope

- **WHEN** a `flow/` class gains a `CoroutineScope` constructor parameter
- **THEN** the zone gate fails, naming the file

#### Scenario: A flow takes a non-suspend Unit lambda

- **WHEN** a `flow/` class gains a lambda parameter returning `Unit` that is not `suspend`
- **THEN** the zone gate fails, because whatever the composition puts behind it can only be detached

#### Scenario: A flow's value-returning reads are unaffected

- **WHEN** a `flow/` class declares a lambda parameter returning a value rather than `Unit`
- **THEN** the gate passes, because a lambda that must produce a value cannot detach its work

#### Scenario: Fan-out is preserved without detachment

- **WHEN** a flow coordinates several independent effects that previously ran concurrently
- **THEN** they still run concurrently, and the flow's entry point returns only once all of them have
  finished
