# sync-ledger — delta for delete-dead-weight

## MODIFIED Requirements

### Requirement: Reader and writer capability split

The ledger SHALL expose a concrete shared `LedgerWriter` carrying both the record operations and the
per-key query (`entry(key): LedgerEntry?`). Record and query semantics SHALL be implemented once in
this shared class, delegating storage to the injected `LedgerBackend`. There SHALL be no separate
reader type: the writer is constructed only by the composition root that owns the engine (one per
platform), and components that must not record are simply never handed a writer — app-side read
access goes through `LedgerBackend`'s read operations (`aggregates()`, per `sync-status`), never
through a writer instance.

#### Scenario: Writer reads what it wrote

- **WHEN** a `LedgerWriter` records an entry and `entry(key)` is called on the same instance
- **THEN** the recorded entry is returned

#### Scenario: Record access exists only where the writer is constructed

- **WHEN** a component is composed without receiving the root's `LedgerWriter`
- **THEN** it has no record operation available — it can read the ledger only through
  `LedgerBackend`'s read operations

### Requirement: Prune operations are writer-only

The two asset-keyed bulk removals (`deleteByAssetId`, `retainAssets`) SHALL be exposed on
`LedgerWriter` (delegating to the backend) and SHALL NOT be exposed on any other app-facing ledger
surface. They are sync writes by the single ledger writer, not the app-side `clear()` reset, and at
the writer layer they consult no engine state first (a backend may read its own rows to compute a
complement — an implementation detail, not part of the seam contract). Because only the engine's
composition root constructs a `LedgerWriter`, prune access is confined to the single-writer process,
preserving the single-writer invariant.

#### Scenario: Writer prunes by assetId

- **WHEN** a `LedgerWriter` records a row for assetId `X` (key `X-photo.jpg`) and then calls
  `deleteByAssetId("X")`
- **THEN** `entry("X-photo.jpg")` returns null

#### Scenario: Writer retains an asset set

- **WHEN** a `LedgerWriter` holds rows for assetIds `X` and `Y` and calls `retainAssets({"X"})`
- **THEN** the `Y` rows return null and the `X` rows are unchanged

#### Scenario: Prune is absent from the non-writer surface

- **WHEN** a component holds the ledger only as a `LedgerBackend` reader (no writer)
- **THEN** neither `deleteByAssetId` nor `retainAssets` is part of its sanctioned surface — prune
  reaches the backend only through the root-constructed `LedgerWriter`
