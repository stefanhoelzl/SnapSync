## MODIFIED Requirements

### Requirement: Hosts are a closed set of what changes reachable states

A host SHALL be a value of a closed `Host` enum whose identity is platform × process kind × entitlements —
the attributes that change which states a binding can reach, and that are known statically. Operating
system version, device model, toolchain and date SHALL NOT be host identity; they are provenance recorded
with a run. The enum SHALL hold only hosts that some binding names: `JVM`, `IOS_SIM_KEXE`, `IOS_SIM_APP`
and `IOS_DEVICE_APP`. An authorization granted to the process from outside it — a photo grant — is not host
identity either; it is a precondition of a run ("An authorization the process cannot give itself is a
precondition of the run").

The known host matrix, which the next binding starts from:

| host | process | Keychain, as measured | photo access |
|---|---|---|---|
| `JVM` | JVM test | none | none |
| `IOS_SIM_KEXE` | Kotlin/Native `test.kexe` spawned by `simctl`, unentitled | every `SecItem*` call answers `-25291` (`errSecNotAvailable`) | reads `DENIED` (`PHAuthorizationStatus` 2), measured 2026-09-23 on an iOS 26.5 simulator; no route to a grant, because the process has no bundle identifier |
| simulator `.xctest` (unbound) | Swift test bundle, no `TEST_HOST` | answers `-34018` (`errSecMissingEntitlement`) | not measured |
| `IOS_SIM_APP` | the rig build of the app bundle on a simulator, ad-hoc signed with the App Group only | `-34018` to an explicit-group query: `simulator.entitlements` omits `keychain-access-groups` (measured 2026-09-22, iOS 26.2) | granted before launch by `applesimutils`; `simctl privacy grant photos` writes a TCC row PhotoKit does not consult (measured 2026-08-25, iOS 26.2); no partial grant exists on a simulator |
| `IOS_DEVICE_APP` | the entitled app on a device | accessible | whatever a person set; a partial grant is reachable only here |
| device extension (unbound) | the upload extension process | not measured | the app's grant; the OS invokes it under a partial grant when a full-grant registration survived (measured 2026-09-21) |

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

#### Scenario: The simulator app is its own host

- **WHEN** a binding runs inside the app bundle on a simulator
- **THEN** its host is `IOS_SIM_APP`, never `IOS_SIM_KEXE` and never `IOS_DEVICE_APP`, because its bundle
  identifier is what makes a photo grant reachable and its missing Keychain entitlement is what makes the
  device's Keychain states unreachable

## ADDED Requirements

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
