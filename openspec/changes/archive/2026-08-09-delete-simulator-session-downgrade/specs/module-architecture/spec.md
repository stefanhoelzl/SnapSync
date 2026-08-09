## MODIFIED Requirements

### Requirement: One shared composition
Every binary that assembles the live core SHALL call the shared composition (`snapSyncApp` for
the app graph, `uploadCore` for the extension's strict subset bundle); there SHALL be no second
wiring. The composition functions SHALL receive a `CoroutineScope`. The wiring graph SHALL NOT
be unit-tested (it is smoke-tested end to end by the world harness and integration tests over
fake ports); composition selection SHALL be a pure, unit-tested function from parsed launch
directives and the **OS capability facts the resolver reads** to a sealed composition mode, with
`composeRoot` switching once on the sealed type and invoking only the selected shell-supplied adapter
thunks. A fact that is fixed by the compilation target SHALL NOT be re-derived at runtime and SHALL NOT
enter that function. The forge composition is the one named non-core composition, with its own
non-vacuity gate.

#### Scenario: The harness cannot drift from production
- **WHEN** the world harness and the device binaries compose the core
- **THEN** they execute the same composition function over different port implementations, so a
  wiring difference is impossible rather than undetected

#### Scenario: A new launch directive is added
- **WHEN** a new dev/test trigger is introduced
- **THEN** the sealed composition-mode resolver fails to compile until the mode handles it, and
  the resolver's precedence rules (forge excludes live-stack boot) are unit-tested

#### Scenario: A target-fixed fact is not a resolver input
- **WHEN** a fact is already determined by which Kotlin target produced the binary
- **THEN** the resolver does not take it as an input and no runtime read re-derives it
