## MODIFIED Requirements

### Requirement: A join loads the ledger from the per-device listing

The **app** SHALL load the upload ledger from the backend's record of this device at a provision whose
event **differs from the joined one** — a first join (no current membership) or a switch (a different event
is joined) — so a new membership starts with nothing from before it and re-uploads nothing the backend
already holds. The load SHALL:

1. fetch the **per-device** file listing (`list(deviceId)`, see "Event file list seam"), bounded by an
   explicit **15-second** timeout;
2. on a **successful** listing, call **`resetTo`** (atomic clear-and-seed, capability `sync-ledger`) with
   exactly one `COMPLETED` row per stored resource, each keyed by the **recomposed**
   `<assetId>-<role>.<ext>` key and carrying the `assetId` the listing reported. Every seeded row is
   **bare** (no manifest detail) and carries no timestamp and no event;
3. on a **failed** fetch — a transport failure, a decode failure, or the timeout — call **`clear()`**.

Either way the ledger holds nothing from before the provision. The clear is not incidental: a `COMPLETED` row
left from before the membership — on a device that left an event under the earlier contract, which kept
the ledger, or after a leave whose best-effort clear failed — would suppress a needed in-window upload
forever, with no error. Only the network fetch is bounded; the `resetTo` and `clear()` are single atomic
storage operations and are not timed.

The load SHALL run **once per membership change**, in the app process, on **every** iOS version:
`resetTo` and `clear()` are reset-family operations owned by this use case, not record operations of a
cycle's writer (capability `sync-ledger`, "Reader and writer capability split"). It SHALL run **before** the
membership's config is saved (and, on a switch, after uploads are stopped) and **before** either uploader is
registered or armed for it, so no cycle can ever see the new membership over the previous membership's ledger and the first
cycle the arm starts already sees the seed. A crash between the load and the save leaves either (first join)
an unjoined device holding a loaded ledger, which the next join clears anyway, or (switch) the previous
membership over a reloaded ledger, whose next walk simply re-records its work (`DISCOVERED` rows are
re-found by the walk; stored bytes are already `COMPLETED`); neither loses a photo. The rule lives in a `feature/membership`
use-case over the `LedgerStore` and `DeviceFilesSource` ports; `flow/Provision` reaches it only as an injected
effect (the flow-no-ports gate).

A re-provision of the **already-joined** event SHALL NOT load, clear, or reset anything: a `resetTo` there
would drop the `DISCOVERED` and `REQUESTED` rows of a live membership without stopping the uploaders whose
jobs own them.

A **confirmed-successful** listing SHALL be treated as **authoritative** — whether it reports every, some, or
**none** of the device's resources. A successful **empty** listing seeds nothing, and cannot be a transient
read: an upload confirms its bytes before the job succeeds, the storage listing is read-after-write
consistent, and the list endpoint never answers `2xx` for a failed or partial listing (capability
`api-endpoints`: a failure is `502`, surfaced as a fetch failure). A resource the listing omits is not
seeded and is uploaded idempotently by the producer (last-write-wins at the same destination).

A **transport** failure or timeout SHALL be logged at `Warn`. A **decode** failure SHALL be logged at
`Error`: it will not heal on a later join, and it reaches crash reporting as the permanent fault it is.

