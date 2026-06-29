## MODIFIED Requirements

### Requirement: Event file list seam

The system SHALL define a per-device file-listing seam whose `list(deviceId)` returns a `Result` of
the **filenames the device has stored** — the raw object listing under the device's byte partition,
each entry carrying at least its `filename` (the bare `<assetId>-<role>.<ext>`) — obtained from the
backend **per-device** listing (`GET /files/device/<deviceId>`) over HTTPS. This replaces the former
per-event complete-asset listing (`GET /event/<id>/files`): the source of seed truth is now the
device's event-independent byte store, not any single event. The seam SHALL surface failures as a
failed `Result` (never a thrown error to the caller), so the join can reduce them into state. A
settable/fake implementation SHALL exist for tests; the iOS implementation SHALL use an HTTP client
against the compile-time device-facing host.

#### Scenario: Successful listing returns the device's stored filenames

- **WHEN** the backend returns the device's stored objects
- **THEN** `list(deviceId)` yields a success `Result` carrying one entry per stored file, each with its `filename`

#### Scenario: Upstream failure yields a failed Result

- **WHEN** the backend request fails (network error, non-2xx, timeout)
- **THEN** `list(deviceId)` yields a failed `Result` and does not throw to the caller

### Requirement: Join reconciliation seeds already-stored photos as completed

A triggered reconciliation (in the extension) SHALL: fetch the **per-device** file listing
(`list(deviceId)`); for each stored filename **additively upsert** a `COMPLETED` ledger row keyed by
that `filename` and carrying the `assetId` parsed from the filename; and **on success set the
`joinedEventId` marker** to the configured `eventId`. The seed SHALL use plain per-row upserts and
SHALL **never clear** the ledger — it adds and overwrites only the keys present in the listing,
leaving every other existing row untouched. The seed records no timestamp. Because the byte store is
device-global and event-independent, this additive seed both **restores** dedup after a reinstall
(the ledger is empty, so the seed repopulates every globally-stored resource as `COMPLETED`) and
**preserves** it across an event switch (existing `COMPLETED` rows are kept, the listing merely
re-confirms them). A resource that is not in the device's byte store is absent from the listing, is
not seeded, and is uploaded idempotently by the producer (last-write-wins). Setting the marker on
success — even when zero rows were seeded — settles the join so it does not re-trigger.

#### Scenario: A stored resource is seeded completed

- **WHEN** the per-device listing reports stored files `a1-primary.jpg`, `a1-video.mov`
- **THEN** the ledger holds a `COMPLETED` row for each, carrying the `assetId` parsed from the filename, and the marker is set

#### Scenario: The seed never clears existing rows

- **WHEN** the ledger already holds rows for resources absent from the per-device listing and a reconciliation seeds
- **THEN** those pre-existing rows are left untouched (no `clear`/reset occurs) and only the listed filenames are upserted to `COMPLETED`

#### Scenario: Reinstall restores dedup from an empty ledger

- **WHEN** the ledger is empty (a reinstall) and the device's byte store already holds prior resources
- **THEN** the additive seed upserts a `COMPLETED` row for every stored filename, so the producer re-uploads none of them

#### Scenario: A zero-row join still settles

- **WHEN** the per-device listing returns no files for a device with an empty byte store
- **THEN** no rows are seeded but the `joinedEventId` marker is set, so the next cycle does not re-reconcile

#### Scenario: A not-yet-stored resource re-uploads idempotently

- **WHEN** a resource is absent from the per-device listing (never uploaded)
- **THEN** it is not seeded and the producer uploads it (any already-present resource overwritten last-write-wins)

### Requirement: Seeded rows are skipped by the producer

The filenames seeded from the per-device listing SHALL be byte-identical to the keys the upload
producer derives for those resources — both the listing and the producer name a resource by the same
bare `<assetId>-<role>.<ext>` from the **shared gallery enumeration**, with no event scoping — so a
seeded `COMPLETED` row's key matches a later `ResourceChanged` for the same resource. Because an
uploaded resource is immutable, the engine treats any `COMPLETED` key as `AlreadyUploaded` regardless
of content, so the producer never re-uploads a seeded resource. Because the seed is **additive and
device-global**, this skip now holds **across events**: a resource uploaded under one event is seeded
`COMPLETED` and skipped after a switch to any other event, never re-uploaded.

#### Scenario: Seeded resource is skipped by the producer

- **WHEN** the producer later enumerates a resource that the join seeded `COMPLETED`
- **THEN** the producer's decision is `AlreadyUploaded` and it creates no upload job

#### Scenario: Skip holds across an event switch

- **WHEN** a resource uploaded under one event is seeded `COMPLETED` and the device switches to a different event
- **THEN** the producer still decides `AlreadyUploaded` for that resource and re-uploads nothing already in the device byte store

### Requirement: Event switch versus re-join

The extension SHALL compare the configured `eventId` to the persisted `joinedEventId` marker. When
they **match** (a relaunch or re-provision of the already-joined event) the switch is a no-op: no
seed, no re-projection, no marker write. When they **differ** — an event switch, a reinstall with no
marker, or a fresh provision — the extension SHALL **keep** the ledger, the discovery cursor, **and**
the device-global accumulator intact; **additively seed** the ledger from the per-device listing (no
clear, no reset); **re-project** the device manifest (`device.json`) to the **new** event's storage
path; and set the `joinedEventId` marker to the configured `eventId`. No ledger clear and no cursor
reset occur on a switch — the accumulator, ledger, and cursor are all device-global, so a switch is a
re-projection plus an additive re-confirmation, not a reset (the date-filter case where switching to
an event with an **earlier** start needs cursor re-enumeration is deferred with the date filter).
After a **leave** (config absent), the extension SHALL clear the `joinedEventId` marker on its next
cycle while **keeping** the ledger, cursor, and accumulator, so a subsequent provision of any event
runs a fresh additive reconciliation without losing dedup.

#### Scenario: Re-provision of an already-joined event is a no-op

- **WHEN** the configured `eventId` equals the `joinedEventId` marker
- **THEN** no seed, no re-projection, and no marker write occur; the ledger, cursor, and accumulator are unchanged

#### Scenario: A different event keeps state and additively seeds

- **WHEN** the configured `eventId` differs from the marker
- **THEN** the ledger, discovery cursor, and accumulator are kept, the ledger is additively seeded from the per-device listing, `device.json` is re-projected to the new event path, and the marker is set — with no ledger clear and no cursor reset

#### Scenario: A reinstall restores via the same additive seed

- **WHEN** the marker is absent and the ledger is empty (a reinstall) for a configured event
- **THEN** the additive seed from the per-device listing restores the `COMPLETED` rows and the marker is set, without any clear or reset

#### Scenario: Leaving clears the marker but keeps dedup

- **WHEN** the user has left an event (config absent) and the extension next runs
- **THEN** the extension clears the `joinedEventId` marker but keeps the ledger, cursor, and accumulator, so provisioning any event afterward runs a fresh additive reconciliation and re-uploads nothing already stored
