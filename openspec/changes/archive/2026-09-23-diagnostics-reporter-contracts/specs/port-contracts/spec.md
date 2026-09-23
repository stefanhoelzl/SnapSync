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

The same rule SHALL apply to a **build configuration value** the adapter otherwise reads from its bundle —
a reporting destination in place of the one a release build bakes. An adapter constructed with that value
injected SHALL count as a real implementation, and the bundle lookup it bypasses SHALL NOT be counted as
covered. The injecting constructor SHALL be `internal` to the adapter's module, so no shipped composition can
supply a value of its own choosing. A clause about the value being **absent** SHALL be entered with the
production default, so the host's own bundle answers.

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

#### Scenario: A reporting destination is injected

- **WHEN** a live binding on `IOS_SIM_KEXE` constructs the reporting adapter with a destination on the
  loopback interface for a configured state
- **THEN** its clauses count as run against a real implementation, and none of them counts as coverage of
  reading the destination from the bundle

#### Scenario: An unconfigured build is the host's own answer

- **WHEN** a live binding on `IOS_SIM_KEXE` enters the reporting adapter's unconfigured state
- **THEN** it constructs the adapter with its production default, whose bundle lookup the test executable —
  which carries no deployment configuration — answers with nothing, rather than passing an absent
  destination itself

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

That rule SHALL apply only where the clauses assert the **service's answers**. An endpoint a binding stands
up **only to receive** what the implementation transmits, and reads **only to observe** it, SHALL be part
of the observation handle rather than a stand-in service: it SHALL NOT decide the binding's kind, and no
clause SHALL assert anything the endpoint decides. Such an endpoint SHALL NOT be more lenient than the
production service on a limit that service is measured to enforce, so a clause cannot pass against it with a
payload production would refuse.

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

#### Scenario: A receiving endpoint observes a real reporting channel

- **WHEN** a binding drives the real reporting adapter and SDK against an ingest endpoint it stood up in the
  test process, and every clause judges what the adapter emitted
- **THEN** its kind is `Live`, because the endpoint answers nothing a clause asserts, and the endpoint
  refuses an event over the size the production ingest is measured to refuse
