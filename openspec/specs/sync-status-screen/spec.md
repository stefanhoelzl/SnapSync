# sync status screen Specification

## Purpose

The shared status screen that observes sync status snapshots and shows the user, truthfully,
what state their backup is in: in progress ("n of N images synced"), complete ("N images synced"),
or nothing to sync. The snapshot contract and its classification are owned by the `sync-status`
capability; this screen reduces and renders.
## Requirements
### Requirement: Sync status snapshots reduce to UI state

The presentation layer SHALL reduce each observed `SyncStatus` to a display-ready `UiState`.
`SyncProgress`, its `SyncStatusSource` seam, the `SyncStatus` vocabulary, and the three-state
classification are owned by the `sync-status` capability — this screen consumes them. A `Ready`
snapshot reduces to one of the three states mirroring `SyncState` — `InProgress(synced, total, inProgress, finishedAgo)`,
`Completed(total, finishedAgo)`, or `NothingToSync` (the setup gate's `UiState.Setup` variant and its
two-input precedence — config presence × permission status — are specified by the `setup-gate`
capability), and a `Loading` snapshot reduces to `UiState.Loading` **only when config is present and
permission is GRANTED** (an absent config or any non-`GRANTED` permission short-circuits to the setup
gate regardless of the snapshot). `UiState` carries only final display data: the displayed synced count
`synced = min(completed, total)`, the `total`, the count of photos actively uploading for InProgress
(`inProgress`, taken from `SyncProgress.pending` — it does **not** classify and need not equal
`total - synced`), and the pre-formatted relative time of the most recent completion for InProgress
(`finishedAgo`, **null** when nothing has completed yet — a bare "0 of N"); the `total` and
pre-formatted relative time for Completed.

The reduction SHALL additionally consume the `EventStatusSource` (see `event-rejoin-reconciliation`
and `setup-gate` for the full precedence): once config is present and permission is `GRANTED`,
`EventStatus.Joining` SHALL reduce to `UiState.Joining` and `EventStatus.JoinFailed` to
`UiState.JoinFailed`, both outranking any sync snapshot; `EventStatus.Joined` and `EventStatus.Idle`
fall through to the snapshot reduction above. `UiState.Joining` and `UiState.JoinFailed` are derived
states (the reduction of real `EventStatus` values) and are therefore permitted under the
no-placeholder rule.

The reduction MUST depend only on the latest snapshot (no event history), so any missed
intermediate snapshot cannot corrupt the displayed state. The container's initial UI state SHALL be
computed from the sources' current values at construction. The screen MUST NOT render any state
that was **not derived from actual source values** — but `UiState.Loading` *is* such a
derived state (it is the reduction of a real `SyncStatus.Loading`), and is therefore permitted;
the prohibition is against guesses and placeholders that no source value produced.

#### Scenario: In-progress snapshot carries the synced, total, and in-progress counts
- **WHEN** a `Ready` snapshot with `pending = 35, completed = 12, total = 47, active = true` is observed
- **THEN** the UI state is `InProgress` with `synced = 12`, `total = 47`, and `inProgress = 35`

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

#### Scenario: No cold-start guess
- **WHEN** the container is constructed while the sync source already holds `Ready(Completed)`
  (and config is present and permission is granted)
- **THEN** the first state the screen can ever render is Completed — never an intermediate state, and
  never Loading (Loading appears only for a `SyncStatus.Loading` value)

#### Scenario: Loading snapshot under satisfied gate reduces to Loading
- **WHEN** the sync source holds `SyncStatus.Loading`, config is present, and permission is GRANTED
- **THEN** the UI state is `UiState.Loading`

#### Scenario: Setup gate outranks a Loading snapshot
- **WHEN** the sync source holds `SyncStatus.Loading` and either config is absent or permission is
  `NOT_DETERMINED`
- **THEN** the UI state is `UiState.Setup`, not Loading

#### Scenario: Joining status reduces to Joining
- **WHEN** config is present, permission is `GRANTED`, and `EventStatus` is `Joining` (whatever the snapshot)
- **THEN** the UI state is `UiState.Joining`

#### Scenario: JoinFailed status reduces to JoinFailed
- **WHEN** config is present, permission is `GRANTED`, and `EventStatus` is `JoinFailed`
- **THEN** the UI state is `UiState.JoinFailed`

#### Scenario: Joined falls through to the snapshot
- **WHEN** config is present, permission is `GRANTED`, `EventStatus` is `Joined`, and the snapshot is `Ready(Completed)`
- **THEN** the UI state is `Completed` (the join status does not outrank a settled join)

