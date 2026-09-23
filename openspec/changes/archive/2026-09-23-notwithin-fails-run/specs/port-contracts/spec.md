## MODIFIED Requirements

### Requirement: A port contract is a hand-written list of clause values, and the code is its specification

Every contracted port SHALL have exactly one contract in `:test:contracts`' `commonMain`: an explicit list
of **clause values**, each carrying a stable clause id, the state it needs, and a body that exercises the
port and asserts with ordinary `kotlin.test` assertions. The clause list SHALL be the single statement of
the port's obligations — no `openspec/` spec restates a contract's clauses — and SHALL be hand-written:
nothing generates, records, or rewrites a clause or an expectation from observed behaviour.

The same list SHALL feed every runner. On CI, one test per (contract, binding) SHALL run every clause,
collect every outcome, and fail once with the full outcome table when any clause ends in an outcome that
fails a run ("Outcomes are explicit and none is silent"). On a host with no test runner, an in-app runner
SHALL execute the same list.

#### Scenario: A clause is changed
- **WHEN** a clause body is edited in the contract
- **THEN** every binding runs the edited clause on its next run, on CI and on device, with no second list
  to update

#### Scenario: Several clauses fail
- **WHEN** three clauses fail against one binding
- **THEN** the run reports all three in one outcome table rather than stopping at the first

#### Scenario: Expectations are never recorded
- **WHEN** a run against a real implementation observes behaviour no clause asserts
- **THEN** no clause or expectation is created or changed by the run; a person decides whether to write one

### Requirement: Outcomes are explicit and none is silent

Every clause run against a binding SHALL end in exactly one of: `Passed`; `Failed(message)` — the
implementation, or a recorded answer, violates the clause; `NotRunHere(reason)` — the binding cannot reach
the clause's state; `Diverged(message)` — on replay, the clause made an operating-system call the
recording does not hold; `NotWithin(T)` — a bounded wait on an operating-system callback expired. A clause
SHALL NOT end without an outcome. Whether a clause runs SHALL be decided by its binding before the body
executes; a clause body SHALL have no operation that skips it, so an unexercised clause cannot report
`Passed`.

A run SHALL fail when any clause ends `Failed`, `Diverged`, or `NotWithin`, on every binding kind — `Live`,
`Replay`, and `Fake` — and on every runner CI uses. A wait that expired established nothing about the clause:
on a `Live` binding the clause ran nothing, on a `Replay` binding a recorded answer was never delivered, and on
a `Fake` binding the double did not deliver what the clause requires; none of the three is coverage. A
`NotRunHere` SHALL NOT fail a run by itself: whether it is admissible is the question "Every clause runs against
a real implementation on some host" answers. This is the one statement of which outcomes fail a run; every
runner obeys it rather than carrying its own list.

A clause that waits on an operating-system callback SHALL bound the wait on the **real** clock. A clause body
runs under a test scheduler whose virtual clock skips an idle wait at once, so a bound measured there would
expire before a callback delivered on the platform's main queue could arrive, and would report `NotWithin`
for a platform that answered.

#### Scenario: A host cannot produce a clause's state
- **WHEN** a binding cannot enter the state a clause needs
- **THEN** the clause reads `NotRunHere(reason)` without its body running, and never `Passed`

#### Scenario: A replay meets an unrecorded call
- **WHEN** the adapter under replay makes a call its recording does not hold
- **THEN** the clause reads `Diverged`, distinct from `Failed`, and the remedy is to re-record

#### Scenario: A clause waits for the platform to answer on its main queue

- **WHEN** a clause hands a URL to the platform and waits for the completion the platform calls on its main
  queue
- **THEN** the wait is bounded on the real clock, so a platform that answers within the bound reads `Passed`
  or `Failed` on its answer, and one that does not reads `NotWithin`

#### Scenario: A bounded wait expires on CI

- **WHEN** a clause's bounded wait expires on any binding — a platform callback that never arrives on a `Live`
  binding, a recorded answer never delivered on a `Replay` binding, or a delivery a `Fake` binding never makes
- **THEN** the clause reads `NotWithin(T)` and the run fails, with the full outcome table, exactly as it would
  for a `Failed` clause

#### Scenario: A clause cannot run here

- **WHEN** every clause a binding does not reach reads `NotRunHere` and every other clause reads `Passed`
- **THEN** the run passes, and whether those clauses are covered elsewhere is judged by the coverage gate

### Requirement: In-app hosts CI can reach are run live over the rig

A host whose bindings must run inside the app, but which CI can run — the simulator app — SHALL be run
**live** on every push, not recorded: a CI job SHALL build the app under `-Psnapsync.rig=true` for that host,
establish the declared preconditions, launch it, and run every contract in that host's **in-app registry**
through the rig's contract verb, failing on any outcome that fails a run ("Outcomes are explicit and none is
silent"), any refusal, or an empty registry. The
registry SHALL be a source-level list the contract-coverage gate reads. Such a host SHALL NOT be recorded:
record and replay exist for hosts CI cannot run.

One contract MAY be registered for a recorded host and an in-app CI host at once, when its states split
between them — a state that would take the process under test away from the foreground is recorded on the
device, while the rest run live on the simulator app. The rig's contract verb SHALL then run the entry
registered for the host it is running on; an entry for another host SHALL still answer with its own refusal,
so a recording can never be taken on the wrong host by naming it.

Clauses run live in a shared system that cannot be reset between them — a photo library, whose deletions
need a person's confirmation — SHALL be isolated by addresses derived from the clause id (capture dates,
titles, identifiers), and SHALL NOT delete what they seed; the job SHALL start each run from a fresh
simulator.

#### Scenario: A contract is registered for the simulator app

- **WHEN** a `Live` binding naming the simulator-app host is added and registered
- **THEN** the next push's CI job runs its contract in the app and fails on any outcome that fails a run,
  `NotWithin` included

#### Scenario: Two clauses seed the same library

- **WHEN** two clauses each seed assets into the simulator's shared photo library
- **THEN** each reads only the capture-date window derived from its own id, so neither sees the other's
  assets and neither deletes anything

#### Scenario: One contract is registered for the device and the simulator app

- **WHEN** a contract has a recording entry for the device and a live entry for the simulator app, and its
  verb is called in the simulator app
- **THEN** the simulator app's live entry runs and answers its outcome table, and on the device the same verb
  answers the recording
