# upload-lifecycle Specification

## Purpose

The **tier-neutral upload arm**: which producer verb fires on which membership transition (provision,
event switch, permission grant, direction change, leave), and the invariant that **no transition ever
destroys durable dedup state**. Each upload tier supplies the mechanism behind a two-verb `UploadProducer`
seam (`start` / `stop`); this capability owns the decision, and it owns it in one tested, platform-free
place.

It exists because the upload lifecycle previously had **no owner**. It was smeared across the two tier
specs and the iOS composition root — a module the project's own hard rule declares wiring-only and
untested — so no contract described it and no test could reach it. When a second upload tier arrived, the
app-driven tier (iOS 18–26.0) inherited a PhotoKit-shaped "disable→enable" re-registration ritual on every
provision. Its *disable* half resolved to a full leave (cancelling transfers and the `BGProcessingTask`
heartbeat, wiping the ledger **and** the discovery cursor) while its *enable* half was a no-op below iOS
26.1. Joining an event therefore tore the upload arm down, started nothing, and re-uploaded the user's
whole post-cutoff library on the next cycle — on the tier every current user runs.

The two-verb seam is the fix, and it is a **structural** one. With no destructive verb to reach, there is
no edge from *provision* to *destruction* to get wrong: the bug is unrepresentable rather than merely
absent. Durable state is device-global dedup (`sync-ledger`, "Event-independent key") and stays true across
a leave, a switch, and a re-join; only a triggered reconciliation's `resetTo` ever re-baselines it
(`event-rejoin-reconciliation`). Selecting exactly one producer per process likewise makes the two tiers'
mutual exclusion structural — the non-selected tier's mechanism is never constructed, so it cannot run and
cannot become a second `LedgerWriter`.

Decision record: `changes/archive/2026-07-12-fix-app-driven-upload-lifecycle`.

## Requirements
### Requirement: Upload producer seam has no destructive verb

The system SHALL express the upload arm as a platform-free `UploadProducer` seam in
`:capability:upload` with exactly **two** verbs:

- `start()` — begin or resume uploading for the currently-configured membership.
- `stop()` — cease uploading. It SHALL NOT destroy durable state: it SHALL NOT clear the ledger, SHALL
  NOT clear the discovery cursor, and SHALL NOT delete stored bytes.

There SHALL be **no** destructive verb on the seam. No lifecycle transition — provision, re-provision,
event switch, permission change, direction change, or leave — SHALL clear the ledger or the discovery
cursor. Durable dedup state is device-global (`sync-ledger`), and divergence from storage is repaired
by reconciliation (`event-rejoin-reconciliation`), never by a lifecycle wipe.

Each tier SHALL supply one `UploadProducer` implementation binding these verbs to its own mechanism.

#### Scenario: The seam exposes no way to destroy dedup state

- **WHEN** the `UploadProducer` seam is inspected
- **THEN** it exposes only `start()` and `stop()`, and no lifecycle caller can clear the ledger or the discovery cursor through it

#### Scenario: Stopping preserves durable state

- **WHEN** `stop()` is called on either tier
- **THEN** in-flight uploads cease, but every ledger row and the discovery cursor are left intact

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

The gate SHALL be reachable by the tier-neutral tests: it lives in `:capability:upload`, not in a composition
root, which the project's hard rule declares wiring-only and untested. Root-placed enforcement is how this
capability's own history records the lifecycle shipping with no owner and no test.

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

### Requirement: Exactly one producer per process

The app SHALL construct **exactly one** `UploadProducer` for the process, selected once at composition
from the OS-version tier gate (`ios-url-session-upload`, "Per-version tier selection"). The
non-selected tier's producer SHALL NOT be constructed, so its mechanism cannot run. This SHALL hold
under the development tier-force flag as well: forcing the app-driven tier on a device that supports
the OS-driven tier SHALL NOT register the PhotoKit upload extension.

This makes the two tiers' mutual exclusion structural rather than a runtime guard, and preserves the
`sync-ledger` single-record-writer invariant (two live producers would mean two `LedgerWriter`s over
one App-Group ledger).

#### Scenario: Only the selected tier's producer exists

- **WHEN** the composition root assembles the upload arm
- **THEN** exactly one `UploadProducer` is constructed, and the other tier's mechanism is never invoked

#### Scenario: Forcing the app-driven tier does not enable the extension

- **WHEN** the app-driven tier is forced on a device whose OS supports the OS-driven tier
- **THEN** the PhotoKit upload extension is not registered, and only the app-driven producer is live

