# event-limits Specification

## Purpose

The bounds every event carries, along **two independent axes** that used to be one value.

The host's date **range** (`startsAt`…`endsAt`, at most **30 days** long) is the capture **window**: it
bounds which photos may be *uploaded* and closes nothing else. Joining is never refused on time, so a
guest who scans the QR days after the party still joins and contributes the in-window photos still on
their phone — the case the old grace gate refused.

How long the event **lives** is a separate, stamped `lifetimeSeconds` (30 days). The delete-by is
**derived** on every read as `max(createdAt, startsAt) + lifetimeSeconds`; anchoring at the later of the
two keeps a back-dated event from being born expired and a created-early one from dying inside its own
window. Stamping the **duration** rather than the instant keeps the per-event value immutable against a
later configuration change, while leaving the anchor policy in shared code where it can be corrected
without rewriting a single stored marker. That is a deliberate, narrow exception to this capability's own
"enforcement reads only stamped fields" rule, and it is stated rather than left to be discovered.

The lifecycle is therefore **binary**: an event exists, or the nightly cleanup has deleted it. There is no
served intermediate state, no `410`, and no on-touch reap — deletion belongs solely to the sweep
(capability `scheduled-cleanup`), which reclaims an event past its delete-by (**the guarantee**) or one
that is **empty** — ever joined, with no active member left. Emptiness is **opportunistic reclamation, not
a promise**: a leave whose backend `DELETE` never lands keeps a manifest active, so an abandoned event may
never empty.

That no route deletes on touch is load-bearing beyond tidiness: it is what makes a `404` a *real*
deletion, and therefore safe as one of the two witnesses a device requires before tearing its own
membership down (capability `leave-event`).

**Capacity** (10 devices ever enrolled) is the only refusal any route makes. The window maximum and the
lifetime are fixed for every event, permanently; the sole future paid-tier lever is device count, which is
already per-event and stamped, so raising it needs no schema or enforcement change.

Decision record: `changes/archive/2026-08-25-add-deployment-resolver-and-boot-probe` (the limits become
resolved deployment values); `changes/archive/2026-07-21-add-event-limits`;
`changes/archive/…-add-event-date-range` (`endsAt` becomes creator-supplied at mint);
`changes/archive/…-decouple-event-window-from-lifetime` (the window and the lifetime become independent).

## Requirements

### Requirement: Limit values from backend configuration

The backend SHALL define three event-limit values in its configuration — the event device
**capacity** (initial value `10`), the maximum event **window** (initial value 30 days), and the event
**lifetime** (initial value 30 days) — resolved from the deployment (capability
`deployment-configuration`) and carried on the runtime `Config`, per the module's config-in-the-artifact
law (capability `backend-deployment`: the environment is never consulted for a non-secret; tests inject
shortened values by constructing a `Config` directly). They are **product policy**, not deployment-varying
facts: every deployment resolves the same component, so declaring them as data organizes them without
making them differ between environments. The window maximum and the lifetime SHALL be **two distinct
values** even while they hold the same number: they answer different questions, only the lifetime is
stamped, and collapsing them would make a future divergence a silent behavior change in two places. There
SHALL be **no** grace-period value.

`POST /api/v1/events` SHALL resolve `capacity` and `lifetimeSeconds` from this configuration **at mint time**
and stamp both onto the marker (capability `event-creation`).

`endsAt` SHALL be **creator-supplied at mint when present**: when the `POST /api/v1/events` body carries a valid
`endsAt` — canonical cutoff shape, a real round-tripping instant, strictly after `startsAt`
(`startsAt < endsAt`), and no more than the configured window maximum after it
(`endsAt - startsAt <= windowMax`) — the endpoint SHALL stamp that value as the marker's `endsAt`. When
the body carries no `endsAt`, the endpoint SHALL fall back to `endsAt = startsAt + windowMax`, so clients
that send only `startsAt` keep working.

`endsAt` SHALL bound **only** which captures may be uploaded (capability `photo-selection-policy`). It
SHALL NOT determine when the event is deleted, SHALL NOT close enrollment, and SHALL NOT be read by any
lifecycle check.

All subsequent enforcement SHALL read the marker's own `endsAt`, `capacity`, and `lifetimeSeconds`
fields, never the live configuration values — so a configuration change affects only events minted after
it. The one deliberate exception is the **anchor** from which the lifetime is measured, which is shared
code rather than a stamped value (see the lifetime requirement), so that the anchor policy can be
corrected without rewriting stored metadata.

The window maximum and the lifetime are **fixed for every event, permanently**. The only future paid-tier
lever is `capacity`, which is already per-event and stamped, so raising it needs no schema or enforcement
change.

`endsAt` SHALL be stored in the canonical cutoff form `yyyy-MM-dd'T'HH:mm:ss'Z'` (the same shape as
`startsAt`). `capacity` SHALL be a positive integer. `lifetimeSeconds` SHALL be a positive integer number
of seconds.

#### Scenario: A creator-supplied endsAt within the cap is stamped verbatim

