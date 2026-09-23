# port-contracts Specification

## Purpose

What every implementation of a port must do, stated once as executable **clauses** and run against all of
them — the honest fake and each real adapter — so a double cannot quietly answer differently from the
system it stands in for.

Three failures this exists to stop, all of which this project has paid for. Beliefs about external systems
were unverified prose that went stale with nothing failing (`-25291` was nearly "corrected" to `-34018`;
both are true, for different hosts). The fakes every integration test stands on were gated for their
surface and not their behaviour. And port obligations — `SecureStore`'s three-state read, `resolveOrMint`'s
"normative" ordering — lived in doc comments no check read.

Nothing here generates a contract. A clause is hand-written, and **the contract code is the specification
of a port's clauses**; this spec states the mechanism around them: states, hosts, bindings, outcomes, the
rule that every clause must run against a real implementation somewhere, and how a host CI cannot reach is
recorded at the operating-system boundary and replayed against the current adapter on every build.

Decision records: `changes/archive/2026-09-22-establish-port-contracts`; the injected-location rule and the
App-Group container column, `changes/archive/2026-09-23-contract-app-group-stores`; an external service as
part of the implementation under contract, and the observation handle over outcomes a port cannot return,
`changes/archive/2026-09-23-contract-backend-clients`; the simulator-app host, grant preconditions, and binding
the composition production calls, `changes/archive/2026-09-23-photokit-contracts`.

## Requirements

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

A real adapter whose storage location is **injected** — a fresh temporary directory, or a fresh preferences
suite, in place of the App-Group container — SHALL count as a real implementation for the clauses it runs:
every call it makes to the operating system is the one production makes, except the single lookup that
resolves the location. That lookup SHALL NOT be counted as covered by an injected binding. A clause about
the location being **unavailable** SHALL be entered by constructing the adapter with its production default,
so the host's own answer to the lookup — not a value the binding passed — drives the adapter's
unavailable branch.

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

#### Scenario: A file store over an injected directory
- **WHEN** a live binding constructs a file-backed adapter over a fresh temporary directory for a readable
  state
- **THEN** its clauses count as run against a real implementation, and none of them counts as coverage of
  the container lookup

#### Scenario: An unavailable container is the host's own answer
- **WHEN** a live binding on `IOS_SIM_KEXE` enters a store's unavailable state
- **THEN** it constructs the adapter with its default location, whose App-Group lookup the unentitled
  executable answers with `nil`, rather than passing an absent location itself

### Requirement: Hosts are a closed set of what changes reachable states

