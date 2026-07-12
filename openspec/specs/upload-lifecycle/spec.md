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
| leave | `stop()` |

Leave SHALL be `stop()` plus clearing the configured event, and nothing more.

#### Scenario: Provisioning with access already granted starts the producer

- **WHEN** an event is provisioned while photo access is `GRANTED` and the direction includes upload
- **THEN** the orchestrator calls `start()`, and calls no verb that destroys durable state

#### Scenario: A download-only membership stops the producer

- **WHEN** an event is provisioned while photo access is `GRANTED` and the direction is download-only
- **THEN** the orchestrator calls `stop()`, and the ledger and discovery cursor are left intact

#### Scenario: Provisioning without access defers to the grant

- **WHEN** an event is provisioned while photo access is not `GRANTED`
- **THEN** the orchestrator calls neither verb, and a later transition to `GRANTED` calls `start()`

#### Scenario: Leaving stops without wiping

- **WHEN** the user leaves the event
- **THEN** the orchestrator calls `stop()` and the configured event is cleared, while the ledger and discovery cursor remain intact so a later join re-uploads nothing already stored

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

