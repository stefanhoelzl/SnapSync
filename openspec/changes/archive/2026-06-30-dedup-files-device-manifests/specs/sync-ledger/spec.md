## MODIFIED Requirements

### Requirement: Atomic baseline reset

`LedgerBackend.resetTo(entries)` SHALL replace the entire store with `entries` in a single atomic
transaction: either all prior rows are removed and all `entries` inserted, or — on failure or
interruption — the store is left unchanged (no partial replacement is ever observable). It SHALL emit
exactly one `changes` signal on success. Entries are stored verbatim (the caller supplies `state`);
`resetTo` performs no clock stamping of its own. On the SQLDelight backend it SHALL execute as one
transaction.

The atomic baseline reset (`resetTo`, the clear-then-seed primitive) **is what rejoin reconciliation
invokes on a re-join** (an event switch, reinstall, or fresh provision). Reconciliation `resetTo`s the
ledger to exactly one `COMPLETED` row per filename in the **per-device** listing, so the clear is what
drops stale/phantom rows (e.g. a `REQUESTED` row whose job never materialized) while the
device-global, event-independent listing re-seeds the same files `COMPLETED` — preserving cross-event
dedup so globally-stored resources never re-upload after a switch. The bare-filename key is what makes
this safe: a re-seeded `COMPLETED` row keys identically across events.

#### Scenario: Interrupted reset leaves the store unchanged
- **WHEN** a `resetTo` transaction fails partway (e.g. an insert errors)
- **THEN** the store retains exactly its pre-call rows and no `changes` signal claims a new baseline

#### Scenario: Reset to a non-empty baseline is observable as a whole
- **WHEN** `resetTo(entries)` succeeds over a previously empty store
- **THEN** `aggregates()` reflects all `entries` at once and `get` returns each supplied entry verbatim

#### Scenario: Reset baseline holds on the SQLDelight backend
- **WHEN** the reset scenarios run against the SQLDelight backend on a JVM sqlite driver
- **THEN** they pass unchanged (a single-transaction replacement, one change signal)

#### Scenario: A re-join resetTo seed preserves cross-event dedup
- **WHEN** the store holds `COMPLETED` rows from a prior event plus a stale non-`COMPLETED` row, and reconciliation `resetTo`s the new event from the device-global per-device listing
- **THEN** the listing re-seeds the still-stored files `COMPLETED` (so none re-upload) and the stale row is dropped by the clear, leaving the ledger as exactly the device's stored files

## ADDED Requirements

### Requirement: Event-independent key

The ledger key SHALL be the **bare resource filename** (`<assetId>-<role>.<ext>`), carrying no event
scoping. Because the key is event-independent, a `COMPLETED` row recorded while one event is
configured stays valid and continues to read as `COMPLETED` after the configured event changes — the
ledger neither records nor consults an event when keying, recording, or reading a row. This is what
lets cross-event dedup come purely from the reconcile seed source (a `resetTo` clear-and-seed from the
device-global per-device listing) without any ledger key change.

#### Scenario: A COMPLETED row stays valid after the configured event changes
- **WHEN** a resource is recorded `COMPLETED` under one event and the configured event later changes
- **THEN** `get`/`entry` for that bare filename still returns the `COMPLETED` row, unaffected by the event change

#### Scenario: The key carries no event scoping
- **WHEN** two configured events would reference the same resource
- **THEN** they resolve to the **same** ledger key (the bare filename), so a single `COMPLETED` row serves both