- **WHEN** a valid `POST /api/v1/events` carries a valid `endsAt` (canonical shape, a real instant, strictly
  after `startsAt`, and no more than the configured window maximum after it)
- **THEN** the written marker carries that `endsAt` unchanged

#### Scenario: An absent endsAt falls back to the maximum window

- **WHEN** a valid `POST /api/v1/events` carries no `endsAt` while the configured window maximum is 30 days and
  the configured capacity is 10
- **THEN** the written marker carries `endsAt` equal to `startsAt` plus 30 days in canonical cutoff form,
  and `capacity` `10`

#### Scenario: The lifetime is stamped at mint

- **WHEN** a valid `POST /api/v1/events` is processed while the configured lifetime is 30 days
- **THEN** the written marker carries `lifetimeSeconds` equal to 30 days in seconds

#### Scenario: A configuration change does not reach existing events

- **WHEN** the configured window maximum, lifetime, or capacity is changed after an event was minted
- **THEN** that event's enforcement still uses the `endsAt`, `lifetimeSeconds`, and `capacity` stamped on
  its own marker, unchanged

#### Scenario: The limits do not vary between deployments

- **WHEN** any deployment is resolved
- **THEN** it carries the same capacity, window maximum and lifetime, because every deployment extends the
  one policy component

#### Scenario: The window bounds uploads only

- **WHEN** an event-scoped request arrives after the event's `endsAt` has passed
- **THEN** no lifecycle check consults `endsAt`, and the request is served exactly as it would have been
  before `endsAt` passed

#### Scenario: Tests inject shortened values through Config

- **WHEN** a test constructs a `Config` carrying a shortened window maximum or lifetime
- **THEN** the app built over it mints and enforces with those values — no environment variable, no
  deployment resolution, and no clock mocking involved

### Requirement: Event lifecycle from the marker alone

An event's lifecycle SHALL be binary — it **exists**, or it has been **deleted** — with no served
intermediate state and no stored state machine, flag, or marker rewrite. While its marker is present and
complete, every event-scoped operation SHALL be served: enrollment (under capacity), manifest writes,
photo-byte uploads, the union read, notify fan-out, and leave.
**Joining SHALL NOT be closed by time under any condition** — an event is joinable for
as long as it exists, bounded only by capacity — because a guest who joins after the window closed still
holds in-window captures that belong in the event.

An event's **delete-by** instant SHALL be derived on every read as
`max(createdAt, startsAt) + lifetimeSeconds`, where `createdAt` and `startsAt` are parsed to absolute
instants rather than compared as strings (`createdAt` is not in canonical cutoff form, so a lexicographic
comparison silently yields the wrong anchor). Anchoring at the later of the two is what makes a
back-dated event (whose `startsAt` is already weeks past) survive long enough to be joined, and a
created-early event (whose `startsAt` is weeks away) survive its own window.

The delete-by SHALL be **derived, never stamped**: the marker carries the lifetime *duration*, so the
per-event value is immutable while the anchor formula stays in shared code and can be corrected without
rewriting stored metadata.

A marker missing `startsAt`, `endsAt`, or `capacity`, or with an unparseable field, SHALL be treated as
**gone**: it cannot be classified or served, so every route answers `404` and the scheduled cleanup
deletes it. A marker carrying `startsAt` but no `lifetimeSeconds` SHALL derive its delete-by from the
**configured** lifetime constant — one lifecycle path, with no second rule kept alive for legacy markers.

Deletion is performed solely by the scheduled cleanup (capability `scheduled-cleanup`). No route SHALL
delete an event on touch.

#### Scenario: An event past its window still serves everything

- **WHEN** an event-scoped request arrives after `endsAt` but before the event's delete-by
- **THEN** it is served exactly as it would have been while the window was open, including a
  first-time enrollment for a never-seen device

#### Scenario: The delete-by anchors at the later of createdAt and startsAt

- **WHEN** an event's marker carries a `startsAt` five weeks before its `createdAt`
- **THEN** the derived delete-by is `createdAt + lifetimeSeconds`, so the event is not already past its
  deadline at the moment it is minted

#### Scenario: A created-early event survives its own window

- **WHEN** an event's marker carries a `startsAt` three weeks after its `createdAt`
- **THEN** the derived delete-by is `startsAt + lifetimeSeconds`, so the event outlives the window it
  declares

#### Scenario: A legacy marker without a stamped lifetime derives from configuration

- **WHEN** an event's marker carries `startsAt`, `endsAt`, and `capacity` but no `lifetimeSeconds`
- **THEN** its delete-by is derived using the configured lifetime constant, and it is served normally
  until that instant

#### Scenario: An incomplete marker is gone

- **WHEN** an event-scoped request reads a marker that carries no `startsAt`, no `endsAt`, or no
  `capacity`
- **THEN** every route answers `404` and the scheduled cleanup deletes the event

#### Scenario: No route deletes on touch

- **WHEN** an event-scoped request arrives for an event past its derived delete-by, before the next
  scheduled cleanup has run
- **THEN** the request is served normally and the route deletes nothing — deletion is the sweep's alone

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
