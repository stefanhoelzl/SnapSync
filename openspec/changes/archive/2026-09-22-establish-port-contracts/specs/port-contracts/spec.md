## ADDED Requirements

### Requirement: A port contract is a hand-written list of clause values, and the code is its specification

Every contracted port SHALL have exactly one contract in `:test:contracts`' `commonMain`: an explicit list
of **clause values**, each carrying a stable clause id, the state it needs, and a body that exercises the
port and asserts with ordinary `kotlin.test` assertions. The clause list SHALL be the single statement of
the port's obligations — no `openspec/` spec restates a contract's clauses — and SHALL be hand-written:
nothing generates, records, or rewrites a clause or an expectation from observed behaviour.

The same list SHALL feed every runner. On CI, one test per (contract, binding) SHALL run every clause,
collect every outcome, and fail once with the full outcome table when any clause is `Failed` or
`Diverged`. On a host with no test runner, an in-app runner SHALL execute the same list.

#### Scenario: A clause is changed
- **WHEN** a clause body is edited in the contract
- **THEN** every binding runs the edited clause on its next run, on CI and on device, with no second list
  to update

#### Scenario: Several clauses fail
- **WHEN** three clauses fail against one binding
- **THEN** the run reports all three in one outcome table rather than stopping at the first

#### Scenario: Expectations are never recorded
- **WHEN** a run against a real implementation observes behaviour no clause asserts
- **THEN** no clause or expectation is created or changed by the run; a person decides whether to write one

### Requirement: Clauses are conditioned on states that bindings enter at construction

Each contract SHALL define a hand-written state vocabulary beside it in `:test:contracts` (production code
SHALL NOT gain it). A **binding** SHALL pair exactly one implementation with exactly one host, declare its
kind (`Fake`, `Live` or `Replay`), and declare **as a literal** the set of states it reaches. It SHALL
provide `create(state)`, returning either a fresh implementation already in that state, or
`Unreachable(reason)`. Every clause SHALL receive a fresh instance.

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

### Requirement: Outcomes are explicit and none is silent

Every clause run against a binding SHALL end in exactly one of: `Passed`; `Failed(message)` — the
implementation, or a recorded answer, violates the clause; `NotRunHere(reason)` — the binding cannot reach
the clause's state; `Diverged(message)` — on replay, the clause made an operating-system call the
recording does not hold; `NotWithin(T)` — a bounded wait on an operating-system callback expired. A clause
SHALL NOT end without an outcome. Whether a clause runs SHALL be decided by its binding before the body
executes; a clause body SHALL have no operation that skips it, so an unexercised clause cannot report
`Passed`.

#### Scenario: A host cannot produce a clause's state
- **WHEN** a binding cannot enter the state a clause needs
- **THEN** the clause reads `NotRunHere(reason)` without its body running, and never `Passed`

#### Scenario: A replay meets an unrecorded call
- **WHEN** the adapter under replay makes a call its recording does not hold
- **THEN** the clause reads `Diverged`, distinct from `Failed`, and the remedy is to re-record

### Requirement: Every clause runs against a real implementation on some host

Every clause SHALL be reachable by at least one real implementation on at least one host: a `Live`
binding on a host CI runs that declares the clause's state reachable, or a `Replay` binding whose recording
holds a block for the clause. A host that some `Replay` binding names is a **recorded** host — CI never runs
it — so a `Live` binding there counts only through its recording, never through its declaration. A clause reachable only by a fake SHALL NOT exist. A belief about the platform that no
host can exercise belongs in the adapter's documentation with its evidence; a behaviour of the project's
own logic belongs in an ordinary fake-backed test.

#### Scenario: A clause only the fake reaches
- **WHEN** a clause is added whose state every `Live` binding declares unreachable and no recording holds
- **THEN** the build fails naming the clause

#### Scenario: A device-only clause before anyone has recorded it
- **WHEN** a clause's state is reachable only by the device's `Live` binding and no recording holds the
  clause
- **THEN** the build fails naming the clause, although the device binding declares the state reachable

#### Scenario: A failing clause is hidden by an unreachable declaration
- **WHEN** a clause fails on its only real host and that host's binding is changed to declare the state
  unreachable
