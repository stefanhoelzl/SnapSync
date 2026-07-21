# event-limits Specification

## Purpose

The bounds every event carries: a device **capacity** and a wall-clock **lifetime**, stamped onto
the event marker at mint from backend global configuration and enforced entirely server-side.
An event moves through three states — **live** (joins allowed under the cap, full sync),
**grace** (no new devices, existing members keep full sync so late uploads of in-event photos
still land), and **expired** (deleted on first touch, members notified). Expiry is deletion: no
tombstone, no scheduler — the first request that touches an expired event reaps it, and
afterwards the event is indistinguishable from one that never existed.

Decision record: `changes/archive/2026-07-21-add-event-limits`.

## Requirements

### Requirement: Limit values from backend configuration

The backend SHALL define three event-limit constants in its configuration module — the event
device capacity (initial value `10`), the event duration (initial value 30 days), and the
post-`endsAt` grace period (initial value 1 day) — as source constants carried on the runtime
`Config`, per the module's config-in-source law (capability `backend-deployment`: the
environment is never consulted for a non-secret; tests inject shortened windows by constructing
a `Config` directly). `POST /events` SHALL resolve
`endsAt = startsAt + duration` and `capacity` from this configuration **at mint time** and stamp
both onto the marker (capability `event-creation`). All subsequent enforcement SHALL read the
marker's own `endsAt` and `capacity` fields, never the live configuration values — so a
configuration change affects only events minted after it, and a later change can make the values
creator-chosen with no schema or enforcement change.

`endsAt` SHALL be stored in the canonical cutoff form `yyyy-MM-dd'T'HH:mm:ss'Z'` (the same shape
as `startsAt`), so lifecycle comparisons are lexicographic string comparisons. `capacity` SHALL
be a positive integer.

#### Scenario: Limits are stamped at mint from configuration

- **WHEN** a valid `POST /events` is processed while the configured duration is 30 days and the
  configured capacity is 10
- **THEN** the written marker carries `endsAt` equal to `startsAt` plus 30 days in canonical
  cutoff form, and `capacity` `10`

#### Scenario: A configuration change does not reach existing events

- **WHEN** the configured duration or capacity is changed after an event was minted
- **THEN** that event's enforcement still uses the `endsAt` and `capacity` stamped on its own
  marker, unchanged

#### Scenario: Tests inject shortened windows through Config

- **WHEN** a test constructs a `Config` carrying a shortened event duration or grace period
- **THEN** the app built over it mints and enforces with those values — no environment variable
  and no clock mocking involved

### Requirement: Event lifecycle from the marker alone

An event's lifecycle state SHALL be a pure function of its marker's `endsAt`, the configured
grace period, and the server's current wall-clock: **live** while `now <= endsAt`, **grace**
while `endsAt < now <= endsAt + grace`, and **expired** once `now > endsAt + grace`. A marker
missing `endsAt` or `capacity` (written before this capability) SHALL be treated as **expired**.
No stored state machine, flag, or rewrite SHALL represent the lifecycle — the marker stays
write-once, and the state is recomputed on every read.

#### Scenario: Live within the window

- **WHEN** an event-scoped request arrives while `now <= endsAt`
- **THEN** the event is treated as live and the request proceeds under the capacity rules

#### Scenario: Grace after the end

- **WHEN** an event-scoped request arrives while `endsAt < now <= endsAt + grace`
- **THEN** the event is treated as in grace — closed to new devices, open to existing members

#### Scenario: Expired past the grace period

- **WHEN** an event-scoped request arrives while `now > endsAt + grace`
- **THEN** the event is treated as expired and the expiry reap is triggered

#### Scenario: A legacy marker is expired

- **WHEN** an event-scoped request reads a marker that carries no `endsAt` or no `capacity`
- **THEN** the event is treated as expired, exactly as if its grace period had elapsed

### Requirement: Capacity bounds devices ever enrolled

The capacity check SHALL compare the marker's `capacity` against the number of **distinct device
ids ever enrolled** on the event — every device with an active (`<deviceId>.json`) **or**
departed (`<deviceId>.left.json`) manifest under `events/<eventId>/devices/` (capability
`device-manifest`). A device-manifest write for a device id already present in either form SHALL
pass the capacity check unconditionally (a rejoin reuses the device's own slot; a manifest
update is not an enrollment). A write for a device id present in neither form SHALL be rejected
with `409 Conflict` when the ever-enrolled count has reached `capacity`, and no manifest SHALL
be written. Leaving SHALL NOT free a slot — departed manifests keep counting, so churn cannot
route more than `capacity` distinct devices through an event.

The check is read-then-write without coordination (the storage backend has no compare-and-set);
concurrent first-time enrollments MAY transiently exceed `capacity`. The bound this requirement
guarantees is that a request observing the event at or over capacity admits no new device.

