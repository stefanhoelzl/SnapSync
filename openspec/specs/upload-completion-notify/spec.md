# upload-completion-notify Specification

## Purpose

The trigger that closes the sharing loop: after an uploading device drains a cycle in which at least one
upload completed, it pokes `api-endpoints`, which wakes every other member device to pull the new
photos.

Without it the push pipe exists but nothing calls it, so a co-contributor's photos are discovered only when
the receiving user next opens the app. Firing **once per drained cycle** (not once per upload) is what keeps a
burst of fifty photos from becoming fifty pushes.

The notify is an **injected, best-effort, bounded** seam: it cannot fail the upload cycle, it cannot block it
indefinitely, and it is a lambda rather than a named port so the tier composition roots stay wiring-only.

Decision record: `changes/archive/2026-07-05-notify-driven-download`.

The trigger dropped the word *drained* in `changes/archive/2026-08-27-fix-cap-truncation-loop`: it now fires when a cycle promoted a row **and**
the manifest projection changed, so a device that never drains still reaches the other members.

## Requirements

### Requirement: Notify fires once per drained cycle that completed an upload

The upload cycle SHALL fire exactly one event notify for the configured event when **both** hold: this
cycle promoted at least one `UPLOADED` row, **and** the in-cycle device-manifest write changed the
published projection (the producer actually wrote, rather than skipping because the projection was
unchanged since its last successful write). The notify SHALL be fired **after** that write, because the
event union (the recipient's authority) reflects a newly-completed asset only once its owning device's
manifest has been written; firing before that would wake recipients to a union that does not yet list
the new assets.

Each half rules out a different wasted wake, and neither alone is sufficient. Without the promotion,
the first manifest of an event — an empty projection, which is genuinely a change from nothing — would
wake every member to fetch nothing. Without the projection check, a cycle that promoted a row the
projection excludes, or whose write was not confirmed, would wake members to a union they have already
seen.

The trigger SHALL NOT be conditioned on the cycle fully draining. A cap-truncated cycle meeting both
conditions SHALL notify: its assets are in the union, and the members waiting for them are waiting for
exactly this.

The word this **replaces** is *drained*. The previous rule fired on a fully-drained cycle that promoted
at least one row, and that was wrong twice over. It was too narrow, because a device with more
outstanding work than the platform's job limit never fully drains, so the members of a live event
learned nothing while it uploaded. And its signal was **consumed**: the promotion pass ran before a
cap-truncated cycle short-circuited, so that cycle promoted rows it could not announce, leaving a later
cycle that did drain with nothing to report. The promotion and the notify SHALL therefore be performed
by the same stage, on every outcome that publishes at all, so neither can be spent by a cycle that
cannot act on it.

A cycle that must not announce SHALL NOT promote. A membership whose direction excludes upload, and a cycle
running with no event configured, place nothing in an album and fire no notify (`upload-lifecycle`), and
therefore leave `UPLOADED` rows as they are. Those rows stay outstanding until the device rejoins, at which
point re-join reconciliation seeds them from the device's stored-file listing
(`event-rejoin-reconciliation`) — the bytes did land, so the listing reports them.

"Promoted" means a row moving out of `UPLOADED` in this cycle's promotion pass. That is what makes
duplicate-notify suppression **structural** rather than a check: a terminal outcome re-delivered for a
key that is no longer `REQUESTED` cannot re-enter `UPLOADED` (the guarded write applies to nothing, per
`sync-ledger`), so it cannot present itself to the promotion pass a second time, so it changes no row,
so the projection is unchanged. An earlier rule — read the row's state before writing it and count only
a `false → true` transition — was replaced for a reason that still holds: it was a read-then-write pair
against a writer that takes no lock.

#### Scenario: A cycle that promoted and changed the projection notifies after the manifest write

- **WHEN** an upload cycle promoted at least one `UPLOADED` row and its device-manifest write publishes
  a projection different from the last one written
- **THEN** the cycle writes the manifest and then fires exactly one event notify for the configured
  event

#### Scenario: A cap-truncated cycle notifies when it promoted and the projection changed

- **WHEN** an upload cycle is cap-truncated (returns a still-processing result), promoted at least one
  `UPLOADED` row, and its manifest write published a changed projection
- **THEN** exactly one event notify is fired, because the union now lists assets it did not before

#### Scenario: A cycle that promoted nothing does not notify

- **WHEN** an upload cycle publishes a manifest but promoted no row this cycle — including the first,
  empty manifest of a newly-joined event
- **THEN** no event notify is fired

#### Scenario: An unchanged projection does not notify

- **WHEN** an upload cycle's manifest producer skips its write because the projection is unchanged
- **THEN** no event notify is fired, whether or not the cycle drained

#### Scenario: A re-delivered completion cannot notify twice

- **WHEN** the platform re-delivers a terminal outcome for a key whose row is already `COMPLETED`
- **THEN** the guarded write applies to nothing, the key never re-enters `UPLOADED`, the projection is
  unchanged, and no second notify is fired

#### Scenario: A cycle that cannot announce leaves rows uploaded

- **WHEN** a cycle runs on a membership whose direction excludes upload, or with no event configured, while
  the ledger holds `UPLOADED` rows
- **THEN** nothing is placed in an album, no notify is fired, and those rows remain `UPLOADED`

### Requirement: Notify is an injected best-effort, bounded seam

The notify trigger SHALL be an **injected seam** so the upload cycle stays event-agnostic and
platform-free (exercised on both JVM and `iosSimulatorArm64` with a fake). It SHALL be a **required**
constructor parameter with **no default**: a defaulted seam is one a composition root can omit silently,
and this one was omitted, which is why this capability had no integration coverage at all. A harness or
test that does not care still has to say so by passing a no-op. The seam carries the configured event id
at the composition root; the cycle SHALL
NOT itself construct any event id, URL, or HTTP client. The notify send SHALL be **best-effort**:
**non-throwing**, subject to a **bounded timeout**, and performed with **no retry** — a failing, slow,
or hung notify SHALL NOT fail, stall, or delay the upload cycle beyond that bound. Upload completions
are durable in the ledger independently of the notify, so a dropped notify never loses state (the next
foreground reconcile on recipients is the standing backstop).

#### Scenario: A failed notify does not fail the cycle

- **WHEN** the notify send errors or the endpoint returns a non-2xx status
- **THEN** the failure is absorbed (logged) and the upload cycle completes normally

#### Scenario: A slow notify is bounded

- **WHEN** the notify send hangs or is slow
- **THEN** it is abandoned at its timeout bound and does not stall the upload cycle

#### Scenario: The seam cannot be omitted

- **WHEN** an upload cycle is constructed
- **THEN** a notifier must be supplied explicitly — there is no default, so a composition root that wants no notify passes a no-op rather than saying nothing

### Requirement: Event notify request shape

The notifier SHALL issue an HTTP `POST` to `<host>/events/<eventId>/notify` for the cycle's configured
event id, with **no request body**. It SHALL build the request with string composition only — no crypto,
no signing — reusing the shared injected HTTP client, and SHALL treat any 2xx as success and any other
outcome as an absorbed failure.

The request carries the device's App Attest token, because it rides the shared client and the endpoint
requires it (capabilities `device-attestation`, `api-endpoints`). The notifier does not attach it
itself — the shared client appends `Authorization: Bearer <token>` to every request through it — so a
notify built here is authorized by construction rather than by the event id.

#### Scenario: Notify POSTs the route with no body

- **WHEN** the notifier fires for event id `E`
- **THEN** it issues `POST <host>/events/E/notify` carrying no request body
