# upload-lifecycle — delta

## MODIFIED Requirements

### Requirement: Lifecycle orchestration is tier-neutral and tested

The decision of **which verb fires on which transition** SHALL live in a tier-neutral orchestrator in
`:domain`'s `feature/upload` zone, not in the app composition root, and SHALL be tested in `commonTest`
(running on both JVM and `iosSimulatorArm64`) against fake `UploadProducer`s, so it is exercised on JVM
**and** `iosSimulatorArm64` rather than only inside an iOS process. The orchestrator SHALL translate
membership and permission transitions into `start()`/`stop()` and nothing else: it holds no ledger, no
cursor, and no storage handle, so a lifecycle transition **cannot** destroy dedup state — the seam gives
it no verb that could.

Photo access is **usable** when it is `GRANTED` or `LIMITED`. A producer SHALL be started only when an
event is configured **and** photo access is usable **and** the membership's direction includes upload.
Where more than one producer is composed (capability `ios-url-session-upload`, "Per-version tier
selection"), the orchestrator SHALL start the producer **selected by the current permission** — the
OS-driven producer under `GRANTED`, the app-driven producer under `LIMITED` (the OS never invokes the
extension under a partial grant; capability `ios-photokit-upload`) — and SHALL hold the
exactly-one-started invariant (see "Exactly one producer started per process"). The orchestrator SHALL
bind the transitions as follows, where the upload arm is enabled exactly when photo access is usable
**and** the configured membership's direction includes upload (`join-event`):

| Transition | Action |
| --- | --- |
| provision / re-provision, arm enabled | `start()` on the permission-selected producer |
| provision / re-provision, access usable but direction is download-only | `stop()` |
| provision / re-provision, access not usable | neither (the grant transition will drive it) |
| transition to usable access (`GRANTED` or `LIMITED`), arm enabled | `start()` on the permission-selected producer |
| transition between usable states (`GRANTED` ↔ `LIMITED`), arm enabled | switch: `stop()` the outgoing producer, then `start()` the incoming one |
| transition to usable access, **no event configured** | neither |
| leave | `stop()` |

Leave SHALL be `stop()` plus clearing the configured event, and nothing more.

**No membership, no arm.** "The *configured membership's* direction includes upload" is false when there
is no configured membership, so a transition to usable access with no event configured SHALL fire **neither**
verb. The orchestrator SHALL therefore read the membership's upload posture as a **three-valued** seam —
includes-upload / excludes-upload / **no membership** — collapsing it to a two-valued "enabled" flag in
the composition root is what previously answered *enabled* for an absent membership. That decision is
behavior and SHALL live in the tested orchestrator, like every other row of this table; the root SHALL
contribute only a projection of the current config, with no defaulting of its own.

This is not a nicety. Photo access can be usable while no event is configured — the join gate's
photo-access explainer raises the system dialog **before** the join is confirmed (`join-event`), and a
grant arriving there must not start a producer, because `join-event` requires that "no config is saved
and **no upload producer is enabled** until the user confirms". The membership-less start is also
reachable from a bare Settings grant after a leave. On the app-driven tier a start with no membership
arms a self-re-submitting `BGProcessingTask` heartbeat for an event that does not exist; both tiers'
cycles then skip on the absent config, so the work is inert but the wake is not.

#### Scenario: Provisioning with access already granted starts the producer

- **WHEN** an event is provisioned while photo access is `GRANTED` and the direction includes upload
- **THEN** the orchestrator calls `start()` on the OS-driven producer where composed (else the app-driven one), and calls no verb that destroys dedup state

#### Scenario: Provisioning under a limited grant starts the app-driven producer

- **WHEN** an event is provisioned while photo access is `LIMITED` and the direction includes upload
- **THEN** the orchestrator calls `start()` on the app-driven producer, and the OS-driven producer is not started

#### Scenario: A download-only membership stops the producer

- **WHEN** an event is provisioned while photo access is usable and the direction is download-only
- **THEN** the orchestrator calls `stop()`, and the ledger is left intact

#### Scenario: Provisioning without access defers to the grant

- **WHEN** an event is provisioned while photo access is neither `GRANTED` nor `LIMITED`
- **THEN** the orchestrator calls neither verb, and a later transition to usable access calls `start()` on the permission-selected producer

#### Scenario: A permission flip switches producers stop-first

- **WHEN** photo access transitions from `GRANTED` to `LIMITED` (or back) while an upload-inclusive membership is configured
- **THEN** the orchestrator stops the outgoing producer before starting the incoming one, and at no point are both started

#### Scenario: A grant with no event configured arms nothing

- **WHEN** photo access transitions to usable access while no event is configured
- **THEN** the orchestrator calls neither `start()` nor `stop()`, and no background wake is armed

#### Scenario: The join that follows such a grant is what arms the producer

- **WHEN** photo access transitions to usable access with no event configured, and the user then confirms a join whose direction includes upload
- **THEN** the provision transition calls `start()` — the producer is armed at the join, not at the grant

#### Scenario: Leaving stops without wiping

- **WHEN** the user leaves the event
- **THEN** the orchestrator calls `stop()` and the configured event is cleared, while the ledger remains intact so a later join re-uploads nothing already stored

## REMOVED Requirements

### Requirement: Exactly one producer per process

**Reason**: The construction-time exclusion this requirement mandated cannot express a
permission-dependent mechanism choice: on iOS ≥26.1 the correct producer depends on the **current**
grant (the OS never invokes the PhotoKit extension under `.limited` — measured, capability
`ios-photokit-upload`), and permission changes at runtime while composition happens once per process.
The invariant it protected — one `LedgerWriter` at a time — is preserved by its replacement.

**Migration**: superseded by "Exactly one producer started per process" (below), which moves the
mutual exclusion from construction-time (structural) to start-time (behavioral, guarded by
`architecture-guards`). The tier-force flag's posture is carried over.

## ADDED Requirements

### Requirement: Exactly one producer started per process

At most one `UploadProducer` SHALL be started at any time where the composition constructs more than
one (iOS ≥26.1 constructs both the OS-driven and the app-driven producer; below 26.1 only the
app-driven one exists), and the tier-neutral orchestrator SHALL be the only component
that starts or stops either. A mechanism switch SHALL be **stop-then-start**: the outgoing producer's
`stop()` completes before the incoming producer's `start()` — the OS-driven producer's `stop()` is what
deregisters the extension, which is what actually prevents a second `LedgerWriter` over the App-Group
ledger (`sync-ledger`).

This replaces the prior structural exclusion (only one producer constructed) because the mechanism
choice is now an input of **runtime** permission, which no once-per-process construction decision can
express. The invariant's essence — one writer at a time — is unchanged; its enforcement moves from the
compiler to a `:test:architecture` guard (capability `architecture-guards`). The development
tier-force flag retains its meaning: forcing the app-driven tier SHALL NOT register the PhotoKit
extension.

#### Scenario: Only the permission-selected producer runs
- **WHEN** the app runs on iOS ≥26.1 with both producers composed
- **THEN** at most one producer is started at any time, selected by current permission, and the other's
  mechanism is not invoked

#### Scenario: The switch is stop-then-start
- **WHEN** the orchestrator switches producers on a permission flip
- **THEN** the outgoing producer is stopped (the OS-driven one deregistering its extension) before the
  incoming producer starts

#### Scenario: Forcing the app-driven tier does not enable the extension
- **WHEN** the app-driven tier is forced on a device whose OS supports the OS-driven tier
- **THEN** the PhotoKit upload extension is not registered, and only the app-driven producer is started
