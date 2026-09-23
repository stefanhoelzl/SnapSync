## MODIFIED Requirements

### Requirement: Clauses are conditioned on states that bindings enter at construction

Each contract SHALL define a hand-written state vocabulary beside it in `:test:contracts` (production code
SHALL NOT gain it). A **binding** SHALL pair exactly one implementation with exactly one host, declare its
kind (`Fake`, `Live` or `Replay`), and declare **as a literal** the set of states it reaches. It SHALL
provide `create(state)`, returning either a fresh implementation already in that state, or
`Unreachable(reason)`. Every clause SHALL receive a fresh instance.

A clause's subject SHALL be the port, or — where an outcome a clause asserts is **not readable through the
port's own members** — the port together with an **observation handle** declared beside the contract and
implemented by each binding over the system it built. Three cases are known: a port that declares **no reads**
of its own, such as an **inbound** port the core implements (`module-architecture`, "OS entry points cross
an inbound port"); an outcome the implementation reports through a callback its binding wires rather
than through the port's result, such as a backend's refusal of this build or of its credential, which reaches
the app only through the HTTP client's interceptor; and an outcome that **leaves the process**, such as what a
reporting channel transmitted, which the binding reads at a receiving endpoint it stood up for the purpose.
Clauses SHALL observe through that handle only **outcomes** of the system behind
the port (state reached, objects landed, a completion released), never a record of which collaborator the
implementation called: a call transcript restates the wiring and is passed by any implementation that
mirrors it. `:test:contracts` SHALL NOT depend on the system a binding builds; the binding adapts it to the
handle.

The runner SHALL verify the declaration on every run: a declared state answered `Unreachable`, or an
undeclared state answered with an instance, is `Failed`.

Where a state exists only after the operating system acts BETWEEN calls it makes into the process — a job the
upload extension creates is uploaded only after its `process()` call returns — a binding MAY enter that state
across calls: it prepares in earlier calls through the same recorded seam, keeps the partial recording where the
next call's process can read it, and runs the clause in the call after the last preparation. The preparation's
calls head the clause's block, in call order, and a replay makes them again, in order, before the clause. The state
is still entered by the binding before the clause body, and no clause's state comes from another clause's body.
Where every clause so entered shares one operating-system queue that an implementation drains as a whole, each such
clause SHALL be run alone.

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

#### Scenario: An outcome leaves the process

- **WHEN** a clause asserts that a UUID in an error log reaches the reporting channel's destination redacted,
  and the port returns nothing about what it transmitted
- **THEN** the binding points the implementation at a receiving endpoint it stood up, the handle reads the
  events that endpoint received, and the clause judges the delivered event, never which reporting call ran

#### Scenario: A negative outcome that leaves the process

- **WHEN** a clause asserts that a stimulus transmits nothing, and delivery takes an unbounded time
- **THEN** the clause follows the stimulus with a sentinel it waits for, and judges only what was delivered
  before the sentinel, rather than waiting a fixed time and treating silence as absence

#### Scenario: A state the OS reaches only between calls

- **WHEN** a clause needs a transfer the upload extension created in an earlier `process()` call and the OS has
  since settled
- **THEN** its binding creates the transfer in one call and returns, the clause runs in the next call over the
  settled transfer, and the recording's block holds the creating call before the clause's own

### Requirement: Hosts are a closed set of what changes reachable states

A host SHALL be a value of a closed `Host` enum whose identity is platform × process kind × entitlements —
the attributes that change which states a binding can reach, and that are known statically. Operating
system version, device model, toolchain and date SHALL NOT be host identity; they are provenance recorded
with a run. The enum SHALL hold only hosts that some binding names: `JVM`, `IOS_SIM_KEXE`, `IOS_SIM_APP`,
`IOS_DEVICE_APP` and `IOS_DEVICE_PHOTOKIT_EXT`. An app extension is a process kind of its own — the operating
system launches it, bounds its run, and invokes it through an entry only it receives — so each extension
type is a host of its own, named for the type rather than for extensions in general. An authorization granted to the process from outside it — a photo grant — is not host
identity either; it is a precondition of a run ("An authorization the process cannot give itself is a
precondition of the run").

An external service a binding launches or reaches — the real backend served locally for a test — SHALL
NOT be a host. It is part of the implementation under contract, and the binding's host is the process
the binding runs in. Where the port's client is the same in every binding and only the service behind it
differs, the service decides the binding's kind: a stand-in service is `Fake`, the real one run for real
is `Live`.

That rule SHALL apply only where the clauses assert the **service's answers**. An endpoint a binding stands
up **only to receive** what the implementation transmits, and reads **only to observe** it, SHALL be part
of the observation handle rather than a stand-in service: it SHALL NOT decide the binding's kind, and no
clause SHALL assert anything the endpoint decides. Such an endpoint SHALL NOT be more lenient than the
production service on a limit that service is measured to enforce, so a clause cannot pass against it with a
payload production would refuse.

An endpoint a binding stands up to **answer** what the implementation transmits, with an answer the
clause's setup chose — an upload receiver answering 200, 403 or 500 by path — SHALL likewise NOT decide the
binding's kind, provided no clause asserts that answer: the answer is a **stimulus** that puts the
implementation into the clause's state, as a seeded asset is, and the obligations under contract remain the
implementation's (what it records, acknowledges and re-presents). A service whose answers are themselves what
the clauses assert remains the stand-in of the rule above.

The known host matrix, which the next binding starts from:

| host | process | Keychain, as measured | App-Group container, as measured | photo access |
|---|---|---|---|---|
| `JVM` | JVM test | none | none | none |
| `IOS_SIM_KEXE` | Kotlin/Native `test.kexe` spawned by `simctl`, unentitled | every `SecItem*` call answers `-25291` (`errSecNotAvailable`) | the lookup answers `nil` | reads `DENIED` (`PHAuthorizationStatus` 2), measured 2026-09-23 on an iOS 26.5 simulator; no route to a grant, because the process has no bundle identifier |
| simulator `.xctest` (unbound) | Swift test bundle, no `TEST_HOST` | answers `-34018` (`errSecMissingEntitlement`) | not measured | not measured |
| `IOS_SIM_APP` | the rig build of the app bundle on a simulator, ad-hoc signed with the App Group only | `-34018` to an explicit-group query: `simulator.entitlements` omits `keychain-access-groups` (measured 2026-09-22, iOS 26.2) | available under the ad-hoc signature `scripts/sim-sign` applies; an unsigned build has none (measured 2026-08-09) | granted before launch by `applesimutils`; `simctl privacy grant photos` writes a TCC row PhotoKit does not consult (measured 2026-08-25, iOS 26.2); no partial grant exists on a simulator |
| `IOS_DEVICE_APP` | the entitled app on a device | accessible | available | whatever a person set; a partial grant is reachable only here |
| `IOS_DEVICE_PHOTOKIT_EXT` | the upload extension process on a device, launched by the operating system and run in a rig build | not measured | available — the extension's ledger, config and log live there, and a run is requested and answered through it | the app's grant; the OS invokes it under a partial grant when a full-grant registration survived (measured 2026-09-21). One `process()` call runs about 60 s and is then killed with no termination notice; after a killed call the OS backs off 6–11 min (measured 2026-09-23, iOS 26.6) |

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

#### Scenario: A receiving endpoint observes a real reporting channel

- **WHEN** a binding drives the real reporting adapter and SDK against an ingest endpoint it stood up in the
  test process, and every clause judges what the adapter emitted
- **THEN** its kind is `Live`, because the endpoint answers nothing a clause asserts, and the endpoint
  refuses an event over the size the production ingest is measured to refuse

#### Scenario: The upload extension is its own host

- **WHEN** a binding runs inside the upload extension's `process()` call on a device
- **THEN** its host is `IOS_DEVICE_PHOTOKIT_EXT`, never `IOS_DEVICE_APP`, although the app process can call
  the same platform API: production calls it only from the extension, so only the extension's answers are
  evidence about production

#### Scenario: A receiver answers a clause's setup

- **WHEN** a binding points upload jobs at an endpoint it stood up, which answers each with the status the
  clause's setup chose, and no clause asserts that answer
- **THEN** the binding stays `Live`, because the answer is a stimulus that puts the operating system's queue
  into the clause's state

### Requirement: A recording is one committed plain-text file per contract and host

A recording SHALL live at `test/contracts/recordings/<Contract>@<HOST>.rec` as line-oriented text, or at
`<Contract>@<HOST>.<GRANT>.rec` where the binding declares the photo grant it runs under ("An authorization the
process cannot give itself is a precondition of the run") — one file per grant, because one run holds one
grant and a host may be recorded under several. A contract whose bindings declare no grant keeps the
unsuffixed name. The file carries a provenance header naming the contract, host, grant where declared, device model, operating-system version, build number,
Kotlin version and date, followed by one `[CLAUSE_ID]` block per clause, sorted by id, of `call -> answer`
lines. Each run SHALL overwrite the file; its history is the repository's history. The in-app run SHALL
return the file content verbatim, and it SHALL be committed without editing.

#### Scenario: A re-recording changes one answer
- **WHEN** a device run is repeated and one answer differs
- **THEN** the committed diff is confined to that clause's block and the header's provenance

#### Scenario: One host recorded under two grants

- **WHEN** a contract is recorded on the device app once under a full grant and once under a partial grant
- **THEN** two files exist, `<Contract>@IOS_DEVICE_APP.GRANTED.rec` and `<Contract>@IOS_DEVICE_APP.LIMITED.rec`,
  each replayed by a binding declaring that grant, and neither run overwrites the other

### Requirement: The device run is reached through the rig and contained at compile time

The in-app runner SHALL be reachable only through the rig control channel and SHALL be present only in a
build made with `-Psnapsync.rig=true`; a production build SHALL contain none of the contract module, the
device bindings or the recorder. A device run SHALL answer with the recording and the live outcome table.

A host the rig cannot reach — an app extension, which runs only when the operating system invokes it and
only for as long as the system allows — SHALL be run through the App Group both processes share: the rig's
contract verb, naming that host, SHALL write a run request there and cause the system to invoke the
extension; the rig build's extension SHALL run the requested contract **instead of** its production work,
write the recording and outcome table back, and return; and the verb SHALL answer that file verbatim, or a
distinct timeout status and no recording when none arrives within its bound. The extension's part SHALL be
contained exactly as the rest: present only under the same property.

#### Scenario: A production build
- **WHEN** the app is built without the rig property
- **THEN** no contract, binding or recorder source is on the compile path

#### Scenario: Recording on the device
- **WHEN** an operator calls the rig's contract verb for `SecureStore` on an entitled device
- **THEN** it runs every clause in-app and answers with the recording text and each clause's live outcome

#### Scenario: Recording inside the upload extension

- **WHEN** an operator calls the rig's contract verb for the upload-job contract naming the extension host
- **THEN** the rig writes the run request to the App Group and re-registers the extension, the system invokes
  it, the extension runs the contract in place of its upload cycle and writes the recording back, and the
  verb answers that recording

#### Scenario: The extension never answers

- **WHEN** no result arrives within the verb's bound — the system is backing off, or the run was killed
- **THEN** the verb answers a timeout status and no recording, never a partial one

