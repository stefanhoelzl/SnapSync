# Delta: sync-status-screen

## MODIFIED Requirements

### Requirement: Sync status snapshots reduce to UI state

The sync domain SHALL define `SyncStatus(pending, completed, failed, active, estimatedRemaining: Duration?, lastFinishedAt: Instant?)`, observed through the `SyncStatusSource` contract, whose `status` SHALL be a `StateFlow<SyncStatus>` — a level-triggered state holder whose current value is always available synchronously (implementations must know the whole truth at construction). Counts describe the most recent pass (in-flight or finished). The sync domain SHALL expose a computed `state` property as the single source of truth for classification:

- `pending > 0 && active` → **InProgress**
- `pending > 0 && !active` → **Suspended**
- `pending == 0 && lastFinishedAt == null` → **NeverSynced**
- `pending == 0 && failed == 0` → **Complete**
- `pending == 0 && failed > 0 && completed > 0` → **Incomplete**
- `pending == 0 && failed > 0 && completed == 0` → **Failed**

The presentation layer SHALL reduce each observed snapshot to a display-ready `UiState` mirroring these six states (the permission gate's variants and their permission-first precedence are specified by the `permission-gate` capability), carrying only final display data (pre-formatted strings and, for InProgress, a progress fraction computed as processed-of-total: `(completed + failed) / (pending + completed + failed)`). The reduction MUST depend only on the latest snapshot (no event history), so any missed intermediate snapshot cannot corrupt the displayed state. The container's initial UI state SHALL be computed from the sources' current values at construction — the screen MUST NOT render any state (placeholder, guess, or loading indicator) that was not derived from actual source values.

#### Scenario: Active pass classifies as InProgress
- **WHEN** a snapshot with `pending = 22, completed = 12, active = true` is observed
- **THEN** the state is InProgress with fraction ≈ 12/34

#### Scenario: Inactive pass classifies as Suspended
- **WHEN** a snapshot with `pending = 22, active = false` is observed
- **THEN** the state is Suspended

#### Scenario: Clean finished pass classifies as Complete
- **WHEN** a snapshot with `pending = 0, completed = 34, failed = 0, lastFinishedAt != null` is observed
- **THEN** the state is Complete

#### Scenario: Partial-yield pass classifies as Incomplete
- **WHEN** a snapshot with `pending = 0, completed = 31, failed = 3` is observed
- **THEN** the state is Incomplete

#### Scenario: Zero-yield pass classifies as Failed
- **WHEN** a snapshot with `pending = 0, completed = 0, failed = 34` is observed
- **THEN** the state is Failed

#### Scenario: Virgin device classifies as NeverSynced
- **WHEN** a snapshot with all counts zero and `lastFinishedAt = null` is observed
- **THEN** the state is NeverSynced

#### Scenario: A newer snapshot replaces the displayed state entirely
- **WHEN** a snapshot is observed after any earlier snapshots, regardless of any snapshots missed in between
- **THEN** the UI state derives from the latest snapshot alone

#### Scenario: No cold-start guess
- **WHEN** the container is constructed while the sync source already holds a Failed snapshot (and permission is granted)
- **THEN** the first state the screen can ever render is Failed — never an intermediate NeverSynced or loading placeholder
