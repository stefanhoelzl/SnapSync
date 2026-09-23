## MODIFIED Requirements

### Requirement: Clauses are conditioned on states that bindings enter at construction

Each contract SHALL define a hand-written state vocabulary beside it in `:test:contracts` (production code
SHALL NOT gain it). A **binding** SHALL pair exactly one implementation with exactly one host, declare its
kind (`Fake`, `Live` or `Replay`), and declare **as a literal** the set of states it reaches. It SHALL
provide `create(state)`, returning either a fresh implementation already in that state, or
`Unreachable(reason)`. Every clause SHALL receive a fresh instance.

A clause's subject SHALL be the port, or — where an outcome a clause asserts is **not readable through the
port's own members** — the port together with an **observation handle** declared beside the contract and
implemented by each binding over the system it built. Two cases are known: a port that declares **no reads**
of its own, such as an **inbound** port the core implements (`module-architecture`, "OS entry points cross
an inbound port"); and an outcome the implementation reports through a callback its binding wires rather
than through the port's result, such as a backend's refusal of this build or of its credential, which reaches
the app only through the HTTP client's interceptor. Clauses SHALL observe through that handle only **outcomes** of the system behind
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

#### Scenario: An outcome reaches the app only through a callback

- **WHEN** a clause asserts that the backend refuses this build and names the minimum version, and the
  port's own result can only say the call failed
- **THEN** the subject carries an observation handle the binding implements over the interceptor callbacks
  it wired, and the clause reads the refusal the app now holds through it, never a record of which callback
  ran

### Requirement: Hosts are a closed set of what changes reachable states

A host SHALL be a value of a closed `Host` enum whose identity is platform × process kind × entitlements —
the attributes that change which states a binding can reach, and that are known statically. Operating
system version, device model, toolchain and date SHALL NOT be host identity; they are provenance recorded
with a run. The enum SHALL hold only hosts that some binding names; this change introduces `JVM`,
`IOS_SIM_KEXE` and `IOS_DEVICE_APP`.

An external service a binding launches or reaches — the real backend served locally for a test — SHALL
NOT be a host. It is part of the implementation under contract, and the binding's host is the process
the binding runs in. Where the port's client is the same in every binding and only the service behind it
differs, the service decides the binding's kind: a stand-in service is `Fake`, the real one run for real
is `Live`.

The known host matrix, which the next binding starts from:

| host | process | Keychain, as measured |
|---|---|---|
| `JVM` | JVM test | none |
| `IOS_SIM_KEXE` | Kotlin/Native `test.kexe` spawned by `simctl`, unentitled | every `SecItem*` call answers `-25291` (`errSecNotAvailable`) |
| simulator `.xctest` (unbound) | Swift test bundle, no `TEST_HOST` | answers `-34018` (`errSecMissingEntitlement`) |
| simulator app (unbound) | the app bundle, ad-hoc signed | `-34018` to an explicit-group query: `simulator.entitlements` omits `keychain-access-groups` (measured 2026-09-22, iOS 26.2) |
| `IOS_DEVICE_APP` | the entitled app on a device | accessible |
| device extension (unbound) | the upload extension process | not measured |

#### Scenario: An iOS update changes an answer
- **WHEN** a re-recording on a newer iOS version yields a different answer on `IOS_DEVICE_APP`
- **THEN** it is a change on the same host, visible in that host's recording history, not a new host

#### Scenario: A contract is asked to record on a host it does not record for
- **WHEN** a contract that records for one host is run in a process that is a different host — a simulator
  app asked for the device's recording
- **THEN** it refuses instead of recording, and the channel answers a refusal status, so its answer cannot be
  redirected into a recording filed under a host it never ran on

#### Scenario: Two processes on one simulator
- **WHEN** a binding runs in the simulator's Kotlin/Native test executable
- **THEN** its host is `IOS_SIM_KEXE`, never a generic "simulator" value shared with a Swift test bundle

#### Scenario: A binding runs the real backend

- **WHEN** a JVM test binding drives a backend client against the real backend it launched locally
- **THEN** its host is `JVM` and its kind is `Live`, and the same client over an in-memory stand-in of the
  backend is a `Fake` binding, although the client code is identical in both
