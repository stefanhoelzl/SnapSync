## MODIFIED Requirements

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

Duplicate-notify suppression stays **structural**: a terminal outcome re-delivered for a key that is no
longer `REQUESTED` cannot re-enter `UPLOADED` (the guarded write applies to nothing, per `sync-ledger`),
so it changes no row, so the projection is unchanged, so no second notify is fired.

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
