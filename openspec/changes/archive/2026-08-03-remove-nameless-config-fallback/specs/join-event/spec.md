## MODIFIED Requirements

### Requirement: The confirmation loads and verifies event details first

On entering the pending-join state, the system SHALL fetch the event's details by `GET /events/:eventId`
before offering the confirm action, showing a **loading** phase ("Loading event details…"). The screen
SHALL open immediately on decode (the `eventId` is local) and the load SHALL gate only the confirm, per
these outcomes:

- **200 with a name** → a **loaded** phase showing the event **name** (a **required, non-null,
  non-blank** value) and carrying the event's **`startsAt`** (both read from the
  `{ eventId, name, createdAt, startsAt }`
  body), with the confirm action (Join) enabled. The loaded `startsAt` SHALL be the cutoff row's
  **default** *and* its **floor** (see capability `photo-selection-policy`). `startsAt` is **always present**
  on a 200 — the backend synthesizes it from `createdAt` for markers written before it existed
  (capability `event-creation`) — so the loaded phase SHALL carry it non-null and there is **no**
  seed-from-`createdAt` fallback and **no** seed-to-now fallback;
- **200 without a name, or whose name is blank** → treated as a **failed** phase with a **Retry** action
  — a loaded event SHALL
  always carry a name (the backend enforces name-required on create, trimming and rejecting an empty or
  whitespace-only value, capability `event-creation`), so a
  nameless or blank-named 200 is a malformed/transient response, never a loaded phase with a null or
  blank name. This is the **only** guard against a blank name entering the persisted membership: the
  membership type requires the name to be present, not to be non-blank (capability `event-link`), and no
  downstream consumer re-checks it;
- **200 without a parseable `startsAt`** → likewise a **failed** phase with a **Retry** action. A loaded
  event SHALL always carry a `startsAt` (the backend rejects a non-canonical one on create and
  synthesizes one on read), so its absence is a malformed/transient response. It SHALL NOT be defaulted
  to now: `startsAt` is a **floor**, and inventing one on the client would silently lower it;
- **404** → a **blocked** phase ("this invite is invalid or the event no longer exists") with **no**
  confirm action — the details fetch is the event-existence gate;
- **network / non-404 failure** → a **failed** phase with a **Retry** action that re-runs the fetch.

The confirm action SHALL NOT be offered while loading, blocked, or failed. The join surface SHALL hold a
cutoff that is **always present**: the loaded phase's cutoff and the surface's chosen cutoff SHALL both be
non-nullable, so a join with no cutoff is unrepresentable rather than guarded against at confirm time
(capability `photo-selection-policy`). Because the loaded phase carries a non-null name, downstream
provisioning and album titling (capability `event-album`) always have a name to use.

#### Scenario: Details load and enable confirm
- **WHEN** `GET /events/:eventId` returns 200 with the event name and `startsAt`
- **THEN** the join surface shows the name, defaults the cutoff to `startsAt`, and offers the Join confirm action

#### Scenario: A missing or unparseable startsAt is retryable, never defaulted
- **WHEN** `GET /events/:eventId` returns 200 with a name but no `startsAt`, or one that does not parse
- **THEN** the join surface shows a load-failure message and a Retry action, and never enters the loaded
  phase with an invented floor

#### Scenario: The cutoff row is seeded on first composition and never empty
- **WHEN** the join surface first composes in any phase — including a commit-failure phase reached without
  passing through the loaded phase
- **THEN** the cutoff row carries a value (the loaded `startsAt`), and the confirm/retry action passes
  that value on, there being no representable state in which it could pass none

#### Scenario: A nameless 200 is retryable, not a null-named load
- **WHEN** `GET /events/:eventId` returns 200 whose body carries no name
- **THEN** the join surface shows a load-failure message and a Retry action, and never enters the loaded phase with a null name

#### Scenario: A blank-named 200 is retryable, not a blank-named load
- **WHEN** `GET /events/:eventId` returns 200 whose body carries a name that is empty or whitespace-only
- **THEN** the join surface shows a load-failure message and a Retry action, and no membership is ever
  provisioned or refreshed with that blank name

#### Scenario: A missing event blocks the join
- **WHEN** `GET /events/:eventId` returns 404
- **THEN** the join surface shows an invalid/expired-invite message and offers no Join action

#### Scenario: A load failure is retryable
- **WHEN** `GET /events/:eventId` fails on the network or returns a non-404 error
- **THEN** the join surface shows a load-failure message and a Retry action that re-runs the fetch

