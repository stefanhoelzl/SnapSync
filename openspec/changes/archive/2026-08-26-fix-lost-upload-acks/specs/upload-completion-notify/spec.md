## MODIFIED Requirements

### Requirement: Notify fires once per drained cycle that completed an upload

The upload cycle SHALL fire exactly one event notify for the configured event when it **fully drains**
(`CycleResult.COMPLETED` — its discover/create/drain completes without platform backpressure, no cap
truncation) **and** promoted at least one `UPLOADED` row this cycle. The notify SHALL be fired
**after** the in-cycle device-manifest write, because the event union (the
recipient's authority) reflects a newly-completed asset only once its owning device's manifest has been
written; firing before that would wake recipients to a union that does not yet list the new assets. A
cycle that is cap-truncated (does not fully drain) SHALL NOT notify — even if it promoted rows —
and a fully-drained cycle that promoted **nothing** SHALL NOT notify.

"Completion" means a row moving out of `UPLOADED` in this cycle's promotion pass. This makes
duplicate-notify suppression **structural** rather than a check: a terminal outcome re-delivered for a key
that is no longer `REQUESTED` cannot re-enter `UPLOADED` (the guarded write applies to nothing, per
`sync-ledger`), so it cannot present itself to the promotion pass a second time. The previous rule — read
the row's state before writing it, and count only a `false → true` transition — is replaced: it was a
read-then-write pair against a writer that takes no lock.

A cycle that must not announce SHALL NOT promote. A membership whose direction excludes upload, and a cycle
running with no event configured, place nothing in an album and fire no notify (`upload-lifecycle`), and
therefore leave `UPLOADED` rows as they are. Those rows stay outstanding until the device rejoins, at which
point re-join reconciliation seeds them from the device's stored-file listing
(`event-rejoin-reconciliation`) — the bytes did land, so the listing reports them.

#### Scenario: Drained cycle with a promotion notifies after the manifest write

- **WHEN** an upload cycle fully drains and promoted at least one `UPLOADED` row
- **THEN** the cycle writes the device manifest and then fires exactly one event notify for the
  configured event

#### Scenario: Cap-truncated cycle does not notify

- **WHEN** an upload cycle is cap-truncated (returns a still-processing result) even though it promoted rows
- **THEN** no event notify is fired for that cycle (the union has not been refreshed for those assets)

#### Scenario: Drained cycle with no promotion does not notify

- **WHEN** an upload cycle fully drains but promoted no row this cycle
- **THEN** no event notify is fired

#### Scenario: A re-delivered completion cannot notify twice

- **WHEN** the platform re-delivers a terminal outcome for a key whose row is already `COMPLETED`
- **THEN** the guarded write applies to nothing, the key never re-enters `UPLOADED`, and no second notify is
  fired

#### Scenario: A cycle that cannot announce leaves rows uploaded

- **WHEN** a cycle runs on a membership whose direction excludes upload, or with no event configured, while
  the ledger holds `UPLOADED` rows
- **THEN** nothing is placed in an album, no notify is fired, and those rows remain `UPLOADED`
