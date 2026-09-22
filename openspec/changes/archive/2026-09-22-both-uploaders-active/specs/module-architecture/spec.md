## MODIFIED Requirements

### Requirement: Rules in features, order in flows
Business rules SHALL live in features; cross-feature ordering SHALL live in flows, which
coordinate and never decide. Features SHALL be mutually blind, coordinating only through durable
state behind shared ports. Each **kind of write** to a durable port (per key where one technology
backs several stores) SHALL be owned by exactly one piece of code — one feature's use case or one
port's guarded write — and each such write SHALL be one transaction whose guard is in the statement,
so it is safe against any other write landing between its read and its write. How many **processes**
run that code at once is not an invariant: the upload ledger's record family is written by whichever
upload cycle runs, and on iOS ≥ 26.1 under a full grant both processes run one (`upload-lifecycle`,
"Both uploaders may run; an overlap is a duplicate, never a loss"; `sync-ledger`). Shared config SHALL
be written whole by its one writer, and no field of shared state may encode a request to another
feature. Recovery and reconciliation decisions SHALL be feature rules over port-exposed facts, never
adapter logic. Decision record: `changes/both-uploaders-active` (D1).

#### Scenario: An ordering rule hides in a feature
- **WHEN** review finds a feature method whose only purpose is to sequence effects on behalf of a
  flow that could not express a branch
- **THEN** the branch is restored to the flow using the transcriber grammar's sealed-result form,
  or the rule is named and kept in the feature as a rule

#### Scenario: Two features own one kind of write
- **WHEN** a second feature gains a write path for a kind of write to a durable port (or key) that
  already has an owner
- **THEN** the design is corrected so one piece of code owns that kind of write

#### Scenario: Two processes running the owning code is not a second owner
- **WHEN** the same owning code writes one durable port from two processes at once, each write one
  guarded transaction
- **THEN** the ownership rule is met; safety against the interleaving is the guard's, not process
  exclusivity's

### Requirement: One shared composition
Every binary that assembles the live core SHALL call the shared composition (`snapSyncApp` for
the app graph, `uploadCore` for the extension's strict subset bundle); there SHALL be no second
wiring. The composition functions SHALL receive a `CoroutineScope`. The wiring graph SHALL NOT
be unit-tested (it is smoke-tested end to end by the world harness and integration tests over
fake ports); a decision about which platform mechanism may be used — today the one fact whether the
upload extension may be registered (`upload-lifecycle`, "Whether the extension may be registered is
one pure fact") — SHALL be a pure, unit-tested **total** function from the **OS capability facts it
reads** and current runtime state, and the shell SHALL invoke only the shell-supplied adapter thunks
that answer permits, deciding nothing itself. A fact that is fixed by the compilation target SHALL NOT
be re-derived at runtime and SHALL NOT enter that function. Such a function SHALL be re-evaluated
whenever one of its inputs changes, rather than once per process, so a choice that depends on runtime
state does not force the choice out of the function and into scattered guards.

#### Scenario: The harness cannot drift from production
- **WHEN** the world harness and the device binaries compose the core
- **THEN** they execute the same composition function over different port implementations, so a
  wiring difference is impossible rather than undetected

#### Scenario: A new mechanism or a new input state is added
- **WHEN** a new platform mechanism, or a new value of an input to such a function, is introduced
- **THEN** the function fails to compile until every combination is handled, and its cells are
  unit-tested — including that no cell permits a mechanism the running OS cannot invoke

#### Scenario: A target-fixed fact is not an input
- **WHEN** a fact is already determined by which Kotlin target produced the binary
- **THEN** the function does not take it as an input and no runtime read re-derives it
