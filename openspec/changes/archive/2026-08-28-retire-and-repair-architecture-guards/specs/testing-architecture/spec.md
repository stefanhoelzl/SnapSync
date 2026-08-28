## MODIFIED Requirements

### Requirement: Fake-driven feature tests live in the fake module

Feature tests that drive `:domain` subjects through the honest in-memory port implementations SHALL
live in `:adapter:generic:fake`'s own `commonTest`. `:domain`'s test source set cannot reach those
fakes: `:adapter:generic:fake` depends on `:domain`, so a test edge back from `:domain` is a project
dependency cycle, and a test source set cannot be depended on across modules at all — which is the
same constraint that puts the shared storage contracts in `:test:world`'s `commonMain`
(`harness-world-model`).

`:domain`'s `commonTest` SHALL therefore hold only tests standing on pure functions or hand-written
local doubles.

Two consequences SHALL be stated rather than discovered: a feature's tests may be split across two
modules, so a reader looking for them must look in both; and `:adapter:generic:fake`'s `commonTest` is a
**test host** that legitimately sees more than any other consumer. The fakes are `internal`, exported
through factories returning the port type, so no other module can name an implementation or reach a
member the port does not declare — but `internal` is module-scoped and a module's own test source set is
inside it. That is what makes the fake module the only place these tests can live, and it is a property
of the module boundary rather than of a gate that reads source.

#### Scenario: A feature test needs a fake

- **WHEN** a `:domain` feature test requires an honest in-memory port implementation
- **THEN** it is written in `:adapter:generic:fake`'s `commonTest`, not in `:domain`'s

#### Scenario: A test-only helper is added to the fake module

- **WHEN** a helper is added under `:adapter:generic:fake`'s `commonTest`
- **THEN** the fake-honesty gate does not scan it, because the gate's subject is what the fakes
  expose in their main source sets
