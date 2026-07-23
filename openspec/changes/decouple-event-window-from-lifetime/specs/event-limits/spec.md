## MODIFIED Requirements

### Requirement: Limit values from backend configuration

The backend SHALL define three event-limit constants in its configuration module — the event device
**capacity** (initial value `10`), the maximum event **window** (initial value 30 days), and the event
**lifetime** (initial value 30 days) — as source constants carried on the runtime `Config`, per the
module's config-in-source law (capability `backend-deployment`: the environment is never consulted for a
non-secret; tests inject shortened values by constructing a `Config` directly). The window maximum and
the lifetime SHALL be **two distinct constants** even while they hold the same value: they answer
different questions, only the lifetime is stamped, and collapsing them would make a future divergence a
silent behavior change in two places. There SHALL be **no** grace-period constant.

`POST /events` SHALL resolve `capacity` and `lifetimeSeconds` from this configuration **at mint time**
and stamp both onto the marker (capability `event-creation`).

`endsAt` SHALL be **creator-supplied at mint when present**: when the `POST /events` body carries a valid
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

- **WHEN** a valid `POST /events` carries a valid `endsAt` (canonical shape, a real instant, strictly
  after `startsAt`, and no more than the configured window maximum after it)
- **THEN** the written marker carries that `endsAt` unchanged

#### Scenario: An absent endsAt falls back to the maximum window

- **WHEN** a valid `POST /events` carries no `endsAt` while the configured window maximum is 30 days and
  the configured capacity is 10
- **THEN** the written marker carries `endsAt` equal to `startsAt` plus 30 days in canonical cutoff form,
  and `capacity` `10`

#### Scenario: The lifetime is stamped at mint

- **WHEN** a valid `POST /events` is processed while the configured lifetime is 30 days
- **THEN** the written marker carries `lifetimeSeconds` equal to 30 days in seconds

#### Scenario: A configuration change does not reach existing events

- **WHEN** the configured window maximum, lifetime, or capacity is changed after an event was minted
- **THEN** that event's enforcement still uses the `endsAt`, `lifetimeSeconds`, and `capacity` stamped on
  its own marker, unchanged

#### Scenario: The window bounds uploads only

- **WHEN** an event-scoped request arrives after the event's `endsAt` has passed
- **THEN** no lifecycle check consults `endsAt`, and the request is served exactly as it would have been
  before `endsAt` passed

#### Scenario: Tests inject shortened values through Config

- **WHEN** a test constructs a `Config` carrying a shortened window maximum or lifetime
- **THEN** the app built over it mints and enforces with those values — no environment variable and no
  clock mocking involved

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

## REMOVED Requirements

### Requirement: Grace closes enrollment but not sync

**Reason**: Grace existed only because `endsAt` was simultaneously the capture ceiling and the event's
lifetime, so the event died a day after its window closed and late enrollment had to be refused to keep
the closing event's membership fixed. With the window and the lifetime separated, an event outlives its
window by construction and there is nothing for a grace period to protect: a guest joining after `endsAt`
contributes their in-window captures normally, and a member's late-draining upload queue lands against a
still-live event. The `410` response, the `live`/`grace` phase split, and the grace-period constant are
all deleted with it.

**Migration**: No client migration is required — no client keys on `410` anywhere, so its removal is
invisible to every shipped build. Backend callers that distinguished `409` (full) from `410` (over) now
see only `409`; being over is no longer a reason to refuse anything. Events currently in grace become
ordinary live events and are deleted by the scheduled cleanup at their derived delete-by.
