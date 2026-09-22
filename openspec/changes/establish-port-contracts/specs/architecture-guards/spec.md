## ADDED Requirements

### Requirement: The contract-coverage gate

A `:test:architecture` gate SHALL derive, from the repository's text and the committed recordings, every
contract's clause ids and the state each needs; every binding's host, kind and declared reachable states;
and every recording's host and clause blocks. It SHALL fail, naming the clause, when a clause is reached
by no `Live` binding's declaration and by no `Replay` binding's recording (capability `port-contracts`,
"Every clause runs against a real implementation on some host"). It SHALL fail when a `Host` value is named
by no binding.

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
