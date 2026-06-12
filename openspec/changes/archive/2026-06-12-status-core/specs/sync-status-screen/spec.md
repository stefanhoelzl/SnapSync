# sync status screen — delta

## MODIFIED Requirements

### Requirement: Sync status snapshots reduce to UI state

`SyncStatus`, its `SyncStatusSource` seam, and the five-state classification are owned by the
`sync-status` capability — this screen consumes them. The presentation layer SHALL reduce each
observed snapshot to a display-ready `UiState` mirroring the five `SyncState` values (the
permission gate's variants and their permission-first precedence are specified by the
`permission-gate` capability), carrying only final display data (pre-formatted strings and, for
InProgress, a progress fraction computed as processed-of-total:
`(completed + failed) / (pending + completed + failed)`). The reduction MUST depend only on the
latest snapshot (no event history), so any missed intermediate snapshot cannot corrupt the
displayed state. The container's initial UI state SHALL be computed from the sources' current
values at construction — the screen MUST NOT render any state (placeholder, guess, or loading
indicator) that was not derived from actual source values.

#### Scenario: In-progress snapshot carries a fraction
- **WHEN** a snapshot with `pending = 22, completed = 12, failed = 0, active = true` is observed
- **THEN** the UI state is InProgress with fraction ≈ 12/34

#### Scenario: A newer snapshot replaces the displayed state entirely
- **WHEN** a snapshot is observed after any earlier snapshots, regardless of any snapshots missed
  in between
- **THEN** the UI state derives from the latest snapshot alone

#### Scenario: No cold-start guess
- **WHEN** the container is constructed while the sync source already holds an Incomplete
  snapshot (and permission is granted)
- **THEN** the first state the screen can ever render is Incomplete — never an intermediate
  NeverSynced or loading placeholder

### Requirement: Status screen renders UI state

The status screen SHALL render each of the five states as a centered two-line hero (icon +
headline, with an optional muted detail line) via the design system's `StatusHero`. Item counts
and progress bars MUST NOT appear as text anywhere on the screen; the progress indicator alone
conveys the rough fraction.

| State | Indicator | Headline | Detail |
|---|---|---|---|
| NeverSynced | Warning | "No sync yet" | — |
| InProgress | Progress(fraction) | "Sync in progress" | estimate text |
| Suspended | Waiting | "Waiting to sync" | — |
| Complete | Success | "Sync complete" | relative time |
| Incomplete | Warning | "Sync incomplete" | relative time |

#### Scenario: In-progress state
- **WHEN** the UI state is InProgress with fraction 0.35 and estimate text "~2 min left"
- **THEN** the screen shows a progress indicator at roughly 35%, the headline "Sync in
  progress", and the detail "~2 min left", with no textual count

#### Scenario: Suspended state shows a bare headline
- **WHEN** the UI state is Suspended
- **THEN** the screen shows the waiting indicator and "Waiting to sync" with no detail line

#### Scenario: Finished outcome shows relative time
- **WHEN** the UI state is Incomplete with relative time "5 min ago"
- **THEN** the screen shows the warning indicator, "Sync incomplete", and the muted detail
  "5 min ago"

The screen is composed under the rules of the `design-system` capability (semantic components
only; Material 3 containment; `ScreenLayout` owns screen structure).
