# event-limits Specification

## Purpose

The bounds every event carries: a device **capacity** and a wall-clock **lifetime** (`endsAt`), stamped
onto the event marker at mint and enforced entirely server-side. The **lifetime** is now
**creator-supplied**: `POST /events` accepts the host's chosen `endsAt` when present (validated: canonical
instant, strictly after `startsAt`, **no upper cap**) and falls back to the configured `startsAt + duration`
when absent (an old client sending only `startsAt`). Capacity stays server-resolved from configuration.
An event moves through three states — **live** (joins allowed under the cap, full sync),
**grace** (no new devices, existing members keep full sync so late uploads of in-event photos
still land), and **expired** (deleted on first touch, members notified). Expiry is deletion: no
tombstone, no scheduler — the first request that touches an expired event reaps it, and
afterwards the event is indistinguishable from one that never existed.

These bounds are also the future **free/paid tier boundary**. Creator-chosen **duration** is the
mechanism the paid tier will gate — it exists now, uncapped and free for everyone; the payment gate is
**additive future** work at the `event-creation` attach point (charge for a longer window, or cap the free
tier there), needing no schema or enforcement change because enforcement reads only the marker's own stamped
`endsAt` and `capacity`. **Capacity** remains a server-resolved global today and is the other future paid
lever. Today's values are the current free-for-everyone bounds, not that future free tier.

Decision record: `changes/archive/2026-07-21-add-event-limits`;
`changes/archive/…-add-event-date-range` (`endsAt` becomes creator-supplied at mint).
## Requirements
### Requirement: Limit values from backend configuration

The backend SHALL define three event-limit constants in its configuration module — the event
device capacity (initial value `10`), the event duration (initial value 30 days), and the
post-`endsAt` grace period (initial value 1 day) — as source constants carried on the runtime
`Config`, per the module's config-in-source law (capability `backend-deployment`: the
environment is never consulted for a non-secret; tests inject shortened windows by constructing
a `Config` directly). `POST /events` SHALL resolve `capacity` from this configuration **at mint
time** and stamp it onto the marker (capability `event-creation`).

`endsAt` SHALL be **creator-supplied at mint when present**: when the `POST /events` body carries
a valid `endsAt` — canonical cutoff shape, a real round-tripping instant, and strictly after
`startsAt` (`startsAt < endsAt`), with **no upper cap on the duration** — the endpoint SHALL stamp
that value as the marker's `endsAt`. When the body carries no `endsAt`, the endpoint SHALL fall
back to `endsAt = startsAt + duration` from configuration, so clients that send only `startsAt`
keep working. The configured duration is thus a **fallback default**, not a fixed global bound on
how long an event may run; a creator-chosen duration is the additive future paid-tier gate
(capability `event-creation` names the attach point) — enforcement needs no change because it
already reads only the marker's own stamped fields.

All subsequent enforcement SHALL read the marker's own `endsAt` and `capacity` fields, never the
live configuration values or a global duration — so a configuration change affects only the
fallback used by events minted after it, and a later change can make capacity creator-chosen with
no schema or enforcement change.

`endsAt` SHALL be stored in the canonical cutoff form `yyyy-MM-dd'T'HH:mm:ss'Z'` (the same shape
as `startsAt`), so lifecycle comparisons are lexicographic string comparisons. `capacity` SHALL
be a positive integer.

#### Scenario: A creator-supplied endsAt is stamped verbatim

- **WHEN** a valid `POST /events` carries a valid `endsAt` (canonical shape, a real instant, and
  strictly after `startsAt`)
- **THEN** the written marker carries that `endsAt` unchanged — no configured duration is applied
  and no upper cap on `endsAt - startsAt` is enforced

#### Scenario: An absent endsAt falls back to the configured duration

- **WHEN** a valid `POST /events` carries no `endsAt` while the configured duration is 30 days and
  the configured capacity is 10
- **THEN** the written marker carries `endsAt` equal to `startsAt` plus 30 days in canonical
  cutoff form, and `capacity` `10`

#### Scenario: A configuration change does not reach existing events

- **WHEN** the configured duration or capacity is changed after an event was minted
- **THEN** that event's enforcement still uses the `endsAt` and `capacity` stamped on its own
  marker, unchanged

#### Scenario: Tests inject shortened windows through Config

- **WHEN** a test constructs a `Config` carrying a shortened event duration or grace period
- **THEN** the app built over it mints (when no `endsAt` is supplied) and enforces with those
  values — no environment variable and no clock mocking involved

### Requirement: Event lifecycle from the marker alone

An event's lifecycle state SHALL be a pure function of its marker's `endsAt` and the server's current
wall-clock: **live** while `now <= endsAt`, and **grace** while `now > endsAt`. There is no distinct
served **expired** state — an event past `endsAt` remains in grace (closed to new devices, open to
existing members) until the scheduled cleanup deletes it (capability `scheduled-cleanup`), and deletion
by that sweep *is* expiry. A marker missing `endsAt` or `capacity` (written before the `event-limits`
capability) SHALL be treated as **grace** by the gate and is deleted by the sweep. No stored state
machine, flag, or rewrite SHALL represent the lifecycle — the marker stays write-once, and the state is
recomputed on every read. The configured grace period governs only **when the sweep deletes** an event
(`now > endsAt + grace`), not how the gate classifies it.

#### Scenario: Live within the window

- **WHEN** an event-scoped request arrives while `now <= endsAt`
- **THEN** the event is treated as live and the request proceeds under the capacity rules

#### Scenario: Grace after the end

- **WHEN** an event-scoped request arrives while `now > endsAt`
- **THEN** the event is treated as in grace — closed to new devices, open to existing members — until the
  scheduled cleanup deletes it

#### Scenario: A legacy marker is in grace

- **WHEN** an event-scoped request reads a marker that carries no `endsAt` or no `capacity`
- **THEN** the event is treated as in grace (closed to new devices, open to existing members) and is left
  for the scheduled cleanup to delete

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

