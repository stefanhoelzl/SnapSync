# sync status screen Specification

## Purpose

The shared status screen that observes sync status snapshots and shows the user, truthfully,
what state their backup is in: never synced, in progress, waiting, complete, or incomplete.
The snapshot contract and its classification are owned by the `sync-status` capability; this
screen reduces and renders.
## Requirements
### Requirement: Sync status snapshots reduce to UI state

The presentation layer SHALL reduce each observed `SyncStatus` to a display-ready `UiState`.
`SyncProgress`, its `SyncStatusSource` seam, the `SyncStatus` vocabulary, and the five-state
classification are owned by the `sync-status` capability — this screen consumes them. A `Ready`
snapshot reduces to one of the five states mirroring `SyncState` (the permission gate's variants
and their permission-first precedence are specified by the `permission-gate` capability), and a
`Loading` snapshot reduces to `UiState.Loading` **only when permission is GRANTED** (any
non-`GRANTED` permission short-circuits to the gate regardless of the snapshot). `UiState` carries
only final display data (pre-formatted strings and, for InProgress, a progress fraction computed as
processed-of-total: `(completed + failed) / (pending + completed + failed)`).

The reduction MUST depend only on the latest snapshot (no event history), so any missed
intermediate snapshot cannot corrupt the displayed state. The container's initial UI state SHALL be
computed from the sources' current values at construction. The screen MUST NOT render any state
that was **not derived from actual source values** — but `UiState.Loading` *is* such a
derived state (it is the reduction of a real `SyncStatus.Loading`), and is therefore permitted;
the prohibition is against guesses and placeholders that no source value produced.

#### Scenario: In-progress snapshot carries a fraction
- **WHEN** a `Ready` snapshot with `pending = 22, completed = 12, failed = 0, active = true` is observed
- **THEN** the UI state is InProgress with fraction ≈ 12/34

#### Scenario: A newer snapshot replaces the displayed state entirely
- **WHEN** a snapshot is observed after any earlier snapshots, regardless of any snapshots missed
  in between
- **THEN** the UI state derives from the latest snapshot alone

#### Scenario: No cold-start guess
- **WHEN** the container is constructed while the sync source already holds `Ready(Incomplete)`
  (and permission is granted)
- **THEN** the first state the screen can ever render is Incomplete — never an intermediate
  NeverSynced, and never Loading (Loading appears only for a `SyncStatus.Loading` value)

#### Scenario: Loading snapshot under granted permission reduces to Loading
- **WHEN** the sync source holds `SyncStatus.Loading` and permission is GRANTED
- **THEN** the UI state is `UiState.Loading`

#### Scenario: Permission gate outranks a Loading snapshot
- **WHEN** the sync source holds `SyncStatus.Loading` and permission is `NOT_DETERMINED`
- **THEN** the UI state is the permission ask gate, not Loading

### Requirement: Status screen renders UI state

The status screen SHALL render each state as a centered two-line hero (icon +
headline, with an optional muted detail line) via the design system's `StatusHero`. Item counts
and progress bars MUST NOT appear as text anywhere on the screen; the progress indicator alone
conveys the rough fraction. `UiState.Loading` SHALL render an **indeterminate** progress indicator
with the headline "Loading …", no detail line and no button (the user has no action; it auto-resolves).

| State | Indicator | Headline | Detail |
|---|---|---|---|
| Loading | Loading (indeterminate) | "Loading …" | — |
| NeverSynced | Warning | "No sync yet" | — |
| InProgress | Progress(fraction) | "Sync in progress" | estimate text |
| Suspended | Waiting | "Waiting to sync" | — |
| Complete | Success | "Sync complete" | relative time |
| Incomplete | Warning | "Sync incomplete" | relative time |

#### Scenario: Loading state shows an indeterminate indicator
- **WHEN** the UI state is Loading
- **THEN** the screen shows an indeterminate progress indicator and the headline "Loading …",
  with no detail line and no button

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

### Requirement: Presentation formats and ticks relative time

The presentation layer SHALL format `lastFinishedAt` into coarse relative-time text (e.g. "just now", "5 min ago", "2 h ago") using an injected `Clock`, and SHALL re-emit the UI state periodically (~once per minute) only when the visible text would change. The UI layer MUST NOT perform time formatting or own a clock.

#### Scenario: Relative time ages on screen
- **WHEN** the displayed state is Complete with "just now" and 5 minutes pass with no new snapshot
- **THEN** the displayed detail becomes "5 min ago" without any snapshot being observed

#### Scenario: Tests control time
- **WHEN** presentation tests advance the injected clock
- **THEN** the emitted relative-time text changes deterministically

### Requirement: Estimates are minted at snapshot emission

`estimatedRemaining` SHALL be valid as of the snapshot's emission: sources MUST compute it when emitting a snapshot and MUST NOT persist a previously computed estimate; a source that cannot estimate SHALL emit `null`. The presentation layer SHALL render the estimate verbatim in coarse buckets (e.g. "less than a minute left", "~2 min left") and MUST NOT age it between snapshots. While InProgress with a `null` estimate, the detail line SHALL show "estimating…"; estimates are never rendered outside InProgress.

#### Scenario: Null estimate renders as placeholder
- **WHEN** a snapshot classifies as InProgress with `estimatedRemaining = null`
- **THEN** the detail line shows "estimating…"

#### Scenario: Estimate is not aged by presentation
- **WHEN** an InProgress snapshot with a 2-minute estimate is displayed and time passes with no new snapshot
- **THEN** the displayed estimate text remains "~2 min left" until a new snapshot replaces it

