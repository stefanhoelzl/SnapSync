## MODIFIED Requirements

### Requirement: Lifecycle orchestration is tier-neutral and tested

The decision of **which verb fires on which transition** SHALL live in a tier-neutral orchestrator in
`:capability:upload`, not in the app composition root, and SHALL be tested in `commonTest` (running on
both JVM and `iosSimulatorArm64`) against a fake `UploadProducer`. The orchestrator SHALL bind the
transitions as follows, where the upload arm is enabled exactly when photo access is `GRANTED` **and**
the configured membership's direction includes upload (`join-event`):

| Transition | Action |
| --- | --- |
| provision / re-provision, arm enabled | `start()` |
| provision / re-provision, access granted but direction is download-only | `stop()` |
| provision / re-provision, access not granted | neither (the grant transition will drive it) |
| transition to `GRANTED`, arm enabled | `start()` |
| transition to `GRANTED`, **no event configured** | neither |
| leave | `stop()` |

Leave SHALL be `stop()` plus clearing the configured event, and nothing more.

**No membership, no arm.** "The *configured membership's* direction includes upload" is false when there
is no configured membership, so a transition to `GRANTED` with no event configured SHALL fire **neither**
verb. The orchestrator SHALL therefore read the membership's upload posture as a **three-valued** seam —
includes-upload / excludes-upload / **no membership** — collapsing it to a two-valued "enabled" flag in
the composition root is what previously answered *enabled* for an absent membership. That decision is
behavior and SHALL live in the tested orchestrator, like every other row of this table; the root SHALL
contribute only a projection of the current config, with no defaulting of its own.

This is not a nicety. Photo access can be `GRANTED` while no event is configured — the join gate's
photo-access explainer raises the system dialog **before** the join is confirmed (`join-event`), and a
grant arriving there must not start a producer, because `join-event` requires that "no config is saved
and **no upload producer is enabled** until the user confirms". The membership-less start is also
reachable from a bare Settings grant after a leave. On the app-driven tier a start with no membership
arms a self-re-submitting `BGProcessingTask` heartbeat for an event that does not exist; both tiers'
cycles then skip on the absent config, so the work is inert but the wake is not.

#### Scenario: Provisioning with access already granted starts the producer

- **WHEN** an event is provisioned while photo access is `GRANTED` and the direction includes upload
- **THEN** the orchestrator calls `start()`, and calls no verb that destroys durable state

#### Scenario: A download-only membership stops the producer

- **WHEN** an event is provisioned while photo access is `GRANTED` and the direction is download-only
- **THEN** the orchestrator calls `stop()`, and the ledger and discovery cursor are left intact

#### Scenario: Provisioning without access defers to the grant

- **WHEN** an event is provisioned while photo access is not `GRANTED`
- **THEN** the orchestrator calls neither verb, and a later transition to `GRANTED` calls `start()`

#### Scenario: A grant with no event configured arms nothing

- **WHEN** photo access transitions to `GRANTED` while no event is configured
- **THEN** the orchestrator calls neither `start()` nor `stop()`, and no background wake is armed

#### Scenario: The join that follows such a grant is what arms the producer

- **WHEN** photo access transitions to `GRANTED` with no event configured, and the user then confirms a join whose direction includes upload
- **THEN** the provision transition calls `start()` — the producer is armed at the join, not at the grant

#### Scenario: Leaving stops without wiping

- **WHEN** the user leaves the event
- **THEN** the orchestrator calls `stop()` and the configured event is cleared, while the ledger and discovery cursor remain intact so a later join re-uploads nothing already stored
