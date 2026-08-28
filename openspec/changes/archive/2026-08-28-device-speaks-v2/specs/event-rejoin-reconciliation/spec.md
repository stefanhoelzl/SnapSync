## MODIFIED Requirements

### Requirement: Event file list seam

The system SHALL define a per-device file-listing seam whose `list(deviceId)` returns a `Result` of
the **storage keys the device has stored** — each key the bare `<assetId>-<role>.<ext>` — obtained from
the backend **per-device** listing (capability `api-endpoints`) over HTTPS. The source of seed truth is
the device's event-independent stored resources, not any single event. A settable/fake implementation
SHALL exist for tests; the iOS implementation SHALL use an HTTP client against the compile-time
device-facing host.

The backend answers in **identity terms** — `assetId`, `role`, and the resource's **capture filename** —
and mints no `url`. The seam SHALL therefore **recompose** each key through the shared `uploadKey`
builder, so the one definition of the storage layout stays in `model/` and the seam's callers keep
receiving keys. The recomposition is exact even when the capture name is unavailable or is itself a
storage key, because only the value's extension is consumed.

The response SHALL be decoded **strictly**: `assetId`, `role` and `filename` are all required, and `role`
SHALL be decoded as the closed `ResourceRole` vocabulary rather than as an opaque string. This is not
defensive typing. The previous listing shape also carried a field named `filename`, and it carried the
**storage key** where this one carries the **capture name** — so a lenient decode accepts either and
silently means the opposite, seeding capture names as ledger keys, leaving every real key unseeded, and
re-uploading the device's entire library on the next rejoin with no error anywhere.

Strict about the fields it NAMES, tolerant of the ones it does not: **unknown fields SHALL still be
ignored**, so a backend that adds one requires no client release. The two are not in tension and the
distinction is the whole design — requiring `assetId` and `role` is what makes a listing this build cannot
read fail loudly, while ignoring an added field is what stops a backend addition costing a device its
entire dedup set. A reader who takes "strict" to mean "rejects anything unexpected" has the rule backwards
in the direction that breaks working devices.

The seam SHALL surface failures as a failed `Result` (never a thrown error to the caller), so the join can
reduce them into state — and it SHALL **distinguish a decode failure from a transport failure**. A
transport failure is transient and is retried by deferring the cycle; a decode failure is permanent, will
not heal by retrying, and SHALL be reported at `Error` severity rather than absorbed into the same
deferral. Collapsing the two leaves a device deferring uploads forever behind a warning that reads exactly
like a slow network.

Reading the backend's record rather than the byte store means a resource the backend has not recorded is
not listed. That is the correct direction for this seam: seeding a row as `COMPLETED` for bytes the backend
cannot vouch for would suppress an upload that never happened.

#### Scenario: Successful listing returns the device's stored keys

- **WHEN** the backend returns the device's stored resources in identity terms
- **THEN** `list(deviceId)` yields a success `Result` carrying one recomposed `<assetId>-<role>.<ext>` key
  per stored resource

#### Scenario: A response missing identity is refused rather than misread

- **WHEN** the backend returns entries carrying a `filename` but no `assetId` or `role`
- **THEN** the decode fails and no key is produced, rather than seeding the `filename` values as keys

#### Scenario: A role outside the closed vocabulary is refused

- **WHEN** an entry names a role that is not a member of `ResourceRole`
- **THEN** the decode fails

#### Scenario: Transport failure yields a retryable failed Result

- **WHEN** the backend request fails with a network error, a non-2xx status, or a timeout
- **THEN** `list(deviceId)` yields a failed `Result` marked as transient, and does not throw to the caller

#### Scenario: A decode failure is permanent and is reported

- **WHEN** the response cannot be decoded into the expected shape
- **THEN** `list(deviceId)` yields a failed `Result` distinguishable from a transport failure, and the
  condition is reported at `Error` severity rather than only warned about once per cycle

#### Scenario: An unrecorded resource is not seeded

- **WHEN** the backend holds no record for a resource
- **THEN** the listing omits it, so the reconciler does not seed a `COMPLETED` row that would suppress a
  needed upload

### Requirement: Join reconciliation seeds already-stored photos as completed

A triggered reconciliation (in the extension) SHALL: fetch the **per-device** file listing
(`list(deviceId)`); **`resetTo`** (atomic clear-and-seed) the ledger to exactly one `COMPLETED` row
per stored resource, each keyed by the **recomposed** `<assetId>-<role>.<ext>` key, carrying the
`assetId` the listing reported and the **configured `eventId` as provenance** (`sync-ledger`, "Event
provenance and the backfill sweep" — the seed is this join's own write, so no seeded row is ever a
pre-provenance sentinel row); **clear the discovery cursor** to force a full re-enumeration; and **on
success set the `joinedEventId` marker** to the configured `eventId`. The seed records no timestamp.

