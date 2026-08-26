## MODIFIED Requirements

### Requirement: Stale-asset collection

The asset phase SHALL collect a byte object under `files/devices/<deviceId>/` if and only if **(a)** no
surviving event references its key — through any membership, `active` or `departed` — and **(b)** its
storage `DateCreated` (upload time) is **earlier than** `min(startsAt)` taken over the events the device is
an **active** member of among surviving events, where `min` over **no** such events is `+∞`. There SHALL be
no wall-clock age threshold: a live upload for any active event was uploaded at or after that event's
start, hence at or after the floor, so it is never collected.

When a byte is collected, its `resources` row SHALL be deleted **before** the byte object. The order is
load-bearing:

- row then byte — a crash leaves an orphan byte, still unreferenced and still below the floor, so the next
  run collects it. Self-healing.
- byte then row — a crash leaves a row asserting `uploaded = 1` for bytes that no longer exist. That
  residue is inert while nothing reads the row for dedup, and becomes a silently un-re-uploadable photo the
  moment something does.

A device that holds **no membership of any state** in a surviving event SHALL additionally have its
`devices` row collected — but **only once no device token minted for it can still verify**, which the row's
recorded token expiry states (capability `device-attestation`). The row is the device's whole global
record; there is no second object beside it.

The expiry condition is **forcing, not tidiness**. A device's token is verified from its signature alone,
so it keeps working for its full lifetime whether or not the backend still holds the attestation behind it.
Collecting the row earlier therefore deletes the record backing a credential the device is still using: its
next device-scoped write is refused, and it recovers by completing a full Apple attestation — the throttled
path. Because this sweep runs nightly and the device is still orphaned the following night, that recurs for
as long as it stays orphaned, once per launch-day, with no error and no user-visible symptom until Apple
throttles it. Waiting for the expiry costs nothing: past it the device cannot make a gated call anyway.

The phase SHALL identify its candidates by query rather than by enumerating a device roster. A returning
device re-registers its push token on its next launch or join and re-attests on demand.

#### Scenario: A pre-switch leftover byte is collected

- **WHEN** a byte is unreferenced by any surviving event and its upload time is earlier than the start of
  every surviving event the device is active in
- **THEN** the sweep deletes that byte, having first deleted its `resources` row

#### Scenario: A live upload is retained

- **WHEN** a byte is unreferenced but its upload time is at or after the start of an active event the
  device belongs to
- **THEN** the sweep retains the byte (it is at or above the device's floor)

#### Scenario: A referenced byte is retained

- **WHEN** a byte's key is named by a surviving event's assets
- **THEN** the sweep retains the byte regardless of its upload time

#### Scenario: A crash between the two deletions is self-healing

- **WHEN** the sweep deletes a resource row and is killed before deleting its byte
- **THEN** the byte remains unreferenced and below the floor, and the next run collects it

#### Scenario: A fully-orphaned device with a dead credential is collected whole

- **WHEN** a device holds no membership in any surviving event and its recorded token expiry has passed
- **THEN** every unreferenced byte under `files/devices/<deviceId>/` and its `devices` row are deleted

#### Scenario: A fully-orphaned device that may still hold a working token is retained

- **WHEN** a device holds no membership in any surviving event but its recorded token expiry is in the
  future
- **THEN** its `devices` row is retained, so the device is not driven into a re-attestation it does not need

#### Scenario: A departed member's bytes are retained while its event survives

- **WHEN** a device has left an event that has not yet expired, so that event's assets still reference its
  bytes
- **THEN** those bytes are retained until that event is deleted

### Requirement: Dry-run, best-effort, and a run summary

The sweep SHALL support a **dry-run** mode that logs every event, **tombstone**, and asset it *would* delete
and deletes nothing. A real run SHALL delete **best-effort** per object — a single delete failure SHALL be
logged and SHALL NOT abort the run (deletes are idempotent and `404`-tolerant) — and SHALL emit a
**summary** over three tiers, each reporting the count **deleted** and the count **kept**: **events**,
**devices**, and **files** (byte objects) — with the file tallies additionally carrying the **total byte
size** of each, so the summary reports storage actually reclaimed and not merely an object count.

The **events** tier SHALL count **events**: a reclaimed tombstone is not an event and SHALL appear in
neither the deleted nor the kept count. The two event counts therefore do **not** partition the directories
enumerated under `events/`, and no consumer of the summary SHALL assume they do. A tombstone SHALL contribute
no tier of its own — its reclamation is self-extinguishing, so an events-deleted count that reports only
real events is the signal that it happened.

The summary SHALL also carry a single **error** count (the number of per-object failures tolerated during
the run). When the sweep runs on a GitHub Actions runner (the `GITHUB_STEP_SUMMARY` environment variable is
present), it SHALL additionally render the summary to the job's **Summary** panel; off-CI (the variable
absent) this is a no-op. The run SHALL exit non-zero only on a **systemic** failure (an authentication
failure or an inability to list storage at all), so a stuck sweep is visible while transient per-object
errors are not treated as failures.

#### Scenario: Dry-run deletes nothing

- **WHEN** the sweep runs in dry-run mode
- **THEN** it logs the events, tombstones, and assets it would delete and performs no delete

#### Scenario: Dry-run names each tombstone it would reclaim

- **WHEN** the sweep runs in dry-run mode over a zone holding tombstones
- **THEN** each one is logged individually, so the directories a real run would remove can be inspected
  before any deletion

#### Scenario: A reclaimed tombstone is counted in neither event tier

- **WHEN** a real run reclaims a tombstone
- **THEN** the summary's events-deleted and events-kept counts both exclude it, and the run adds no other
  tier for it

#### Scenario: The summary reports deleted and kept across the three tiers

- **WHEN** a run completes
- **THEN** its summary gives, for events, devices, and files, both the count deleted and the count kept,
  and for files the total byte size of each — plus a single tolerated-error count

#### Scenario: The summary renders to the CI job summary panel

- **WHEN** the sweep runs on a GitHub Actions runner
- **THEN** it also writes the summary to the job's Summary panel; run off-CI, it writes no such panel

#### Scenario: A per-object delete failure does not abort the run

- **WHEN** one object delete fails during a real run
- **THEN** the failure is logged, counted as an error, the sweep continues, and the run still completes
  with its summary

#### Scenario: A systemic failure exits non-zero

- **WHEN** the sweep cannot authenticate or cannot list storage at all
- **THEN** the run exits non-zero
