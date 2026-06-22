## MODIFIED Requirements

### Requirement: Sync status snapshots reduce to UI state

The presentation layer SHALL reduce each observed `SyncStatus` to a display-ready `UiState`.
`SyncProgress`, its `SyncStatusSource` seam, the `SyncStatus` vocabulary, and the three-state
classification are owned by the `sync-status` capability — this screen consumes them. A `Ready`
snapshot reduces to one of the three states mirroring `SyncState` — `InProgress(synced, total, finishedAgo)`,
`Completed(total, finishedAgo)`, or `NothingToSync` (the setup gate's `UiState.Setup` variant and its
two-input precedence — config presence × permission status — are specified by the `setup-gate`
capability), and a `Loading` snapshot reduces to `UiState.Loading` **only when config is present and
permission is GRANTED** (an absent config or any non-`GRANTED` permission short-circuits to the setup
gate regardless of the snapshot). `UiState` carries only final display data: the displayed synced count
`synced = min(completed, total)`, the `total`, and the pre-formatted relative time of the most recent
completion for InProgress (`finishedAgo`, **null** when nothing has completed yet — a bare "0 of N");
the `total` and pre-formatted relative time for Completed.

The reduction MUST depend only on the latest snapshot (no event history), so any missed intermediate
snapshot cannot corrupt the displayed state. The container's initial UI state SHALL be computed from the
sources' current values at construction. The screen MUST NOT render any state that was **not derived
from actual source values** — but `UiState.Loading` *is* such a derived state (it is the reduction of a
real `SyncStatus.Loading`), and is therefore permitted; the prohibition is against guesses and
placeholders that no source value produced.

#### Scenario: In-progress snapshot carries the synced and total counts and last-sync time
- **WHEN** a `Ready` snapshot with `completed = 12, total = 47` and a completion 5 minutes ago is observed
- **THEN** the UI state is `InProgress` with `synced = 12`, `total = 47`, and `finishedAgo = "5 min ago"`

#### Scenario: In-progress with no prior completion has no last-sync time
- **WHEN** a `Ready` snapshot with `completed = 0, total = 47, lastFinishedAt = null` is observed
- **THEN** the UI state is `InProgress` with `synced = 0`, `total = 47`, and `finishedAgo = null`

#### Scenario: Completed snapshot carries the total
- **WHEN** a `Ready` snapshot with `completed = 47, total = 47` is observed
- **THEN** the UI state is `Completed` with `total = 47`

#### Scenario: Empty library reduces to nothing-to-sync
- **WHEN** a `Ready` snapshot with `total = 0` is observed
- **THEN** the UI state is `NothingToSync`

#### Scenario: Overshoot clamps the displayed synced count
- **WHEN** a `Ready` snapshot with `completed = 6, total = 5` is observed
- **THEN** the UI state is `Completed` with `total = 5` (the synced count never displays as `6`)

#### Scenario: A newer snapshot replaces the displayed state entirely
- **WHEN** a snapshot is observed after any earlier snapshots, regardless of any snapshots missed
  in between
- **THEN** the UI state derives from the latest snapshot alone

#### Scenario: Loading snapshot under satisfied gate reduces to Loading
- **WHEN** the sync source holds `SyncStatus.Loading`, config is present, and permission is GRANTED
- **THEN** the UI state is `UiState.Loading`

#### Scenario: Setup gate outranks a Loading snapshot
- **WHEN** the sync source holds `SyncStatus.Loading` and either config is absent or permission is
  `NOT_DETERMINED`
- **THEN** the UI state is `UiState.Setup`, not Loading

### Requirement: Status screen renders UI state

The status screen SHALL render each state as a centered hero via the design system's `StatusHero`: a
single LED-style status dot above one count line, with an optional muted detail line. The dot is carried
by a **semantic** `StatusIndicator` — no color, shape, or style appears in any `App*` signature; the
Material 3 skin in `:domain:ui:components` maps the semantic indicator to pixels (`InProgress` → a
yellow dot, `Complete` → a green dot). `NothingToSync` uses the `Complete` (green) indicator. There is
no headline line and no progress ring. `UiState.Loading` SHALL render an **indeterminate** progress
indicator with the text "Loading …", no dot, no detail line and no button (the user has no action; it
auto-resolves).

The synced and total counts SHALL appear as text. The screen renders:

| State | Indicator | Count line | Detail |
|---|---|---|---|
| Loading | Loading (indeterminate), no dot | "Loading …" | — |
| InProgress | InProgress (yellow dot) | "{synced} of {total} images synced" | relative time, or none when `finishedAgo` is null |
| NothingToSync | Complete (green dot) | "Nothing to sync yet" | — |
| Completed | Complete (green dot) | "{total} images synced" | relative time |

#### Scenario: Loading state shows an indeterminate indicator
- **WHEN** the UI state is Loading
- **THEN** the screen shows an indeterminate progress indicator and the text "Loading …",
  with no dot, no detail line and no button

#### Scenario: In-progress state shows the count and last-sync time as text
- **WHEN** the UI state is InProgress with `synced = 12`, `total = 47`, and `finishedAgo = "5 min ago"`
- **THEN** the screen shows the yellow dot, the line "12 of 47 images synced", and the muted detail
  "5 min ago", with no headline and no progress ring

#### Scenario: In-progress with no prior completion shows no detail line
- **WHEN** the UI state is InProgress with `finishedAgo = null`
- **THEN** the screen shows the yellow dot and the count line only, with no detail line

#### Scenario: Nothing-to-sync state
- **WHEN** the UI state is NothingToSync
- **THEN** the screen shows the green dot and the line "Nothing to sync yet" with no detail line

#### Scenario: Completed state shows total and relative time
- **WHEN** the UI state is Completed with `total = 47` and relative time "5 min ago"
- **THEN** the screen shows the green dot, the line "47 images synced", and the muted detail "5 min ago"

The screen is composed under the rules of the `design-system` capability (semantic components
only; Material 3 containment; `ScreenLayout` owns screen structure).

## REMOVED Requirements

### Requirement: Estimates are minted at snapshot emission

**Reason**: This version never estimates remaining time (`estimatedRemaining ≡ null`), and the
redesigned screen has no estimate detail line — InProgress now shows the textual "{synced} of {total}
images synced" instead. Retaining an estimate requirement (including the "estimating…" placeholder)
would describe UI that no longer exists.

**Migration**: None. `SyncProgress.estimatedRemaining` remains `null` for classification/fakes but is
never rendered. Any presentation logic formatting or aging an estimate is removed.
