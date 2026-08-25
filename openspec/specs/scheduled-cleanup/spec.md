# scheduled-cleanup Specification

## Purpose

The one mechanism that reclaims backend storage, and the **only** thing that deletes an event. A
**nightly** job runs two ordered phases against the Bunny storage zone: it deletes every **stale event**
and then collects every **stale asset** (a byte or device record no surviving event still needs).

It reclaims the **directory** too, not only the objects. Bunny keeps a directory after its last object is
removed, so a deleted event leaves a **tombstone** behind — and a tombstone is indistinguishable from a live
event until its marker is read, which is how the sweep once re-deleted and re-counted the same husks every
night forever. Reclaiming them is what lets the run summary be read as a report of work done.

An event is stale when it is past its derived delete-by (`max(createdAt, startsAt) + lifetimeSeconds`,
capability `event-limits` — **the guarantee, which nothing can prevent**), or **empty** (ever joined, with
no active member left), or incomplete. Emptiness is **opportunistic reclamation, not a promise**: a leave
whose backend `DELETE` never lands keeps a manifest active, so an abandoned event may never empty. Nothing
— spec, client behaviour, or user-facing copy — may be written as if it were assured.

It runs **outside** the Edge Script. Bunny Edge Scripting has no scheduler and caps a request at 50
subrequests / 30 s CPU, so a whole-storage sweep — thousands of storage calls — cannot run there. The job
runs on a GitHub Actions runner and talks to storage **directly**, importing the Edge Script's own storage
helpers, lifecycle rules, and config constants so the two can never diverge.

It announces nothing. The sweep holds the storage `AccessKey` and no other credential, and makes no request
to the Edge Script at all. A notify-before-delete once existed and was removed: the notify channel carries a
semantic-free "something changed, go sync" payload — the opposite of what a deletion means — and it had to be
dispatched milliseconds before the deletes it described, so devices woke to an already-deleted event and
burned a scarce background wake syncing against it. Members instead discover the deletion on their own next
foreground details fetch, which is the only context where acting on it is safe (capability `leave-event`).

Reclamation latency of up to one nightly cycle is accepted, and the sweep closes the two leaks the prior
specs accepted — untouched-expired events and the abandon-leak of devices that vanish without a clean leave.

Decision record: `changes/archive/2026-07-21-nightly-cleanup`;
`changes/archive/…-decouple-event-window-from-lifetime` (deadline-or-empty predicate; notify removed);
`changes/archive/2026-07-26-prune-swept-event-directories` (tombstone reclamation; why the recursive delete
is confined to husks and never applied to a device byte partition; the measured bunny listing behaviour).
## Requirements
### Requirement: Scheduled runner with direct storage access

The sweep SHALL run as a **scheduled GitHub Actions job** (a nightly cron plus `workflow_dispatch`),
never inside the Edge Script — the platform has no scheduler, and its per-request 50-subrequest / 30 s
CPU caps make a whole-storage sweep impossible in-edge. The job SHALL operate on Bunny storage
**directly**, authenticating with the storage-zone `AccessKey` (`BUNNY_STORAGE_ACCESS_KEY`) supplied as
a **GitHub Actions secret scoped to this workflow** — never the Bunny account API key, and never a
credential granted to the deploy workflow.

The storage-zone `AccessKey` SHALL be the sweep's **only** credential. The sweep SHALL make **no request
to the Edge Script** and SHALL hold no key authorizing one: it reads and deletes storage, and does
nothing else. This is what lets a cleanup job hold no credential against the live device-facing API.

The sweep SHALL import the backend's shared storage helpers, lifecycle classifier, and config constants
(the same modules the Edge Script uses) rather than reimplement layout or lifecycle rules. All storage
reads the sweep's correctness depends on SHALL be served from the storage **main region** (the
`BUNNY_STORAGE_HOST` deployment invariant), so a concurrently-written manifest is visible and the sweep
never deletes data out from under an active device.

#### Scenario: The sweep runs on a nightly schedule

- **WHEN** the scheduled time arrives
- **THEN** the GitHub Actions job runs the two-phase sweep against the configured storage zone

#### Scenario: The sweep is manually dispatchable

- **WHEN** an operator dispatches the workflow
- **THEN** it runs the same sweep on demand

#### Scenario: The sweep uses the zone AccessKey, not the account key

- **WHEN** the sweep performs any storage read or delete
- **THEN** the request carries the storage-zone `AccessKey` and never the Bunny account API key

#### Scenario: The sweep never calls the Edge Script

- **WHEN** a full sweep run completes
- **THEN** it has made no request to the device-facing API, and its configuration carries no credential
  that would authorize one

#### Scenario: The sweep shares the Edge Script's lifecycle and layout logic

- **WHEN** the sweep classifies an event or resolves membership
- **THEN** it uses the same imported modules the Edge Script uses, so no second copy of the rules can drift

### Requirement: Two ordered phases — events before assets

