## MODIFIED Requirements

### Requirement: The confirmation loads and verifies event details first

On entering the pending-join state, the system SHALL fetch the event's details by `GET /events/:eventId`
before offering the confirm action, showing a **loading** phase ("Loading event details…"). The screen
SHALL open immediately on decode (the `eventId` is local) and the load SHALL gate only the confirm, per
these outcomes:

- **200 with a name** → a **loaded** phase showing the event **name** (a **required, non-null** value)
  and carrying the event's **`createdAt`** (both read from the `{ eventId, name, createdAt }` body), with
  the confirm action (Join) enabled; the loaded `createdAt` SHALL seed the cutoff row's **default** (see
  capability `photo-date-cutoff`). When `createdAt` is **absent or unparseable**, the cutoff row SHALL be
  seeded to **now** rather than left empty;
- **200 without a name** → treated as a **failed** phase with a **Retry** action — a loaded event SHALL
  always carry a name (the backend enforces name-required on create, capability `event-creation`), so a
  nameless 200 is a malformed/transient response, never a loaded phase with a null name;
- **404** → a **blocked** phase ("this invite is invalid or the event no longer exists") with **no**
  confirm action — the details fetch is the event-existence gate;
- **network / non-404 failure** → a **failed** phase with a **Retry** action that re-runs the fetch.

The confirm action SHALL NOT be offered while loading, blocked, or failed. The join surface SHALL hold a
cutoff that is **always present**: the loaded phase's cutoff and the surface's chosen cutoff SHALL both be
non-nullable, so a join with no cutoff is unrepresentable rather than guarded against at confirm time
(capability `photo-date-cutoff`). Because the loaded phase carries a non-null name, downstream
provisioning and album titling (capability `event-album`) always have a name to use.

#### Scenario: Details load and enable confirm
- **WHEN** `GET /events/:eventId` returns 200 with the event name and `createdAt`
- **THEN** the join surface shows the name, seeds the cutoff default from `createdAt`, and offers the Join confirm action

#### Scenario: An absent createdAt seeds the cutoff to now
- **WHEN** `GET /events/:eventId` returns 200 with a name but no `createdAt`, or a `createdAt` that does
  not parse
- **THEN** the cutoff row is seeded to the current instant, the row is never empty, and the Join confirm
  action is offered

#### Scenario: The cutoff row is seeded on first composition and never empty
- **WHEN** the join surface first composes in any phase — including a commit-failure phase reached without
  passing through the loaded phase
- **THEN** the cutoff row carries a value (the loaded default, else now), and the confirm/retry action
  passes that value on, there being no representable state in which it could pass none

#### Scenario: A nameless 200 is retryable, not a null-named load
- **WHEN** `GET /events/:eventId` returns 200 whose body carries no name
- **THEN** the join surface shows a load-failure message and a Retry action, and never enters the loaded phase with a null name

#### Scenario: A missing event blocks the join
- **WHEN** `GET /events/:eventId` returns 404
- **THEN** the join surface shows an invalid/expired-invite message and offers no Join action

#### Scenario: A load failure is retryable
- **WHEN** `GET /events/:eventId` fails on the network or returns a non-404 error
- **THEN** the join surface shows a load-failure message and a Retry action that re-runs the fetch

### Requirement: The autoJoin flag auto-confirms the gate

When a decoded deeplink carries `autoJoin = true`, the system SHALL run the **same** gate — decode,
fetch details, and (when already joined to a different event) leave-then-join — but SHALL **auto-fire**
the confirm once details reach the loaded phase, rather than waiting for a user tap. The auto-fired
confirm SHALL use the **default** cutoff (the loaded event's `createdAt`, or **now** when that is absent
or unparseable — never an absent cutoff, capability `photo-date-cutoff`) unless the deeplink carries an
explicit dev/test cutoff (see capability `deeplink-config`), in which case that value SHALL be used;
SHALL use the **default** direction **Both** unless the deeplink carries an explicit dev/test `direction`
override (`both`/`upload`/`download`, capability `deeplink-config`), in which case that direction SHALL
be used; and SHALL use the **default** album choice **off** unless the deeplink carries an explicit
dev/test `saveToAlbum` override (capability `deeplink-config`), in which case that value SHALL be used.
This keeps the headless developer launch path working (it cannot tap a confirm control) and lets it force
a specific cutoff, direction, and album choice on device. Because the auto path has no interactive
surface, a load failure (404 or network) or a failed enrollment SHALL **abort and log** rather than
parking on a retryable error state.

#### Scenario: autoJoin provisions without a tap, using the default cutoff, Both direction, and album off
- **WHEN** a deeplink with `autoJoin = true` and no explicit cutoff, direction, or album override is decoded and its details load successfully
- **THEN** the confirm is auto-fired with the cutoff defaulting to the loaded `createdAt`, the direction defaulting to `Both`, and `saveToAlbum` defaulting to off

#### Scenario: autoJoin falls back to now when the event carries no createdAt
- **WHEN** a deeplink with `autoJoin = true` and no explicit cutoff is decoded, and the loaded event
  carries no `createdAt` (or an unparseable one)
- **THEN** the auto-fired confirm provisions with the current instant as the cutoff, never an absent cutoff

#### Scenario: autoJoin honors an explicit dev/test cutoff
- **WHEN** a deeplink with `autoJoin = true` carries an explicit dev/test cutoff and its details load
- **THEN** the auto-fired confirm provisions with that explicit cutoff

#### Scenario: autoJoin honors an explicit dev/test direction override
- **WHEN** a deeplink with `autoJoin = true` carries `direction = download` and its details load
- **THEN** the auto-fired confirm provisions with direction `DownloadOnly` (the producer is not enabled)

#### Scenario: autoJoin honors an explicit dev/test saveToAlbum override
- **WHEN** a deeplink with `autoJoin = true` carries `saveToAlbum = true` and its details load
- **THEN** the auto-fired confirm provisions with `saveToAlbum = true`, so a headless launch exercises album placement

#### Scenario: autoJoin still leaves an existing event
- **WHEN** a deeplink with `autoJoin = true` for a different event is decoded while already joined
- **THEN** the existing event is left first and the new event is joined, without any confirmation UI

#### Scenario: autoJoin aborts on failure instead of showing Retry
- **WHEN** the details fetch returns 404 (or the enrollment fails) on an `autoJoin` launch
- **THEN** the flow aborts and logs, presenting no retryable error surface