A host SHALL be a value of a closed `Host` enum whose identity is platform × process kind × entitlements —
the attributes that change which states a binding can reach, and that are known statically. Operating
system version, device model, toolchain and date SHALL NOT be host identity; they are provenance recorded
with a run. The enum SHALL hold only hosts that some binding names: `JVM`, `IOS_SIM_KEXE`, `IOS_SIM_APP`
and `IOS_DEVICE_APP`. An authorization granted to the process from outside it — a photo grant — is not host
identity either; it is a precondition of a run ("An authorization the process cannot give itself is a
precondition of the run").

An external service a binding launches or reaches — the real backend served locally for a test — SHALL
NOT be a host. It is part of the implementation under contract, and the binding's host is the process
the binding runs in. Where the port's client is the same in every binding and only the service behind it
differs, the service decides the binding's kind: a stand-in service is `Fake`, the real one run for real
is `Live`.

The known host matrix, which the next binding starts from:

| host | process | Keychain, as measured | App-Group container, as measured | photo access |
|---|---|---|---|---|
| `JVM` | JVM test | none | none | none |
| `IOS_SIM_KEXE` | Kotlin/Native `test.kexe` spawned by `simctl`, unentitled | every `SecItem*` call answers `-25291` (`errSecNotAvailable`) | the lookup answers `nil` | reads `DENIED` (`PHAuthorizationStatus` 2), measured 2026-09-23 on an iOS 26.5 simulator; no route to a grant, because the process has no bundle identifier |
| simulator `.xctest` (unbound) | Swift test bundle, no `TEST_HOST` | answers `-34018` (`errSecMissingEntitlement`) | not measured | not measured |
| `IOS_SIM_APP` | the rig build of the app bundle on a simulator, ad-hoc signed with the App Group only | `-34018` to an explicit-group query: `simulator.entitlements` omits `keychain-access-groups` (measured 2026-09-22, iOS 26.2) | available under the ad-hoc signature `scripts/sim-sign` applies; an unsigned build has none (measured 2026-08-09) | granted before launch by `applesimutils`; `simctl privacy grant photos` writes a TCC row PhotoKit does not consult (measured 2026-08-25, iOS 26.2); no partial grant exists on a simulator |
| `IOS_DEVICE_APP` | the entitled app on a device | accessible | available | whatever a person set; a partial grant is reachable only here |
| device extension (unbound) | the upload extension process | not measured | available — the extension's ledger, config and log live there | the app's grant; the OS invokes it under a partial grant when a full-grant registration survived (measured 2026-09-21) |

No host enforces file data protection before first unlock in a way a binding can enter: the simulator
implements none (a platform belief, not measured here), and the rig drives only a running, unlocked app. A clause conditioned on protected data being
unavailable therefore has no real host.

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

#### Scenario: The simulator app is its own host

- **WHEN** a binding runs inside the app bundle on a simulator
- **THEN** its host is `IOS_SIM_APP`, never `IOS_SIM_KEXE` and never `IOS_DEVICE_APP`, because its bundle
  identifier is what makes a photo grant reachable and its missing Keychain entitlement is what makes the
  device's Keychain states unreachable

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

### Requirement: An authorization the process cannot give itself is a precondition of the run

A binding SHALL NOT claim to enter at construction a state that depends on an authorization granted to the
process from outside it — the photo grant, which the operating system keys to the bundle and the process
cannot change. It SHALL declare the one grant it runs under, reach only the
states consistent with it, and **refuse** the whole run, before any clause executes, in a process holding a
different grant. A refused run SHALL report the refusal and no clause outcome; the rig SHALL answer it with
the refusal status the contract verb already uses.

#### Scenario: A mis-granted launch

- **WHEN** the simulator app is launched without the photo grant its bindings declare, and the contract verb
  is called
- **THEN** the run is refused before any clause executes, the channel answers the refusal status, and no
  clause reads `Passed` or `NotRunHere`

#### Scenario: A grant state no host can enter from inside

- **WHEN** a clause needs a partial photo grant
- **THEN** only a host where a person set that grant can reach it, and a binding on any other host declares
  it unreachable rather than attempting it

### Requirement: A live binding binds the composition production calls

A real binding SHALL bind the composition production calls, over the real adapter and the real platform
reads, wherever production reaches a port only through a composition that owns part of that port's
contract — a grant-aware wrapper that answers "no answer" where the bare adapter would answer "nothing". Where production binds an adapter bare, the adapter SHALL satisfy the whole contract itself, and a
clause it fails SHALL be fixed in the adapter rather than excused by a caller's gate.

#### Scenario: A bare adapter would state a falsehood

- **WHEN** the bare photo-library candidate source, without a grant, would answer a readable empty library
- **THEN** the contract's real binding is the grant-aware composition production calls, which answers "not
  readable", and the bare adapter's precondition stays documented on the adapter

#### Scenario: An adapter bound bare relies on its callers' gates

- **WHEN** a clause fails against an adapter production binds bare, and only the callers' gates keep the
  failing path unreachable
- **THEN** the adapter is fixed to satisfy the clause, and the callers' gates remain as they were

### Requirement: In-app hosts CI can reach are run live over the rig

A host whose bindings must run inside the app, but which CI can run — the simulator app — SHALL be run
**live** on every push, not recorded: a CI job SHALL build the app under `-Psnapsync.rig=true` for that host,
establish the declared preconditions, launch it, and run every contract in that host's **in-app registry**
through the rig's contract verb, failing on any `Failed` outcome, any refusal, or an empty registry. The
registry SHALL be a source-level list the contract-coverage gate reads. Such a host SHALL NOT be recorded:
record and replay exist for hosts CI cannot run.

Clauses run live in a shared system that cannot be reset between them — a photo library, whose deletions
need a person's confirmation — SHALL be isolated by addresses derived from the clause id (capture dates,
titles, identifiers), and SHALL NOT delete what they seed; the job SHALL start each run from a fresh
simulator.

#### Scenario: A contract is registered for the simulator app

- **WHEN** a `Live` binding naming the simulator-app host is added and registered
- **THEN** the next push's CI job runs its contract in the app and fails on any `Failed` outcome

#### Scenario: Two clauses seed the same library

- **WHEN** two clauses each seed assets into the simulator's shared photo library
- **THEN** each reads only the capture-date window derived from its own id, so neither sees the other's
  assets and neither deletes anything
