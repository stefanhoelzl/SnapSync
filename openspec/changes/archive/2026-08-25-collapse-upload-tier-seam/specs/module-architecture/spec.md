## MODIFIED Requirements

### Requirement: One shared composition
Every binary that assembles the live core SHALL call the shared composition (`snapSyncApp` for
the app graph, `uploadCore` for the extension's strict subset bundle); there SHALL be no second
wiring. The composition functions SHALL receive a `CoroutineScope`. The wiring graph SHALL NOT
be unit-tested (it is smoke-tested end to end by the world harness and integration tests over
fake ports); selection of a platform mechanism SHALL be a pure, unit-tested **total**
function from the **OS capability facts the resolver reads** and current runtime state to a mechanism
identity, and the shell SHALL invoke only the shell-supplied adapter thunks that identity names,
deciding nothing itself. A fact that is fixed by the compilation target SHALL NOT be re-derived at
runtime and SHALL NOT enter that function. Such a resolver SHALL be re-evaluated whenever one of its
inputs changes, rather than once per process, so a mechanism choice that depends on runtime state does
not force the choice out of the resolver and into scattered guards.

#### Scenario: The harness cannot drift from production
- **WHEN** the world harness and the device binaries compose the core
- **THEN** they execute the same composition function over different port implementations, so a
  wiring difference is impossible rather than undetected

#### Scenario: A new mechanism or a new input state is added
- **WHEN** a new platform mechanism, or a new value of a resolution input, is introduced
- **THEN** the resolver fails to compile until every combination is handled, and its cells are
  unit-tested — including that no cell names a mechanism the running OS cannot invoke

#### Scenario: A target-fixed fact is not a resolver input
- **WHEN** a fact is already determined by which Kotlin target produced the binary
- **THEN** the resolver does not take it as an input and no runtime read re-derives it
