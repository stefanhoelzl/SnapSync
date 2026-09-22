## MODIFIED Requirements

### Requirement: Clauses are conditioned on states that bindings enter at construction

Each contract SHALL define a hand-written state vocabulary beside it in `:test:contracts` (production code
SHALL NOT gain it). A **binding** SHALL pair exactly one implementation with exactly one host, declare its
kind (`Fake`, `Live` or `Replay`), and declare **as a literal** the set of states it reaches. It SHALL
provide `create(state)`, returning either a fresh implementation already in that state, or
`Unreachable(reason)`. Every clause SHALL receive a fresh instance.

A clause's subject SHALL be the port, or — for a port that declares **no reads** of its own, such as an
**inbound** port the core implements (`module-architecture`, "OS entry points cross an inbound port") — the
port together with an **observation handle** declared beside the contract and implemented by each binding
over the system it built. Clauses SHALL observe through that handle only **outcomes** of the system behind
the port (state reached, objects landed, a completion released), never a record of which collaborator the
implementation called: a call transcript restates the wiring and is passed by any implementation that
mirrors it. `:test:contracts` SHALL NOT depend on the system a binding builds; the binding adapts it to the
handle.

The runner SHALL verify the declaration on every run: a declared state answered `Unreachable`, or an
undeclared state answered with an instance, is `Failed`.

A scenario that needs an implementation to change state mid-run SHALL NOT be a clause; it is a test of
the project's own logic over the port, written as an ordinary fake-backed test.

#### Scenario: A fake enters a state
- **WHEN** a clause needs the `Inaccessible` state from the honest fake
- **THEN** the fake's binding constructs it in that state through its constructor, without a lever

#### Scenario: A real implementation cannot reach a state
- **WHEN** the unentitled simulator test binary's Keychain binding is asked for `Empty`
- **THEN** it answers `Unreachable` with a reason naming the host's limitation, and the clause reads
  `NotRunHere(reason)` for that binding

#### Scenario: A binding's declaration lies
- **WHEN** a binding declares a state reachable and `create` answers `Unreachable` for it
- **THEN** that clause is `Failed` for the binding

#### Scenario: An inbound port is contracted

- **WHEN** a contract covers a port whose members return nothing, such as the app's OS entry surface
- **THEN** each binding supplies the port and an observation handle over the core it composed in the named
  state, and every clause asserts an outcome read through the handle