The sweep SHALL run the **event phase** to completion before the **asset phase**. The asset phase SHALL
compute the referenced-byte set and every device's retention floor over the events that **survive** the
event phase, so an asset whose only event was just deleted is correctly seen as unreferenced.

Both phases SHALL read their inputs from the database (capability `database`) rather than from storage
listings: the referenced-byte set is a query over surviving events' `event_assets` joined to `resources`,
and each device's floor is a query over the events it is an active member of. Only the byte objects
themselves are enumerated from storage.

#### Scenario: Assets are evaluated against surviving events

- **WHEN** the event phase deletes an event and the asset phase then runs
- **THEN** the asset phase treats that event's assets as gone and evaluates its bytes for collection

#### Scenario: The root set is a query, not a fan-out

- **WHEN** the asset phase computes the referenced-byte set
- **THEN** it issues a query over the surviving events' rows and performs no per-event, per-device manifest
  read

### Requirement: Stale-event deletion

The event phase SHALL delete every **stale event**, and SHALL leave every other event untouched. An event
is stale when **either** of the following holds:

- **past its deadline** — `now` is later than the row's derived delete-by
  (`max(createdAt, startsAt) + lifetimeSeconds`, per capability `event-limits`);
- **empty** — the event has **at least one** membership row and **none** of them is `active`.

An event with **no membership rows at all** SHALL NOT be treated as empty: it has been minted but never
joined (`POST /api/v1/events` always produces a zero-device event, because the creator confirms through the
same join gate a scanned QR uses), and it SHALL survive until its deadline like any other event.

Emptiness is **opportunistic reclamation, not a guarantee**. A leave whose backend request never lands
leaves an active membership behind, so an abandoned event may never empty; the deadline is the only bound
that always holds, and no requirement, client behavior, or user-facing statement SHALL be written as if
emptiness were assured.

Deletion SHALL be a single `DELETE` of the event row, whose `ON DELETE CASCADE` removes its memberships and
their assets atomically (capability `database`). There is no ordering to get right and no partially-deleted
event to observe. The sweep SHALL NOT notify the event's members before deleting it. Deletion SHALL be
idempotent — a re-run over an already-deleted event is a harmless no-op.

The deletion **decision** SHALL be taken inside an interactive transaction, which runs against the primary.
The emptiness rule is the exposed one: a stale replica that had not yet observed a **rejoin** would see a
fully-departed event and delete a live one. The deadline rule reads immutable stamped columns and is
stale-safe by contrast. Read-your-writes has not been measured from the edge (capability `database`).

#### Scenario: An event past its deadline is deleted

- **WHEN** an event's row gives a derived delete-by earlier than `now`
- **THEN** the sweep deletes it, and its memberships and assets go with it

#### Scenario: An emptied event is deleted

- **WHEN** an event has membership rows and every one of them is `departed`
- **THEN** the sweep deletes it, even though its deadline has not passed

#### Scenario: An event with one active member is left intact

- **WHEN** an event within its deadline has at least one `active` membership
- **THEN** the sweep deletes nothing for that event

#### Scenario: A minted-but-never-joined event is not empty

- **WHEN** an event within its deadline has no membership rows at all
- **THEN** the sweep deletes nothing for that event, and it survives until its deadline

#### Scenario: The decision is taken against the primary

- **WHEN** the sweep evaluates an event for deletion
- **THEN** the evaluation runs inside an interactive transaction, so a stale replica cannot cause a live
  event to be deleted

#### Scenario: Deletion sends no notification

- **WHEN** the sweep deletes any stale event
- **THEN** it dispatches no push and makes no notify request; members discover the deletion on their own
  next foreground details fetch (capability `leave-event`)

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

A device that is in **no** surviving event (floor `= +∞`) SHALL additionally have its `device_records` row
and its attestation record `devices/<deviceId>.attest.json` collected — these carry no event date and are
reclaimed only in this fully-orphaned case (a returning device re-registers its push token on its next
launch or join and re-attests on demand).

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

#### Scenario: A fully-orphaned device is collected whole

- **WHEN** a device is an active member of no surviving event
- **THEN** every unreferenced byte under `files/devices/<deviceId>/`, its `device_records` row, and its
  `devices/<deviceId>.attest.json` are deleted

#### Scenario: A departed member's bytes are retained while its event survives

- **WHEN** a device has left an event that has not yet expired, so that event's assets still reference its
  bytes
- **THEN** those bytes are retained until that event is deleted

### Requirement: Dry-run, best-effort, and a run summary

The sweep SHALL support a **dry-run** mode that logs every event, **tombstone**, and asset it *would* delete
and deletes nothing. A real run SHALL delete **best-effort** per object — a single delete failure SHALL be
logged and SHALL NOT abort the run (deletes are idempotent and `404`-tolerant) — and SHALL emit a
**summary** over three tiers, each reporting the count **deleted** and the count **kept**: **events**,
**devices** (a device counted once regardless of how many of its global config/attestation records exist),
and **files** (byte objects) — with the file tallies additionally carrying the **total byte size** of each,
so the summary reports storage actually reclaimed and not merely an object count.

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

