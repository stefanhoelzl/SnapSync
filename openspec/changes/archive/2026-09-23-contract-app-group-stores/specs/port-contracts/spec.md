## MODIFIED Requirements

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
with a run. The enum SHALL hold only hosts that some binding names; this change introduces `JVM`,
`IOS_SIM_KEXE` and `IOS_DEVICE_APP`.

The known host matrix, which the next binding starts from:

| host | process | Keychain, as measured | App-Group container, as measured |
|---|---|---|---|
| `JVM` | JVM test | none | none |
| `IOS_SIM_KEXE` | Kotlin/Native `test.kexe` spawned by `simctl`, unentitled | every `SecItem*` call answers `-25291` (`errSecNotAvailable`) | the lookup answers `nil` |
| simulator `.xctest` (unbound) | Swift test bundle, no `TEST_HOST` | answers `-34018` (`errSecMissingEntitlement`) | not measured |
| simulator app (unbound) | the app bundle, ad-hoc signed | `-34018` to an explicit-group query: `simulator.entitlements` omits `keychain-access-groups` (measured 2026-09-22, iOS 26.2) | available under the ad-hoc signature `scripts/sim-sign` applies; an unsigned build has none (measured 2026-08-09) |
| `IOS_DEVICE_APP` | the entitled app on a device | accessible | available |
| device extension (unbound) | the upload extension process | not measured | available — the extension's ledger, config and log live there |

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
