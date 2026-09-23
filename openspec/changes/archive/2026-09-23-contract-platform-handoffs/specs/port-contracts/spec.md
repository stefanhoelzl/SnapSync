## MODIFIED Requirements

### Requirement: Outcomes are explicit and none is silent

Every clause run against a binding SHALL end in exactly one of: `Passed`; `Failed(message)` — the
implementation, or a recorded answer, violates the clause; `NotRunHere(reason)` — the binding cannot reach
the clause's state; `Diverged(message)` — on replay, the clause made an operating-system call the
recording does not hold; `NotWithin(T)` — a bounded wait on an operating-system callback expired. A clause
SHALL NOT end without an outcome. Whether a clause runs SHALL be decided by its binding before the body
executes; a clause body SHALL have no operation that skips it, so an unexercised clause cannot report
`Passed`.

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

### Requirement: In-app hosts CI can reach are run live over the rig

A host whose bindings must run inside the app, but which CI can run — the simulator app — SHALL be run
**live** on every push, not recorded: a CI job SHALL build the app under `-Psnapsync.rig=true` for that host,
establish the declared preconditions, launch it, and run every contract in that host's **in-app registry**
through the rig's contract verb, failing on any `Failed` outcome, any refusal, or an empty registry. The
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
- **THEN** the next push's CI job runs its contract in the app and fails on any `Failed` outcome

#### Scenario: Two clauses seed the same library

- **WHEN** two clauses each seed assets into the simulator's shared photo library
- **THEN** each reads only the capture-date window derived from its own id, so neither sees the other's
  assets and neither deletes anything

#### Scenario: One contract is registered for the device and the simulator app

- **WHEN** a contract has a recording entry for the device and a live entry for the simulator app, and its
  verb is called in the simulator app
- **THEN** the simulator app's live entry runs and answers its outcome table, and on the device the same verb
  answers the recording
