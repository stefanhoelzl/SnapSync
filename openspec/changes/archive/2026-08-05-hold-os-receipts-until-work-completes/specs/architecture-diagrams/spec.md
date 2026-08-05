## MODIFIED Requirements

### Requirement: Diagrams are derived, never drawn
The repository SHALL contain a generated diagram set under `architecture/`, produced by
`./gradlew architectureDiagrams` from the code and build model alone: the module dependency
graph, the zone/feature graph, one sequence diagram per flow command, the port × adapter matrix
(including a fake-and-contract-test-exists column), feature cards (including the forge
name→sources map), and the DI wiring graphs with the binary × port matrix. Diagrams SHALL be
Mermaid-in-Markdown. Hand-edited content in `architecture/` SHALL NOT survive the freshness gate.

#### Scenario: A wiring difference between binaries becomes visible
- **WHEN** two binaries pass different implementations for the same port without a documented
  reason
- **THEN** the binary × port matrix shows the anomalous cell in the PR diff that introduced it

The flow transcriber SHALL derive its trigger inventory from the `flow/` zone's directory
listing (one file per trigger — never a hand-enumerated list) and SHALL transcribe every function
in each flow file against the **closed grammar**: straight-line calls (features and the
`compose/`-built effect lambdas); an **awaited fan-out** `coroutineScope { launch { … } … }`, whose
branch bodies are themselves grammar-bound; a `when` over a feature-returned sealed result whose
branches are a single call, launch, or `Unit`; a single **leading** guard clause (the null-check pair
`val x = codec(...)` + `if (x == null) { log; return }`, or a sole `<call>?.let { … }` guarded
region); a best-effort wrap (`runCatching { call }.onFailure { log-only }` — the absorb is
diagnostics, transparent to transcription); a fan-out loop over an injected receiver list; and
`log.*` statements (diagnostics, omitted). The transcriber scanning an empty `flow/` scope SHALL
fail, never render nothing.

An escaping `scope.launch` SHALL NOT be part of the grammar. It was the sanctioned concurrent form
until flows lost their `CoroutineScope` (law *A trigger flow never outlives its own run*); keeping it
legal would leave the transcriber rendering — and thereby blessing — the one shape the law exists to
forbid. The rendered concurrent region SHALL be marked as awaited, so a reader cannot mistake a
branch that the flow waits for for work that escapes it.

#### Scenario: A flow the generator cannot transcribe
- **WHEN** a flow function's body falls outside the closed grammar
- **THEN** generation fails the build — both the CI `diagrams` job's `architectureDiagrams` run
  and the in-process freshness test under `./gradlew build` — naming the file, line, and
  construct, because an untranscribable flow is a law violation, not a rendering problem

#### Scenario: A flow reintroduces a detaching launch
- **WHEN** a flow function's body contains an escaping `scope.launch { … }`
- **THEN** generation fails naming that construct, because the form is no longer in the grammar

#### Scenario: A concurrent region reads as awaited
- **WHEN** a flow fans out concurrently and awaits its branches
- **THEN** the rendered sequence diagram marks the region as concurrent *and* awaited, rather than
  showing branches indistinguishable from escaping work
