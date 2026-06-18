## MODIFIED Requirements

### Requirement: Sync status snapshots reduce to UI state

The presentation layer SHALL reduce each observed `SyncStatus` to a display-ready `UiState`.
`SyncProgress`, its `SyncStatusSource` seam, the `SyncStatus` vocabulary, and the five-state
classification are owned by the `sync-status` capability — this screen consumes them. A `Ready`
snapshot reduces to one of the five states mirroring `SyncState` (the setup gate's `UiState.Setup`
variant and its two-input precedence — config presence × permission status — are specified by the
`setup-gate` capability), and a `Loading` snapshot reduces to `UiState.Loading` **only when config
is present and permission is GRANTED** (an absent config or any non-`GRANTED` permission
short-circuits to the setup gate regardless of the snapshot). `UiState` carries only final display
data (pre-formatted strings and, for InProgress, a progress fraction computed as processed-of-total:
`(completed + failed) / (pending + completed + failed)`).

The reduction MUST depend only on the latest snapshot (no event history), so any missed intermediate
snapshot cannot corrupt the displayed state. The container's initial UI state SHALL be computed from
the sources' current values at construction. The screen MUST NOT render any state that was **not
derived from actual source values** — but `UiState.Loading` *is* such a derived state (it is the
reduction of a real `SyncStatus.Loading`), and is therefore permitted; the prohibition is against
guesses and placeholders that no source value produced.

#### Scenario: In-progress snapshot carries a fraction
- **WHEN** a `Ready` snapshot with `pending = 22, completed = 12, failed = 0, active = true` is observed
- **THEN** the UI state is InProgress with fraction ≈ 12/34

#### Scenario: A newer snapshot replaces the displayed state entirely
- **WHEN** a snapshot is observed after any earlier snapshots, regardless of any snapshots missed
  in between
- **THEN** the UI state derives from the latest snapshot alone

#### Scenario: No cold-start guess
- **WHEN** the container is constructed while the sync source already holds `Ready(Incomplete)`
  (and config is present and permission is granted)
- **THEN** the first state the screen can ever render is Incomplete — never an intermediate
  NeverSynced, and never Loading (Loading appears only for a `SyncStatus.Loading` value)

#### Scenario: Loading snapshot under satisfied gate reduces to Loading
- **WHEN** the sync source holds `SyncStatus.Loading`, config is present, and permission is GRANTED
- **THEN** the UI state is `UiState.Loading`

#### Scenario: Setup gate outranks a Loading snapshot
- **WHEN** the sync source holds `SyncStatus.Loading` and either config is absent or permission is
  `NOT_DETERMINED`
- **THEN** the UI state is `UiState.Setup`, not Loading
