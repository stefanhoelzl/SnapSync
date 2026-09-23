## MODIFIED Requirements

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

A service a binding stands up only to put the implementation into a clause's state — an endpoint that
answers an upload with the status the clause's setup chose — SHALL NOT make the binding `Fake`, provided no
clause asserts what that service answered: its answer is a **stimulus**, as a seeded asset is, and the
obligations under contract remain the implementation's (what it records, acknowledges and re-presents). A
service whose answers are themselves the port's obligations — a backend's refusal, an echoed window — is
the stand-in the previous paragraph means, and makes the binding `Fake`.

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

