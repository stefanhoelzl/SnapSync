## RENAMED Requirements

- FROM: `### Requirement: The upload producers are never both started`
- TO: `### Requirement: The upload transitions stop in-flight work only at a leave`

## MODIFIED Requirements

### Requirement: The upload transitions stop in-flight work only at a leave

A `:test:architecture` guard SHALL pin the invariants of `upload-lifecycle` that the compiler cannot. Both
uploaders may now run at once (`upload-lifecycle`, "Both uploaders may run; an overlap is a duplicate, never a
loss"), so the risk is no longer two writers — it is a transition that stands an uploader down and orphans its
in-flight work, or a registration write the OS cannot take. The guard follows the risk to the three places it
lives:

- **The registration fact's cells.** The guard SHALL drive the pure registration fact
  (`extensionRegistrable`) over **every** combination of OS facts, permission, and the control channel's
  switch, asserting that no combination answers true on an OS below iOS 26.1. A wrong cell leads to the
  registration selector where it does not exist, and the process aborts.
- **The admission gates.** The guard SHALL drive both processes' admission answers — the app's (from the
  permission and the control channel's switch) and the extension's (from the permission alone; it cannot read
  the switch) — over every combination.
- **The transitions.** The guard SHALL drive sequences of the transitions — join, re-provision of the joined
  event, reconfigure (in both directions), permission change (in both `GRANTED` ↔ `LIMITED` directions, and to
  and from no usable access), launch, leave, and the switch being set or cleared — over a fake registration that
  refuses every write under `LIMITED` and whose read is grant-dependent, and a fake app-driven engine.

After every step it SHALL assert that the extension is never registered below iOS 26.1; that no registration
write is attempted under a non-`GRANTED` grant; that **no transition deregisters except a leave or switching the
extension off**; that **no transition cancels transfers except a leave**; and that no enable bypasses the
disable → enable toggle. That a re-provision of the joined event reaches no transition at all is the provision
flow's own test (its `Stay` branch only saves the config).

"No duplicate job starts in a normal sequence" is a property of the cycle, not of the transitions, and SHALL be
asserted by the cycle's own tests (two cycles over one ledger, run one after the other: the second creates
nothing for a key the first recorded `REQUESTED`), not by this guard.

The guard SHALL NOT be retired on the grounds that two writers are now allowed. What it catches moved, not
vanished: a transition that deregisters or cancels on a revoke, a reconfigure or a re-scan wipes in-flight work
that nothing repairs any more, because the demote and the stranded rules that used to repair it are gone.
Decision record: `changes/both-uploaders-active` (D11).

#### Scenario: A transition that stops in-flight work fails the build
- **WHEN** a transitions change makes a join, a reconfigure, a permission change or a launch
  deregister the extension or cancel the app's transfers
- **THEN** the guard fails the build

#### Scenario: A registration fact true below iOS 26.1 fails the build
- **WHEN** any combination of OS facts, permission and switch answers that the extension may be registered on
  an OS whose platform API for it does not exist
- **THEN** the guard fails the build

#### Scenario: A registration write under a partial grant fails the build
- **WHEN** a transitions change attempts a registration write while photo access is not `GRANTED`
- **THEN** the guard fails the build

#### Scenario: A bare enable fails the build
- **WHEN** a transitions change registers the extension without the leading disable
- **THEN** the guard fails the build

#### Scenario: A re-provision that reaches the upload transitions fails the build
- **WHEN** a change makes a re-provision of the joined event reach the upload transitions (so it could read or
  write the registration)
- **THEN** the provision flow's own test fails the build

#### Scenario: A switch that leaves the extension registered fails the build
- **WHEN** a change stops the switch from triggering the registration reconcile, so switching the extension off
  under `GRANTED` leaves it registered
- **THEN** the guard fails the build
