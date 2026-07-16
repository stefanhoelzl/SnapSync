## ADDED Requirements

### Requirement: The upload cycle owns its entry decision

The upload cycle SHALL read the membership itself and decide what the invocation does, before any library
walk, upload job, device manifest, or notify. The decision SHALL have exactly three outcomes:

- **Skip** — a required input could not be read (protected data unavailable). The cycle SHALL touch
  nothing: no reconcile, no marker clear, no cursor reset, no jobs. It SHALL complete cleanly; the next
  cycle retries.
- **Not joined** — there is definitively no usable membership (no item, an item that does not decode, or
  no baked host). The cycle SHALL run the leave-side reconciliation, which clears the `joinedEventId`
  marker (capability `event-rejoin-reconciliation`), and SHALL create no upload job.
- **Run** — joined and configured. The cycle SHALL proceed to its contribution gate and phases.

A composition root SHALL NOT make this decision. A root SHALL supply only the platform reads the decision
consumes — the membership read, the device-identity probe, and the build-time host — and the shared,
tested decision function SHALL combine them. This is the same containment `reconcile` and `Contribution`
already have, and for the same reason: an upload tier's root is wiring-only and untested by project rule,
so a decision placed there reaches whichever tiers its author happened to enumerate.

The decision SHALL be reachable per cycle, not resolved once at construction: a tier whose process
outlives a cycle SHALL re-read the membership on each run so a join, leave, or switch takes effect without
a relaunch.

An unresolvable device identity SHALL produce **Skip**, never **Not joined**. Resolving the identity can
fail exactly as the membership read can — both are Keychain items — and every outcome needs it. "I could
not look" is not "no identity" (capability `device-identity`, which never reports absence: an absent item
mints).

#### Scenario: An unreadable membership skips without touching state
- **WHEN** the cycle's membership read reports unreadable
- **THEN** the cycle completes cleanly, having created no upload job, run no reconciliation, cleared no
  marker, and reset no cursor

#### Scenario: An unresolvable device identity skips, and does not read as a leave
- **WHEN** the device identity cannot be resolved because protected data is unavailable
- **THEN** the cycle skips, the `joinedEventId` marker is left intact, and the identity is not re-minted

#### Scenario: A definitely-absent membership reconciles the leave side
- **WHEN** the cycle's membership read reports definitively no usable membership
- **THEN** the leave-side reconciliation runs, the `joinedEventId` marker is cleared, and no upload job is
  created

#### Scenario: The decision holds on every tier
- **WHEN** any tier runs a cycle from any trigger with an unreadable membership
- **THEN** the outcome is Skip, regardless of which tier or trigger invoked it

#### Scenario: A long-lived tier re-reads the membership each cycle
- **WHEN** a tier whose process survives across cycles runs a cycle after the membership changed
- **THEN** the cycle acts on the current membership, without a relaunch

### Requirement: Every selection and side-effect port is answered at the call site

The upload cycle SHALL require each port that shapes what a member contributes or what a completed cycle
emits — the device-manifest hook, the echo-suppression source, the denylisted-album source, the
completion-notify hook, the membership read, the reconciliation, and the contribution. None SHALL carry a
default.

A permissive default on such a port is an unstated answer: it is how a tier ships without a policy the
other tier has, and the resulting failure is the invisible kind this project is built against — a photo
that never enters the event, or a denylisted photo that does, with the screen reading "In sync"
throughout. Requiring the port does **not** require a tier to have the capability; a tier without one
supplies the empty answer explicitly, so the answer is recorded at the call site and reviewable in the
diff rather than inherited in silence.

#### Scenario: A cycle cannot be constructed without stating its policy
- **WHEN** a composition site constructs an upload cycle without supplying a selection or side-effect port
- **THEN** it does not compile

#### Scenario: An empty answer is legal when stated
- **WHEN** a tier has no denylisted-album source and supplies an empty one explicitly
- **THEN** the cycle runs, admitting all albums, and the choice is visible at the call site
