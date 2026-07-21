# scheduled-cleanup Specification

## Purpose

The one mechanism that reclaims backend storage on a schedule, replacing the removed on-touch expiry
reap (`event-limits`) and last-active-member reap (`event-leave-endpoint`). A **nightly** job runs two
ordered phases against the Bunny storage zone: it deletes every **stale event** (past `endsAt + grace`)
and then collects every **stale asset** (a byte or device record no surviving event still needs).

It runs **outside** the Edge Script. Bunny Edge Scripting has no scheduler and caps a request at 50
subrequests / 30 s CPU, so a whole-storage sweep — thousands of storage calls — cannot run there. The
job runs on a GitHub Actions runner and talks to storage **directly**, importing the Edge Script's own
storage helpers, lifecycle rules, and config constants so the two can never diverge.

Deletion by the sweep *is* expiry: there is no longer an on-touch "expired" state. Reclamation latency
of up to one nightly cycle is accepted, and the sweep also closes the two leaks the prior specs
accepted — untouched-expired events and the abandon-leak of devices that vanish without a clean leave.

Decision record: `changes/archive/2026-07-21-nightly-cleanup`.

## Requirements
### Requirement: Scheduled runner with direct storage access

The sweep SHALL run as a **scheduled GitHub Actions job** (a nightly cron plus `workflow_dispatch`),
never inside the Edge Script — the platform has no scheduler, and its per-request 50-subrequest / 30 s
CPU caps make a whole-storage sweep impossible in-edge. The job SHALL operate on Bunny storage
**directly**, authenticating with the storage-zone `AccessKey` (`BUNNY_STORAGE_ACCESS_KEY`) supplied as
a **GitHub Actions secret scoped to this workflow** — never the Bunny account API key, and never a
credential granted to the deploy workflow. The sweep SHALL import the backend's shared storage helpers,
lifecycle classifier, and config constants (the same modules the Edge Script uses) rather than
reimplement layout or lifecycle rules. All storage reads the sweep's correctness depends on SHALL be
served from the storage **main region** (the `BUNNY_STORAGE_HOST` deployment invariant), so a
concurrently-written manifest is visible and the sweep never deletes data out from under an active
device.

#### Scenario: The sweep runs on a nightly schedule

- **WHEN** the scheduled time arrives
- **THEN** the GitHub Actions job runs the two-phase sweep against the configured storage zone

#### Scenario: The sweep is manually dispatchable

- **WHEN** an operator dispatches the workflow
- **THEN** it runs the same sweep on demand

#### Scenario: The sweep uses the zone AccessKey, not the account key

- **WHEN** the sweep performs any storage read or delete
- **THEN** the request carries the storage-zone `AccessKey` and never the Bunny account API key

#### Scenario: The sweep shares the Edge Script's lifecycle and layout logic

- **WHEN** the sweep classifies an event or resolves membership
- **THEN** it uses the same imported modules the Edge Script uses, so no second copy of the rules can drift

### Requirement: Two ordered phases — events before assets

The sweep SHALL run the **event phase** to completion before the **asset phase**. The asset phase SHALL
compute the referenced-byte set and every device's retention floor over the events that **survive** the
event phase, so an asset whose only event was just deleted is correctly seen as unreferenced.

#### Scenario: Assets are evaluated against surviving events

- **WHEN** the event phase deletes an event and the asset phase then runs
- **THEN** the asset phase treats that event's manifests as gone and evaluates its bytes for collection

### Requirement: Stale-event deletion

The event phase SHALL delete every **stale event** — an event whose marker gives `now > endsAt + grace`
(with `grace` the configured event grace period), or a **legacy** marker missing `endsAt`. For each stale
event the sweep SHALL, in order: **(1)** notify the event's active members best-effort via the Edge
Script's notify route (`event-notify-endpoint`, authorized with the admin key) so their apps learn the
event is gone, then **(2)** delete the event's `metadata.json` marker and every `<deviceId>.json` /
`<deviceId>.left.json` manifest under `events/<eventId>/devices/`. The notify SHALL precede the delete
(once the marker is gone the notify route would `404`); a failed or partial notify SHALL NOT block the
delete. An event that is still **live** or in **grace** (`now <= endsAt + grace`) SHALL be left
untouched. Deletion SHALL be idempotent — a re-run over an already-deleted event is a harmless no-op.

