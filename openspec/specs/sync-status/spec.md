# sync status Specification

## Purpose

The status projection: the user-facing truth about the backup, minted from the engine's ledger.
Defines the `SyncStatus` snapshot contract (lifetime counts, five-state classification), the
`SyncStatusSource` seam presentation consumes, and the ledger-backed source that combines the
ledger's aggregate stream with permission-derived operational state. Lives in `:domain:status`,
which plugs the engine-type leak toward presentation. Authoritative design: docs/design.md §2.4.

## Requirements

### Requirement: SyncStatus contract — lifetime truth, five-state classification
The status domain SHALL define
`SyncStatus(pending, completed, failed, active, estimatedRemaining: Duration?, lastFinishedAt: Instant?)`
in `:domain:status` (package `app.snapsync.status`). Counts are lifetime aggregates over ledger
keys: `pending` = keys not yet proven uploaded (absent, `REQUESTED` hopes, transient `FAILED`),
`completed` = keys with `COMPLETED` proof — never "the most recent pass". `active` is operational
state ("the backup machinery is allowed to run"), never an event-recency heuristic.
`lastFinishedAt` is the newest completion recorded in the ledger; `null` means nothing has ever
completed. The type SHALL expose a computed `state` as the single source of truth for
classification, evaluated in decision-table order:

- `!active` → **SUSPENDED**
- `pending > 0` → **IN_PROGRESS**
- `lastFinishedAt == null` → **NEVER_SYNCED**
- `failed > 0` → **INCOMPLETE**
- otherwise → **COMPLETE**

`SyncState` SHALL have exactly these five values — there is no FAILED state: under
retry-forever no key is ever given up on, and a "nothing ever completed but something finished"
state is untellable when `lastFinishedAt` is the newest completion.

#### Scenario: Machinery off outranks everything
- **WHEN** a snapshot has `active = false`, regardless of all other fields
- **THEN** the state is SUSPENDED

#### Scenario: Outstanding work classifies as in progress
- **WHEN** a snapshot has `active = true` and `pending > 0`
- **THEN** the state is IN_PROGRESS

#### Scenario: Virgin ledger classifies as never synced
- **WHEN** a snapshot has `active = true`, `pending = 0`, and `lastFinishedAt = null`
- **THEN** the state is NEVER_SYNCED

#### Scenario: Finished with casualties classifies as incomplete
- **WHEN** a snapshot has `active = true`, `pending = 0`, `lastFinishedAt != null`, and `failed > 0`
- **THEN** the state is INCOMPLETE

#### Scenario: Everything proven classifies as complete
- **WHEN** a snapshot has `active = true`, `pending = 0`, `lastFinishedAt != null`, and `failed = 0`
- **THEN** the state is COMPLETE

### Requirement: SyncStatusSource seam
The status domain SHALL define `SyncStatusSource` whose `status` is a `StateFlow<SyncStatus>` —
a level-triggered state holder whose current value is always available synchronously
(implementations MUST know the whole truth at construction). Every value is the whole truth;
consumers never fold events.

#### Scenario: First value without waiting
- **WHEN** a consumer reads `status.value` immediately after obtaining a source
- **THEN** it receives a real snapshot — never a placeholder or default

### Requirement: Ledger-backed source
The status domain SHALL provide `LedgerSyncStatusSource`, constructed via a suspend factory
taking a `LedgerWatcher`, a `PermissionStatusSource`, and a `CoroutineScope`. It SHALL mint one
`SyncStatus` per input change by combining the watcher's aggregates with the current permission:
`pending`/`completed` from the aggregates, `lastFinishedAt = newestCompletionAt`,
`active = (permission == GRANTED)` — the shared operational-state rule lives here and only here —
`failed = 0` (retry-forever never gives up a key) and `estimatedRemaining = null` (v1 does not
estimate). The factory SHALL read the watcher's current truth before constructing, so the seam's
synchronous-first-value promise holds.

#### Scenario: Initial snapshot reflects the ledger at construction
- **WHEN** the factory is awaited over a ledger with 2 completed and 1 requested key
- **THEN** `status.value` immediately reports `completed = 2`, `pending = 1`

#### Scenario: A ledger change re-mints the snapshot
- **WHEN** a key is recorded `COMPLETED` after construction
- **THEN** the source emits a new snapshot with `completed` incremented and `lastFinishedAt`
  equal to that completion's timestamp

#### Scenario: Permission flip re-mints the snapshot
- **WHEN** permission changes from `GRANTED` to `DENIED` with no ledger activity
- **THEN** the source emits a snapshot with `active = false` and unchanged counts

#### Scenario: Constants of the v1 source
- **WHEN** any snapshot is minted by the ledger-backed source
- **THEN** `failed == 0` and `estimatedRemaining == null`

### Requirement: Module placement plugs the engine leak
`SyncStatus`, `SyncState`, `SyncStatusSource`, and `LedgerSyncStatusSource` SHALL live in
`:domain:status`, which depends on `:domain:engine` and `:domain:permission` with
**implementation** scope only. `:domain:presentation` SHALL depend on `:domain:status` (and
`:domain:permission`) and SHALL NOT depend on `:domain:engine` — engine types (events, decisions,
jobs, ledger) stay off presentation's compile classpath.

#### Scenario: Presentation compiles without the engine
- **WHEN** `:domain:presentation` is compiled
- **THEN** `:domain:engine` is not on its compile classpath, and no engine type is reachable
  from presentation code
