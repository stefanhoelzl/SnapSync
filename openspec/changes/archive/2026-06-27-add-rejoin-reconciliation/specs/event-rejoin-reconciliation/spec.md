## ADDED Requirements

### Requirement: Reconciliation gate before enabling uploads

The system SHALL run a join reconciliation before enabling the background-upload producer — at every
enable site (a permission grant and a (re)provision) — and only when all hold: an event is
configured, the ledger has **no rows**, and a join has **not been settled in the current process**.
When any condition fails (no event, a non-empty ledger, or a join already settled this process) the
system SHALL NOT fetch, enumerate, or seed, and SHALL proceed directly to enabling the producer. The
ledger's emptiness SHALL be the only persisted "needs join" signal; there SHALL be no persistent
"joined" marker outside the ledger.

#### Scenario: Empty ledger with a configured event triggers a join
- **WHEN** the enable path runs with an event configured, an empty ledger, and no join settled this process
- **THEN** a join reconciliation runs before the producer is enabled

#### Scenario: Non-empty ledger skips the join
- **WHEN** the enable path runs with an event configured and a ledger that already holds rows
- **THEN** no fetch, enumeration, or seeding occurs and the producer is enabled directly

#### Scenario: A join already settled this process does not re-run
- **WHEN** the enable path runs again in the same process after a join was settled (succeeded or failed)
- **THEN** no new join reconciliation runs

### Requirement: Event file list seam

The system SHALL define an `EventFilesSource` seam whose `list(eventId)` returns a `Result` of the
event's stored files — each carrying at least the `filename` and `lastModified` — obtained from the
backend per-event listing (`GET /event/<id>/files`) over HTTPS. The seam SHALL surface failures as a
failed `Result` (never a thrown error to the caller), so the join can reduce them into state. A
settable/fake implementation SHALL exist for tests; the iOS implementation SHALL use an HTTP client
against the compile-time device-facing host.

#### Scenario: Successful listing returns the entries
- **WHEN** the backend returns the event's files
- **THEN** `list(eventId)` yields a success `Result` carrying one entry per stored file

#### Scenario: Upstream failure yields a failed Result
- **WHEN** the backend request fails (network error, non-2xx, timeout)
- **THEN** `list(eventId)` yields a failed `Result` and does not throw to the caller

### Requirement: Join reconciliation seeds already-stored photos as completed

A triggered join SHALL: set status `Joining`; fetch the event file list; enumerate the local photo
library for each resource's `(filename, assetId, version)`; compute the matched subset of local
resources whose `filename` equals a listed filename; seed those as `COMPLETED` at the **local**
`version` via a single atomic ledger reset (`resetTo`), with `updatedAt` taken from the matching
listed entry's `lastModified`; clear the discovery cursor; then set status `Joined`. Matching SHALL be
by `filename` equality only (no size or timestamp comparison). Local resources with no matching listed
filename SHALL NOT be seeded (the producer uploads them later). The seed SHALL run with the producer
disabled.

#### Scenario: An already-stored photo is seeded completed at its local version
- **WHEN** a local resource's filename appears in the event file list
- **THEN** the ledger holds a `COMPLETED` row for that resource at the local `version`

#### Scenario: A locally-present photo not in the list is not seeded
- **WHEN** a local resource's filename does not appear in the event file list
- **THEN** no row is seeded for it and it remains eligible for upload

#### Scenario: Seeding clears the discovery cursor
- **WHEN** a join seeds the ledger
- **THEN** the discovery cursor is cleared so the producer performs a full re-enumeration

#### Scenario: A listed filename with no local match seeds nothing
- **WHEN** the event file list contains a filename that matches no local resource
- **THEN** no row is seeded for that filename

### Requirement: Seeded version matches the producer's recomputed version

The filename/version derivation used to seed SHALL be the single shared library-enumeration
derivation that the upload producer also uses, so a seeded `COMPLETED` row matches a later
`ResourceChanged` for the same resource and the producer treats it as `AlreadyUploaded` (no upload
job). A photo edited between its original upload and the re-join is seeded at the new local version
and is therefore **not** re-uploaded (accepted).

#### Scenario: Seeded resource is skipped by the producer
- **WHEN** the producer later enumerates a resource that the join seeded `COMPLETED` at the same version
- **THEN** the producer's decision is `AlreadyUploaded` and it creates no upload job

### Requirement: Status reflects the seed immediately on join

The seed SHALL make the status projection reflect the seeded completed count **as soon as the join
succeeds**, without the background producer running (its invocation is OS-scheduled and cannot be
forced). The atomic reset SHALL signal the ledger change so the status snapshot re-reads.

#### Scenario: Status shows seeded counts at Joined without a producer run
- **WHEN** a join seeds N photos and reaches `Joined`, and the producer has not run
- **THEN** the status projection reports `completed = N` for the seeded photos against the library total

### Requirement: Join status seam and states

The system SHALL define an `EventStatusSource` seam exposing the current `EventStatus` as a
`StateFlow`, with states `Idle`, `Joining`, `JoinFailed`, and `Joined`. The status SHALL be `Joining`
while the list fetch and seed are in flight, `Joined` after a successful seed, and `JoinFailed` when
the list fetch fails. The presentation layer SHALL consume this seam (see `setup-gate` and
`sync-status-screen`).

#### Scenario: Joining during the fetch and seed
- **WHEN** a join is in flight
- **THEN** `EventStatus` is `Joining`

#### Scenario: Joined after a successful seed
- **WHEN** a join completes its seed
- **THEN** `EventStatus` is `Joined`

#### Scenario: JoinFailed on a list-fetch failure
- **WHEN** the list fetch fails during a join
- **THEN** `EventStatus` is `JoinFailed`

### Requirement: Block until success, no auto-retry, re-scan to retry

The producer SHALL NOT be enabled until a join succeeds. A failed join SHALL **settle** the join for
the current process: the system SHALL NOT automatically retry on foreground, on a timer, or on a
network change. A QR re-scan / config deeplink for the configured event SHALL clear the
settled-this-process flag and make exactly one fresh attempt; a fresh process launch SHALL make one
attempt. The settled flag SHALL be in-memory only (it does not persist across process death).

#### Scenario: Failure does not enable the producer
- **WHEN** a join fails
- **THEN** the producer is not enabled and the status remains `JoinFailed`

#### Scenario: No automatic retry within the process after a failure
- **WHEN** a join has failed and the app foregrounds or time passes
- **THEN** no new join attempt runs automatically

#### Scenario: A re-scan retries the join
- **WHEN** the user re-scans the event QR after a `JoinFailed`
- **THEN** the settled flag clears and one fresh join attempt runs

### Requirement: Event switch versus re-join

The system SHALL compare a scanned / deeplinked eventId to the persisted config eventId. When it
**equals** the current event and the ledger is non-empty, provisioning SHALL be a no-op (no ledger
reset, no re-seed, the producer is left as is). When it **differs**, the system SHALL reset the
ledger to empty and reconcile for the new event (which then triggers the gate via the now-empty
ledger).

#### Scenario: Re-scan of an already-joined event is a no-op
- **WHEN** the scanned eventId equals the configured one and the ledger holds rows
- **THEN** the ledger is not reset, no re-seed occurs, and the producer stays enabled

#### Scenario: A different event resets and reconciles
- **WHEN** the scanned eventId differs from the configured one
- **THEN** the ledger is reset to empty and a join reconciliation runs for the new event
