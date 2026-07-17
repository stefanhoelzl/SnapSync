# upload-lifecycle — delta for move-features-upload-membership-status-trust

## MODIFIED Requirements

### Requirement: Upload producer seam has no destructive verb

The system SHALL express the upload arm as a platform-free `UploadProducer` seam in `:domain`'s
`feature/upload` zone (package `app.snapsync.feature.upload`) with exactly **two** verbs:

- `start()` — begin or resume uploading for the currently-configured membership.
- `stop()` — cease uploading. It SHALL NOT destroy **dedup state**: it SHALL NOT clear the ledger and
  SHALL NOT delete stored bytes.

There SHALL be **no** destructive verb on the seam. No lifecycle transition — provision, re-provision,
event switch, permission change, direction change, or leave — SHALL clear the ledger. Durable dedup state
is device-global (`sync-ledger`), and divergence from storage is repaired by reconciliation
(`event-rejoin-reconciliation`), never by a lifecycle wipe.

The property being defended is **dedup**: the proof that a photo is already in the event. Destroying it
re-uploads a member's whole post-cutoff library — the failure this project exists to prevent. The ledger's
`COMPLETED` rows and the stored bytes are that proof; the **discovery cursor is not**. A cleared cursor
costs one full re-enumeration, which finds nothing new, because dedup lives in the ledger it did not touch.

A tier's `stop()` MAY therefore clear its **discovery cursor**, but **only** as a repair for damage its own
mechanism causes, and **only** where `COMPLETED` rows survive so nothing already stored re-uploads. The
condition is the rule, not the tier: clearing a cursor for tidiness, or where dedup state would not survive
it, remains forbidden. (The PhotoKit tier is the standing instance — the OS's extension-disable wipes every
in-flight job, and `clearRequested()` alone leaves the cleared photos un-rediscoverable behind a settled
cursor. That repair is required by `ios-photokit-upload`, which owns it.)

Each tier SHALL supply one `UploadProducer` implementation binding these verbs to its own mechanism.

#### Scenario: The seam exposes no way to destroy dedup state

- **WHEN** the `UploadProducer` seam is inspected
- **THEN** it exposes only `start()` and `stop()`, and no lifecycle caller can clear the ledger through it

#### Scenario: Stopping preserves dedup state

- **WHEN** `stop()` is called on either tier
- **THEN** in-flight uploads cease, but every ledger row and every stored object is left intact

#### Scenario: A tier may clear its own cursor to repair its own mechanism

- **WHEN** a tier's `stop()` clears its discovery cursor as a repair for jobs its own mechanism wiped, and its `COMPLETED` ledger rows survive
- **THEN** that is permitted: the next cycle re-enumerates fully and creates no upload job for anything already stored

#### Scenario: Clearing a cursor that dedup depends on is still forbidden

- **WHEN** a tier's `stop()` would clear a cursor without its `COMPLETED` rows surviving, or for any reason other than repairing its own mechanism
- **THEN** that is forbidden — the carve-out is conditioned on dedup surviving, not on which tier is asking

### Requirement: Lifecycle orchestration is tier-neutral and tested

The decision of **which verb fires on which transition** SHALL live in a tier-neutral orchestrator in
`:domain`'s `feature/upload` zone, not in the app composition root, and SHALL be tested in `commonTest`
(running on both JVM and `iosSimulatorArm64`) against a fake `UploadProducer`, so it is exercised on JVM
**and** `iosSimulatorArm64` rather than only inside an iOS process. The orchestrator SHALL translate
membership transitions into `start()`/`stop()` and nothing else: it holds no ledger, no cursor, and no
storage handle, so a lifecycle transition **cannot** destroy dedup state — the seam gives it no verb that
could.

A producer SHALL be started only when an event is configured **and** photo access is `GRANTED` **and** the
membership's direction includes upload. The orchestrator SHALL bind the
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
- **THEN** the orchestrator calls `start()`, and calls no verb that destroys dedup state

#### Scenario: A download-only membership stops the producer

- **WHEN** an event is provisioned while photo access is `GRANTED` and the direction is download-only
- **THEN** the orchestrator calls `stop()`, and the ledger is left intact

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
- **THEN** the orchestrator calls `stop()` and the configured event is cleared, while the ledger remains intact so a later join re-uploads nothing already stored

### Requirement: The arm's direction gate lives at the choke point, never at the invoker

An upload arm's participation-direction gate SHALL live at the **choke point** — the one function every
trigger, on every tier, funnels through (`UploadCycle.run()`) — and SHALL NOT be placed at the arm's
**invoker**. No upload job SHALL be created, and no device manifest written, for a membership whose
direction excludes upload, at **any** trigger and on **any** tier.

An invoker-gate is only as sound as its enumeration of invokers, and that enumeration is invalidated
silently by a new tier or a new trigger. This is not hypothetical. `changes/archive/2026-07-07-add-join-direction-mode`,
D3, gated the upload arm by not enabling the producer, reasoning: *"Under `DownloadOnly` the producer is never
enabled, so the OS never invokes the upload extension… No extension code changes."* That holds only where the
OS is the invoker. The app-driven tier (`changes/archive/2026-07-04-add-url-session-upload`) had merged
**three days earlier** and invokes its own cycle from the app process, so a download-only membership uploaded
the member's camera roll on every foreground — while the join gate had promised "you won't share yours". D4 of
the same document gated the **download** arm at its choke point, for reasons it stated explicitly, and is
correct. This requirement supersedes D3.

Placing the gate at the choke point is what makes the cheap mistake impossible. Adding a trigger is one line
in the untested composition root and looks obviously correct; bypassing the choke point means building a
parallel upload path, which nobody does by accident.

The gate SHALL be reachable by the tier-neutral tests: it lives in `:domain`'s `feature/upload` zone, not
in a composition root, which the project's hard rule declares wiring-only and untested. Root-placed
enforcement is how this capability's own history records the lifecycle shipping with no owner and no test.

#### Scenario: A download-only membership creates no upload job at any trigger
- **WHEN** a cycle is driven for a membership whose direction excludes upload — by foreground entry, a
  background task, a silent push, a producer start, or an upload completion
- **THEN** no upload job is created and no device manifest is written, for every one of those triggers

#### Scenario: The gate holds on a tier whose invoker is the app
- **WHEN** the tier in use invokes the upload cycle from the app process rather than being invoked by the OS
- **THEN** the download-only membership still creates no upload job — the gate does not depend on which
  component invokes the cycle

#### Scenario: Stopping the producer is not the gate
- **WHEN** the producer has been stopped for a download-only membership and a trigger subsequently drives a
  cycle
- **THEN** no upload job is created, because the gate is read at the choke point rather than inferred from
  the producer having been stopped