#### Scenario: An expired event is notified then deleted

- **WHEN** an event's marker gives `now > endsAt + grace`
- **THEN** the sweep notifies its active members via the admin-authorized notify route, then deletes its
  marker and all its manifests

#### Scenario: A live or grace event is left intact

- **WHEN** an event's marker gives `now ≤ endsAt + grace`
- **THEN** the sweep deletes nothing for that event

#### Scenario: A legacy marker is deleted

- **WHEN** the sweep reads a marker that carries no `endsAt`
- **THEN** the event is treated as stale and deleted exactly as an expired event

#### Scenario: A failed notify does not block deletion

- **WHEN** the notify fan-out for an expiring event fails
- **THEN** the sweep still deletes the event's marker and manifests

### Requirement: Stale-asset collection

The asset phase SHALL collect a byte object under `files/devices/<deviceId>/` if and only if **(a)** no
surviving event's manifest — active `<deviceId>.json` **or** departed `<deviceId>.left.json` — references
its key, **and (b)** its storage `DateCreated` (upload time) is **earlier than** `min(startsAt)` taken
over the events the device is an **active** member of among surviving events, where `min` over **no**
such events is `+∞`. There SHALL be no wall-clock age threshold: a live upload for any active event was
uploaded at or after that event's start, hence at or after the floor, so it is never collected. A device
that is in **no** surviving event (floor `= +∞`) SHALL additionally have its config object
`devices/<deviceId>.json` and its attestation record `devices/<deviceId>.attest.json` collected — these
carry no event date and are reclaimed only in this fully-orphaned case (a returning device re-registers
its push token on its next launch or join and re-attests on demand).

#### Scenario: A pre-switch leftover byte is collected

- **WHEN** a byte is unreferenced by any surviving manifest and its upload time is earlier than the start
  of every surviving event the device is active in
- **THEN** the sweep deletes that byte

#### Scenario: A live upload is retained

- **WHEN** a byte is unreferenced but its upload time is at or after the start of an active event the
  device belongs to
- **THEN** the sweep retains the byte (it is at or above the device's floor)

#### Scenario: A referenced byte is retained

- **WHEN** a byte's key is named by a surviving event's active or departed manifest
- **THEN** the sweep retains the byte regardless of its upload time

#### Scenario: A fully-orphaned device is collected whole

- **WHEN** a device is an active member of no surviving event
- **THEN** every unreferenced byte under `files/devices/<deviceId>/`, its `devices/<deviceId>.json`, and
  its `devices/<deviceId>.attest.json` are deleted

#### Scenario: A departed member's bytes are retained while its event survives

- **WHEN** a device has left an event that has not yet expired, so its `<deviceId>.left.json` in that
  surviving event still references its bytes
- **THEN** those bytes are retained until that event is deleted

### Requirement: Dry-run, best-effort, and a run summary

The sweep SHALL support a **dry-run** mode that logs every event and asset it *would* delete and deletes
nothing. A real run SHALL delete **best-effort** per object — a single delete failure SHALL be logged and
SHALL NOT abort the run (deletes are idempotent and `404`-tolerant) — and SHALL emit a **summary**
(events deleted, bytes and device records collected, bytes retained by the floor). The run SHALL exit
non-zero only on a **systemic** failure (an authentication failure or an inability to list storage at
all), so a stuck sweep is visible while transient per-object errors are not treated as failures.

#### Scenario: Dry-run deletes nothing

- **WHEN** the sweep runs in dry-run mode
- **THEN** it logs the events and assets it would delete and performs no delete

#### Scenario: A per-object delete failure does not abort the run

- **WHEN** one object delete fails during a real run
- **THEN** the failure is logged, the sweep continues, and the run still completes with its summary

#### Scenario: A systemic failure exits non-zero

- **WHEN** the sweep cannot authenticate or cannot list storage at all
- **THEN** the run exits non-zero

