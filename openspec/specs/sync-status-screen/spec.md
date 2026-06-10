# sync status screen Specification

## Purpose

The shared status screen that observes sync status snapshots and shows the user, pass-level and truthfully, what state their backup is in: never synced, in progress, waiting, complete, incomplete, or failed.

## Requirements

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

### Requirement: Status screen renders UI state

The status screen SHALL render each of the six states as a centered two-line hero (icon + headline, with an optional muted detail line) via the design system's `StatusHero`. Item counts and progress bars MUST NOT appear as text anywhere on the screen; the progress indicator alone conveys the rough fraction.

| State | Indicator | Headline | Detail |
|---|---|---|---|
| NeverSynced | Warning | "No sync yet" | — |
| InProgress | Progress(fraction) | "Sync in progress" | estimate text |
| Suspended | Waiting | "Waiting to sync" | — |
| Complete | Success | "Sync complete" | relative time |
| Incomplete | Warning | "Sync incomplete" | relative time |
| Failed | Error | "Sync failed" | relative time |

#### Scenario: In-progress state
- **WHEN** the UI state is InProgress with fraction 0.35 and estimate text "~2 min left"
- **THEN** the screen shows a progress indicator at roughly 35%, the headline "Sync in progress", and the detail "~2 min left", with no textual count

#### Scenario: Suspended state shows a bare headline
- **WHEN** the UI state is Suspended
- **THEN** the screen shows the waiting indicator and "Waiting to sync" with no detail line

#### Scenario: Finished outcome shows relative time
- **WHEN** the UI state is Failed with relative time "5 min ago"
- **THEN** the screen shows the error indicator, "Sync failed", and the muted detail "5 min ago"

The screen is composed under the rules of the `design-system` capability (semantic components only; Material 3 containment; `ScreenLayout` owns screen structure).

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