Seeding from the listing's reported `assetId` rather than by re-parsing the key is the direction that
cannot drift: the backend now states identity, so the client has no reason to recover it from a string it
just composed.

The clear is essential: it drops stale/phantom rows — e.g. a `REQUESTED` row left by a prior cycle whose
upload job never materialized, which the engine would otherwise read as in-flight and skip re-creating
forever — leaving the ledger as exactly the device's stored files. Because the byte store is
device-global and event-independent, this clear-and-seed both **restores** dedup after a reinstall
(the seed repopulates every globally-stored resource as `COMPLETED`) and **preserves** it across an
event switch (the global listing re-seeds the same files `COMPLETED`, carrying the **new** event's
id — which is what keeps a switch's provenance truthful without any sweep). A resource that is not in the
device's byte store is absent from the listing, is not seeded, and is uploaded idempotently by the
producer (last-write-wins). Setting the marker on success — even when zero rows were seeded — settles
the join so it does not re-trigger.

A **confirmed-successful** listing SHALL be treated as **authoritative** — whether it reports every,
some, or **none** of the ledger's prior `COMPLETED` files. The `resetTo` seeds exactly the stored
files, so any file the listing omits (a subset deletion, or a full storage reset that returns an empty
listing) is not seeded and is re-uploaded by the producer. In particular, an **empty** listing while
the ledger still holds `COMPLETED` rows means the objects were **deleted from storage** and SHALL
re-baseline the ledger to empty (re-uploading everything), NOT defer: a successful empty listing cannot
be a stale/transient read, because (a) an upload confirms its bytes before the job succeeds (capability
`api-endpoints` never returns `2xx` for an unconfirmed upload), (b) the storage LIST reflects
writes and deletes immediately (read-after-write consistent), and (c) the list endpoint never returns a
`2xx` for a failed or partial listing (capability `api-endpoints`: a failure is `502`, surfaced
to the reconciliation as a fetch failure, not an empty array). An **untrustworthy** signal — a transport
error or a timeout — SHALL still defer (see "Extension defers uploads until the seed succeeds"), leaving
the ledger, cursor, and marker untouched so the next cycle retries. A **decode** failure SHALL defer
likewise, but SHALL NOT be reported as a transient condition: it will not heal by retrying, so it is
surfaced as the permanent fault it is. The ledger is thus reset only ever on an authoritative listing.

#### Scenario: A stored resource is seeded completed

- **WHEN** the per-device listing reports resources `(a1, primary, IMG_0001.JPG)` and `(a1, live, IMG_0001.MOV)`
- **THEN** the ledger holds a `COMPLETED` row keyed `a1-primary.jpg` and one keyed `a1-live.mov`, each
  carrying the reported `assetId` and the configured event's id as provenance, and the marker is set

#### Scenario: The reset drops stale/phantom rows

- **WHEN** the ledger holds a non-`COMPLETED` row (e.g. a `REQUESTED` row from a prior cycle whose job never materialized) for a resource absent from the per-device listing and a reconciliation seeds
- **THEN** the `resetTo` clears that stale row, so the ledger holds exactly one `COMPLETED` row per listed resource and nothing else

#### Scenario: Reinstall restores dedup from an empty ledger

- **WHEN** the ledger is empty (a reinstall) and the device's byte store already holds prior resources
- **THEN** the clear-and-seed sets a `COMPLETED` row for every stored resource, so the producer re-uploads none of them

#### Scenario: A zero-row join still settles

- **WHEN** the per-device listing returns no resources for a device with an empty byte store and no
  `COMPLETED` rows in the ledger
- **THEN** no rows are seeded but the `joinedEventId` marker is set, so the next cycle does not re-reconcile

#### Scenario: A storage reset (empty listing against a non-empty ledger) re-baselines and re-uploads

- **WHEN** the per-device listing returns **empty** (a genuine storage reset) but the ledger already
  holds `COMPLETED` rows
- **THEN** the reconciliation `resetTo`s the ledger to empty, clears the cursor, and sets the marker, so
  the producer re-uploads every resource — it does **not** defer (a successful empty listing is
  authoritative, never a transient)

#### Scenario: A partial storage deletion re-uploads only the missing files

- **WHEN** the per-device listing reports a strict subset of the ledger's prior `COMPLETED` files (some
  objects were deleted from storage)
- **THEN** the `resetTo` seeds only the still-stored files `COMPLETED`, and the producer re-uploads
  exactly the omitted (deleted) files, leaving the still-stored ones untouched

#### Scenario: A decode failure defers without pretending to be transient

- **WHEN** the listing cannot be decoded into the expected shape
- **THEN** the ledger, cursor and marker are untouched and uploads are deferred, and the condition is
  reported as permanent rather than logged as a retryable fetch failure

#### Scenario: A not-yet-stored resource re-uploads idempotently

- **WHEN** a resource is absent from the per-device listing (never uploaded)
- **THEN** it is not seeded and the producer uploads it (any already-present resource overwritten last-write-wins)
