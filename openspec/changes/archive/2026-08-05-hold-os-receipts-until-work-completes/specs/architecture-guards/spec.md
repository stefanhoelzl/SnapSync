## MODIFIED Requirements

### Requirement: The zone gates
The build SHALL enforce, over source text with derived scopes: `model/` references nothing
project-internal outside `model/`; `ports/` references only `model/`; features reference only
`model/` and `ports/` and never a sibling feature (pairwise, features enumerated from the
directory); `flow/` references only `model/` and `feature/`; `flow/` declares no `CoroutineScope`
and accepts no non-suspend effect lambda (law *A trigger flow never outlives its own run* — both
doors, because removing the scope alone leaves the lambda one open); `:ui:presentation` references
only the injected flow command bundle, feature read-model types, and `model/`; `:domain` and `:ui`
zones import only their per-zone allowlisted libraries; `:domain` has no `iosMain` source
directory and declares no `project()` dependency.

#### Scenario: A fully-qualified sidestep
- **WHEN** a file references a forbidden declaration by fully-qualified name without an import
- **THEN** the text gate fails exactly as it would for an import

#### Scenario: A flow reacquires a way to detach
- **WHEN** a `flow/` class gains a `CoroutineScope` parameter or a non-suspend effect lambda
- **THEN** the gate fails, naming the file, before any device build