### Requirement: One details client

The app SHALL have exactly one `GET /events/:eventId` client: the `EventDirectory` port (`:domain`
`ports/`) and its `HttpEventDirectory` implementation in `:adapter:generic:app`. Every consumer of an
event's details SHALL read through it — the join gate's details fetch AND the membership refresh (the
foreground re-fetch, capability `event-link`). There SHALL be no second, looser
event-fetch client: a duplicate client is how producer and consumer semantics drift (the deleted
`EventMetadataSource` accepted responses the gate rejects).

**Folding a fetch result into the persisted membership SHALL be one membership-feature rule** seated in
`:domain` `feature/membership`, named for that need rather than for any single field it touches. The rule
SHALL answer a **sealed outcome** with exactly three arms:

- **refreshed** — the fetch resolved and the event is still the configured one: the rule persists a
  changed name, and backfills any absent window and retention fields, as **one whole-config save** with
  only those fields replaced, never clobbering the persisted cutoff (capability
  `photo-selection-policy`);
- **inconclusive** — the fetch did not resolve definitively (offline, transport failure, non-404 status,
  unparseable body), or it resolved for an event that is no longer the configured one: **nothing is
  persisted and nothing is torn down**, and the last-known values are left unchanged;
- **absent** — the fetch resolved to a definitive `NotFound` **and** the membership's own persisted
  deadline has passed: the membership is torn down (capability `leave-event`).

The name arm SHALL be retained as **convergence on the served name**, not as a fill for a membership that
lacks one: a membership always carries a name (capability `event-link`), so this arm exists so that a
persisted name can still be repaired toward the backend's value, and it is the only path by which a
diverged name could ever be corrected.

The teardown on the **absent** arm SHALL be performed by the rule itself, not by a branch in the calling
flow. Attaching the consequence to the verdict is what makes every trigger reach the same outcome by
construction rather than by separate call sites agreeing. It is also what the flow transcriber's closed
grammar requires: the fetch is network I/O and therefore sits inside an escaping launch, where no `when`
is transcribable and an untranscribable flow fails generation (capability `architecture-diagrams`). This
requirement SHALL hold independently of how many triggers call the rule: it is a property of where the
consequence is attached, not of agreement between call sites.

The distinction between *inconclusive* and *absent* SHALL be preserved end to end. The port already
separates `NotFound` from `Failed`; the effect the flows are given SHALL carry that sealed outcome rather
than collapsing it, because collapsing them is what makes "could not tell" and "definitively gone"
indistinguishable at the only place the difference matters.

The fetch itself SHALL remain coordination in the `flow/` triggers — **`Foreground`, unconditionally, and
no other** — through a `compose/`-built `EventDirectory` effect over this one
client. The trigger SHALL do no more than hand the fetched result to the rule. The `Provision` trigger
SHALL NOT fetch: every provision route (interactive join, `autoJoin`, switch, headless create) has just
loaded or minted the event's details, so a fetch there is redundant by construction, and the membership
it would refresh is current already.

#### Scenario: The membership refresh reads through the details client

- **WHEN** the foreground refresh fetches the configured event
- **THEN** it calls the same `EventDirectory` the join gate uses, and updates the stored membership only
  from a resolved outcome

#### Scenario: Provisioning fetches no details

- **WHEN** an event is provisioned by any route — an interactive join, an `autoJoin` link, a switch, or a
  headless create
- **THEN** no `GET /events/:eventId` is issued by the provision trigger, and the membership is persisted
  from the details the route already carries

#### Scenario: An inconclusive outcome changes nothing

- **WHEN** the details fetch fails on the network, times out, returns a non-404 error, or returns an
  unparseable body during a refresh
- **THEN** the persisted config keeps every last-known value, nothing is torn down, and syncing is
  unaffected

#### Scenario: A stale fetch after a switch stores nothing

- **WHEN** a fetch resolves for an event that is no longer the configured one
- **THEN** the rule answers inconclusive and stores nothing (the departed membership is not resurrected)

#### Scenario: The sealed outcome reaches the flow intact

- **WHEN** the details fetch resolves to `NotFound`
- **THEN** the effect the trigger receives distinguishes that from a failed fetch, rather than presenting
  both as the same no-result

#### Scenario: The consequence is attached to the verdict, not to the caller

- **WHEN** the rule answers the absent verdict
- **THEN** the teardown is performed by the rule itself, so no calling flow contains a branch that could
  reach a different consequence from the same verdict