#### Scenario: A new device is rejected at capacity

- **WHEN** a device-manifest write arrives for a device id with no active or departed manifest
  on the event, and the event already has `capacity` distinct device ids enrolled (counting both
  active and departed)
- **THEN** the endpoint responds `409` and writes no manifest

#### Scenario: A known device always passes

- **WHEN** a device-manifest write arrives for a device id that already has an active or
  departed manifest on the event
- **THEN** the capacity check passes regardless of the ever-enrolled count and the write proceeds

#### Scenario: Leaving frees no slot

- **WHEN** a device leaves a full event and a different, never-seen device then attempts to
  enroll
- **THEN** the departed device still counts toward `capacity` and the new device is rejected
  with `409`

#### Scenario: A rejoin reuses the departed slot

- **WHEN** a device that previously left the event writes a fresh manifest while the event is
  live
- **THEN** the write passes the capacity check (its device id is already counted) and the device
  is active again

### Requirement: Grace closes enrollment but not sync

During grace the endpoint SHALL reject a device-manifest write for a device id with no active or
departed manifest on the event with `410 Gone`, and no manifest SHALL be written. Every other
event-scoped operation SHALL behave exactly as when live for devices already on the event:
manifest writes, photo-byte uploads, the union read, notify fan-out, and leave all proceed. The
grace period exists so photos taken during the event but uploaded late — the platform schedules
uploads on its own cadence — still land; closing anything but enrollment would silently drop
them.

The distinct codes keep the two rejection axes separate for future clients: `409` means
**full** (capacity), `410` means **over** (time). Neither carries a response-body contract.

#### Scenario: A new device cannot enroll during grace

- **WHEN** a device-manifest write arrives during grace for a device id with no manifest (active
  or departed) on the event
- **THEN** the endpoint responds `410` and writes no manifest

#### Scenario: An existing member syncs through grace

- **WHEN** a device with an active manifest writes its manifest, uploads bytes, or reads the
  union during grace
- **THEN** each operation proceeds exactly as when the event was live

#### Scenario: Full beats over for a new device in grace

- **WHEN** a device-manifest write arrives during grace for a never-seen device id on an event
  that is also at capacity
- **THEN** the endpoint responds `410` — the event being over is the reason joining is closed,
  regardless of remaining capacity

### Requirement: Expiry reap on first touch

Every event-scoped route — any route addressed by an `eventId` — SHALL pass the lifecycle check
before serving its request. The first request that finds the event expired SHALL trigger the
reap: (1) resolve the event's active members and fan out the existing best-effort silent push to
them (capability `event-notify-endpoint` machinery), then (2) delete the event's stored objects
— every device manifest, the reference-checked garbage collection of freed devices' bytes and
config documents that the leave cascade already defines (capability `event-leave-endpoint`), and
finally the marker itself. No tombstone SHALL remain. The triggering request SHALL be answered
exactly as if the event did not exist (`404` where absence is `404`), and every subsequent
request SHALL be indistinguishable from one against an event that never existed.

The push precedes the deletes because membership is only readable before it is deleted. A failed
or partial push SHALL NOT abort the reap. A reap interrupted mid-cascade SHALL be completable by
the next touch (the surviving marker keeps the event discoverable as expired until the marker —
deleted last — is gone).

There is no scheduler: reap timing rides on traffic, and an event nobody touches lingers as
storage until touched. This is accepted; the likely first toucher is a member's own background
sync, which is exactly who the push is for.

#### Scenario: First touch reaps and answers as absent

- **WHEN** the first event-scoped request arrives after an event's grace period has elapsed
- **THEN** the silent push is fanned out to the active members, the event's manifests, freed
  referenced objects, and marker are deleted, and the request is answered as if the event did
  not exist

#### Scenario: After the reap the event never existed

- **WHEN** any event-scoped request arrives after the reap has completed
- **THEN** the response is byte-for-byte the response an event that was never created would
  produce (`404` on the metadata read, `404` on the manifest write, and so on)

#### Scenario: Push failure does not block the reap

- **WHEN** the reap's silent-push fan-out fails for some or all members
- **THEN** the deletion cascade still runs to completion and the request is still answered as
  absent

#### Scenario: An interrupted reap completes on the next touch

- **WHEN** a reap is interrupted after deleting some objects but before deleting the marker
- **THEN** the next event-scoped request finds the marker, recomputes the state as expired, and
  runs the reap to completion

#### Scenario: A legacy marker is reaped on touch

- **WHEN** an event-scoped request touches an event whose marker predates this capability (no
  `endsAt`/`capacity`)
- **THEN** the reap runs exactly as for an expired event