- **THEN** the clause has no real host and the build fails

### Requirement: Hosts are a closed set of what changes reachable states

A host SHALL be a value of a closed `Host` enum whose identity is platform × process kind × entitlements —
the attributes that change which states a binding can reach, and that are known statically. Operating
system version, device model, toolchain and date SHALL NOT be host identity; they are provenance recorded
with a run. The enum SHALL hold only hosts that some binding names; this change introduces `JVM`,
`IOS_SIM_KEXE` and `IOS_DEVICE_APP`.

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

### Requirement: Hosts CI cannot reach are recorded at the operating-system boundary and replayed on every build

For a host CI cannot run, the contract SHALL be run in-app on demand and SHALL record, per clause, every
call the adapter makes **to the operating system** and the operating system's answer — never the port's
answers. Every CI build SHALL replay each recording by running the **current** adapter code against the
recorded answers through the adapter's own operating-system seam, with the current clauses judging. A
replay binding SHALL report the host its recording was taken on.

An adapter recorded this way SHALL route its operating-system calls through an `internal` seam in its own
module; state seeding SHALL go through the same seam, so it is recorded and replayed like any other call.
A recording is input to a clause, never an expectation.

#### Scenario: A comment is edited in the adapter
- **WHEN** a change to the adapter leaves every operating-system call identical
- **THEN** replay passes and nothing is re-recorded

#### Scenario: The adapter changes what it asks the operating system
- **WHEN** the adapter sends a call with different attributes than were recorded
- **THEN** the clause reads `Diverged` and the recording must be retaken on the host

#### Scenario: The recorded answer violates a clause
- **WHEN** the adapter makes exactly the recorded calls and a clause's assertion fails against the
  recorded answers
- **THEN** the clause reads `Failed`, a finding against the code or the clause that re-recording does not
  resolve

### Requirement: Replay matches exactly, in order, over deterministic clauses

Recorded requests SHALL be matched exactly and in order within a clause. Answers SHALL be recorded in
full, with only a named list of volatile keys masked to fixed placeholders. Clause inputs SHALL be
deterministic — fixed values, a fixed identifier generator, and addresses derived from the clause id. A
missing recording, or a recording lacking a block for a clause its host's binding declares reachable,
SHALL be `Failed`.

#### Scenario: The adapter reorders two calls
- **WHEN** the adapter issues the same calls as recorded in a different order
- **THEN** the clause reads `Diverged`

#### Scenario: The adapter starts reading an answer attribute
- **WHEN** the adapter begins reading an attribute of an answer that it did not read when recorded
- **THEN** the attribute is present on replay, because answers are recorded in full

#### Scenario: A recording is deleted
- **WHEN** a replay binding's recording file is absent
- **THEN** its clauses are `Failed`, not `NotRunHere`

### Requirement: A recording is one committed plain-text file per contract and host

A recording SHALL live at `test/contracts/recordings/<Contract>@<HOST>.rec` as line-oriented text: a
provenance header naming the contract, host, device model, operating-system version, build number,
Kotlin version and date, followed by one `[CLAUSE_ID]` block per clause, sorted by id, of `call -> answer`
lines. Each run SHALL overwrite the file; its history is the repository's history. The in-app run SHALL
return the file content verbatim, and it SHALL be committed without editing.

#### Scenario: A re-recording changes one answer
- **WHEN** a device run is repeated and one answer differs
- **THEN** the committed diff is confined to that clause's block and the header's provenance

### Requirement: The device run is reached through the rig and contained at compile time

The in-app runner SHALL be reachable only through the rig control channel and SHALL be present only in a
build made with `-Psnapsync.rig=true`; a production build SHALL contain none of the contract module, the
device bindings or the recorder. A device run SHALL answer with the recording and the live outcome table.

#### Scenario: A production build
- **WHEN** the app is built without the rig property
- **THEN** no contract, binding or recorder source is on the compile path

#### Scenario: Recording on the device
- **WHEN** an operator calls the rig's contract verb for `SecureStore` on an entitled device
- **THEN** it runs every clause in-app and answers with the recording text and each clause's live outcome
