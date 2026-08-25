## MODIFIED Requirements

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