### Requirement: Status screen renders UI state

The status screen SHALL render each state as a centered hero via the design system's `StatusHero`: a
single LED-style status dot above one count line, with an optional muted detail line. The dot is carried
by a **semantic** `StatusIndicator` — no color, shape, or style appears in any `App*` signature; the
Material 3 skin in `:domain:ui:components` maps the semantic indicator to pixels (`InProgress` → a
yellow dot, `Complete` → a green dot). `NothingToSync` uses the `Complete` (green) indicator. There is
no headline line and no progress ring. `UiState.Loading` SHALL render an **indeterminate** progress
indicator with the text "Loading …", no dot, no detail line and no button (the user has no action; it
auto-resolves). `UiState.Joining` SHALL render an **indeterminate** progress indicator with preparing
text ("Checking what's already backed up …"), no dot, no detail line and no button (it auto-resolves
to the hero once the join succeeds). `UiState.JoinFailed` SHALL render the `Error` indicator with a
failure message and a detail line prompting the user to scan the event QR again, with no spinner and
**no automatic retry** (re-scanning is the only retry) and no button.

The synced and total counts SHALL appear as text. For InProgress, the detail line SHALL carry a second
caption: the in-progress count rendered as `"{inProgress} in progress"` **only when `inProgress > 0`**
(omitted when nothing is actively uploading), followed by `" · {finishedAgo}"` only when a completion
exists (`finishedAgo` non-null). When both are absent there is **no detail line**. The screen renders:

| State | Indicator | Count line | Detail |
|---|---|---|---|
| Loading | Loading (indeterminate), no dot | "Loading …" | — |
| Joining | Loading (indeterminate), no dot | "Checking what's already backed up …" | — |
| JoinFailed | Error (red dot) | "Couldn't reach the server" | "Scan the event QR code again" |
| InProgress | InProgress (yellow dot) | "{synced} of {total} images synced" | "{inProgress} in progress" when inProgress > 0, joined by " · " to "{finishedAgo}" when not null; absent when neither applies |
| NothingToSync | Complete (green dot) | "Nothing to sync yet" | — |
| Completed | Complete (green dot) | "{total} images synced" | relative time |

#### Scenario: Loading state shows an indeterminate indicator
- **WHEN** the UI state is Loading
- **THEN** the screen shows an indeterminate progress indicator and the text "Loading …",
  with no dot, no detail line and no button

#### Scenario: Joining state shows a preparing indicator
- **WHEN** the UI state is Joining
- **THEN** the screen shows an indeterminate progress indicator with preparing text, no dot, no detail
  line and no button

#### Scenario: JoinFailed state prompts a re-scan with no retry control
- **WHEN** the UI state is JoinFailed
- **THEN** the screen shows the failure message and a re-scan prompt, with no spinner and
  no automatic retry

#### Scenario: In-progress state shows the count and the in-progress caption with last-sync time
- **WHEN** the UI state is InProgress with `synced = 12`, `total = 47`, `inProgress = 35`, and `finishedAgo = "5 min ago"`
- **THEN** the screen shows the yellow dot, the line "12 of 47 images synced", and the muted detail
  "35 in progress · 5 min ago", with no headline and no progress ring

#### Scenario: In-progress with no prior completion shows the in-progress caption and no time
- **WHEN** the UI state is InProgress with `synced = 0`, `total = 47`, `inProgress = 47`, and `finishedAgo = null`
- **THEN** the screen shows the yellow dot, the count line, and the muted detail "47 in progress" with
  no relative time appended

#### Scenario: In-progress with nothing actively uploading omits the in-progress label
- **WHEN** the UI state is InProgress with `synced = 3`, `total = 5`, `inProgress = 0`, and `finishedAgo = "2 min ago"`
- **THEN** the detail line shows just "2 min ago" — the "0 in progress" label is omitted

#### Scenario: In-progress with nothing uploading and no completion shows no detail line
- **WHEN** the UI state is InProgress with `inProgress = 0` and `finishedAgo = null`
- **THEN** the screen shows the count line with no detail line

#### Scenario: Nothing-to-sync state
- **WHEN** the UI state is NothingToSync
- **THEN** the screen shows the green dot and the line "Nothing to sync yet" with no detail line

#### Scenario: Completed state shows total and relative time
- **WHEN** the UI state is Completed with `total = 47` and relative time "5 min ago"
- **THEN** the screen shows the green dot, the line "47 images synced", and the muted detail "5 min ago"

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

