## MODIFIED Requirements
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
