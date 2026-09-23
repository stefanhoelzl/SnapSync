## MODIFIED Requirements

### Requirement: The contract-coverage gate

A `:test:architecture` gate SHALL derive, from the repository's text and the committed recordings, every
contract's clause ids and the state each needs; every binding's host, kind and declared reachable states;
and every recording's host, grant and clause blocks — the host and grant read from the file name
(`<Contract>@<HOST>[.<GRANT>].rec`, capability `port-contracts`). It SHALL fail, naming the clause, when a clause is reached
by no declaration of a `Live` binding on a host CI runs, and by no `Replay` binding's recording — a host some
`Replay` binding names being a recorded host, where only the recording counts (capability `port-contracts`,
"Every clause runs against a real implementation on some host"). A `Replay` binding that declares a grant SHALL count only through the recording of that grant. It SHALL
fail when a `Host` value is named by no binding, and when a recording's name carries a grant no binding of
that host declares.

A host CI runs **in-app** — the simulator app — is visible to the gate only through source, so a `Live`
binding there SHALL count only when it is registered in that host's in-app contract registry, the list the
CI job runs (capability `port-contracts`, "In-app hosts CI can reach are run live over the rig"). The gate
SHALL fail, naming the binding, when a `Live` binding names such a host and is absent from its registry.

Its scope SHALL be derived, never listed ("Gates fail closed on novelty"), with a non-vacuity twin for each
derived group — contracts, bindings, recordings. A declaration in a form the gate cannot read SHALL fail
the gate rather than be skipped.

#### Scenario: A clause loses its last real host
- **WHEN** the only `Live` binding reaching a clause's state stops declaring it, and no recording holds the
  clause
- **THEN** the gate fails naming the clause

#### Scenario: A binding declares its states in an unreadable form
- **WHEN** a binding computes its reachable states instead of declaring them as a literal
- **THEN** the gate fails naming the binding

#### Scenario: A group empties
- **WHEN** a rename leaves the gate finding no bindings while contracts and recordings still resolve
- **THEN** the bindings group's non-vacuity twin fails

#### Scenario: A simulator-app binding is left out of the registry

- **WHEN** a `Live` binding names the simulator-app host but is not registered in the registry the CI job runs
- **THEN** the gate fails naming the binding, and its clauses do not count as covered

#### Scenario: A grant's recording is missing

- **WHEN** a replay binding declares the partial grant and only the full-grant recording of its contract and
  host exists
- **THEN** the binding's clauses do not count as covered through the other grant's recording, and a clause
  reached by no other real host fails the gate

#### Scenario: A recording names an undeclared grant

- **WHEN** a recording file carries a grant suffix no binding of that host declares
- **THEN** the gate fails naming the file