Seeding from the listing's reported `assetId` rather than by re-parsing the key is the direction that
cannot drift: the backend states identity, so the client has no reason to recover it from a string it
just composed. Because the listing is per-**device** and event-independent, the seed covers everything the
device has stored for any event; the rows outside the new membership's window stay bare and inert, since
every deciding reader applies the membership's policy (capability `photo-selection-policy`). A bare seeded
row inside the window is re-read and dated by the next walk (capability `sync-ledger`, "A walk re-reads only
the assets the ledger does not fully know").

#### Scenario: A stored resource is seeded completed

- **WHEN** a first join's per-device listing reports resources `(a1, primary, IMG_0001.JPG)` and
  `(a1, live, IMG_0001.MOV)`
- **THEN** the ledger holds exactly a `COMPLETED` row keyed `a1-primary.jpg` and one keyed `a1-live.mov`,
  each carrying the reported `assetId` and no manifest detail

#### Scenario: A switch replaces the previous membership's rows

- **WHEN** the device switches events while the ledger holds rows of the previous membership, including a
  `DISCOVERED` row and a `REQUESTED` row for resources absent from the listing, and the listing fetch succeeds
- **THEN** the `resetTo` leaves exactly one `COMPLETED` row per listed resource and nothing else

#### Scenario: A leftover completed row cannot suppress an upload

- **WHEN** the ledger holds a `COMPLETED` row, left from before the membership, for a resource the backend
  no longer holds, and a join's listing fetch succeeds without it
- **THEN** the row is gone after the load, and the producer uploads that resource when the walk admits it

#### Scenario: A successful empty listing clears the ledger

- **WHEN** a join's listing fetch succeeds and reports no resources
- **THEN** the ledger is empty after the load, and the join proceeds

#### Scenario: A failed fetch clears instead of seeding

- **WHEN** a join's listing fetch fails with a transport error or does not return within 15 seconds
- **THEN** the load calls `clear()`, logs at `Warn`, and the ledger holds nothing from before the provision

#### Scenario: A decode failure clears and is reported as a fault

- **WHEN** a join's listing cannot be decoded into the expected shape
- **THEN** the load calls `clear()` and logs at `Error`, not as a retryable fetch failure

#### Scenario: A re-provision of the joined event loads nothing

- **WHEN** a provision names the event the device has already joined
- **THEN** no listing is fetched, and the ledger is neither cleared nor reset

#### Scenario: The load precedes arming

- **WHEN** a first join or a switch provisions, on iOS 18–26.0 or on iOS ≥26.1
- **THEN** the app runs the load before saving the config and before registering or arming either uploader,
  so the first cycle for the membership, in either process, sees the loaded ledger

#### Scenario: A not-yet-stored resource uploads idempotently

- **WHEN** a resource is absent from the per-device listing
- **THEN** it is not seeded, and the producer uploads it when the walk admits it (an already-present object
  is overwritten last-write-wins)


### Requirement: The upload cycle does not detect membership changes

The upload cycle SHALL NOT detect a change of membership, and SHALL NOT fetch the per-device listing. Every
change of membership is an explicit app action — a join, a switch, or a leave — and each one sets the
ledger itself: a join or a switch loads it (see "A join loads the ledger from the per-device listing") and a
leave clears it (capability `leave-event`). There SHALL be **no** persisted `joinedEventId` marker, **no**
reconciliation port on the cycle, **no** seed deferral outcome, and **no** leave-side ledger or marker write
on the cycle's not-joined path.

The detection existed because a delete-and-reinstall of an already-joined app was once thought to leave a
membership that no provisioning path observed. It no longer can: the config is an App-Group file that dies
with the install (see "Reinstall means the device left the event"), so a reinstall holds no membership, and
rejoining means scanning an invite, which provisions.

A cycle that runs in a process where no provision ever executed SHALL upload against the ledger as it finds
it. That is correct by construction: whichever process ran the membership's provision loaded that ledger in
the shared App-Group store.

#### Scenario: A cycle never fetches the listing

- **WHEN** the upload cycle runs in either process, joined or not
- **THEN** it makes no per-device listing request and reads no join marker

#### Scenario: A contributing membership goes straight to its work

- **WHEN** a cycle runs for a joined, contributing membership
- **THEN** it proceeds from its entry gate to its admission and the membership's selection policy with no
  reconciliation step and no deferral outcome between them

#### Scenario: The not-joined path writes nothing

- **WHEN** a cycle runs with no membership configured
- **THEN** it uploads nothing and writes neither the ledger nor any marker

