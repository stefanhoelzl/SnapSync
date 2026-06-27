# sync status screen Specification

## MODIFIED Requirements

### Requirement: Sync status snapshots reduce to UI state

The presentation layer SHALL reduce each observed `SyncStatus` to a display-ready `UiState`.
`SyncProgress`, its `SyncStatusSource` seam, the `SyncStatus` vocabulary, and the three-state
classification are owned by the `sync-status` capability — this screen consumes them. A `Ready`
snapshot reduces to one of the three states mirroring `SyncState` — `InProgress(synced, total, inProgress, finishedAgo)`,
`Completed(total, finishedAgo)`, or `NothingToSync` (the setup gate's `UiState.Setup` variant and its
single-input precedence on config presence, and the `UiState.PermissionBlocked(permission)` permission
states shown when config is present but permission is not `GRANTED`, are specified by the `setup-gate`
capability), and a `Loading` snapshot reduces to `UiState.Loading` **only when config is present and
permission is GRANTED** (an absent config short-circuits to the setup gate, and a present config with
permission not `GRANTED` short-circuits to `UiState.PermissionBlocked`, regardless of the snapshot).
`UiState` carries only final display data: the displayed synced count
`synced = min(completed, total)`, the `total`, the count of photos actively uploading for InProgress
(`inProgress`, taken from `SyncProgress.pending` — it does **not** classify and need not equal
`total - synced`), and the pre-formatted relative time of the most recent completion for InProgress
(`finishedAgo`, **null** when nothing has completed yet — a bare "0 of N"); the `total` and
pre-formatted relative time for Completed.

Once config is present, a permission not equal to `GRANTED` SHALL reduce to
`UiState.PermissionBlocked(permission)`, outranking both the `EventStatus` reduction and the snapshot
reduction. `UiState.PermissionBlocked` is a derived state (the reduction of a real non-`GRANTED`
`PermissionStatus`) and is therefore permitted under the no-placeholder rule.

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

#### Scenario: Absent config outranks a Loading snapshot
- **WHEN** the sync source holds `SyncStatus.Loading` and config is absent
- **THEN** the UI state is `UiState.Setup`, not Loading

#### Scenario: Permission blocks a Loading snapshot when config is present
- **WHEN** the sync source holds `SyncStatus.Loading`, config is present, and permission is `DENIED` or `NOT_DETERMINED`
- **THEN** the UI state is `UiState.PermissionBlocked(permission)`, not Loading

#### Scenario: Denied permission with config present reduces to PermissionBlocked
- **WHEN** config is present, permission is `DENIED`, and any snapshot is observed (e.g. `Ready(Completed)`)
- **THEN** the UI state is `UiState.PermissionBlocked(DENIED)`, not a sync hero and not `Setup`

#### Scenario: Undetermined permission with config present reduces to PermissionBlocked
- **WHEN** config is present and permission is `NOT_DETERMINED`
- **THEN** the UI state is `UiState.PermissionBlocked(NOT_DETERMINED)`, not a sync hero and not `Setup`

#### Scenario: Permission outranks join status
- **WHEN** config is present, permission is `DENIED`, and `EventStatus` is `Joining`
- **THEN** the UI state is `UiState.PermissionBlocked(DENIED)`, not `Joining`

#### Scenario: Joining status reduces to Joining
- **WHEN** config is present, permission is `GRANTED`, and `EventStatus` is `Joining` (whatever the snapshot)
- **THEN** the UI state is `UiState.Joining`

#### Scenario: JoinFailed status reduces to JoinFailed
- **WHEN** config is present, permission is `GRANTED`, and `EventStatus` is `JoinFailed`
- **THEN** the UI state is `UiState.JoinFailed`

#### Scenario: Joined falls through to the snapshot
- **WHEN** config is present, permission is `GRANTED`, `EventStatus` is `Joined`, and the snapshot is `Ready(Completed)`
- **THEN** the UI state is `Completed` (the join status does not outrank a settled join)

## ADDED Requirements

### Requirement: Status screen renders permission-blocked states

The status screen SHALL render `UiState.PermissionBlocked` as a centered `StatusHero` followed by a
single `PrimaryButton`, switching on the carried `PermissionStatus`. **No progress counts** are shown
(the live gallery total is unavailable without photo access). The hero indicator is **semantic**
(no color/shape/style in any `App*` signature). The button activates an existing container intent —
`onRequestPermission` (which calls `PermissionRequester.request()`) or `onOpenSettings` (which calls
`PermissionRequester.openSettings()`). The system permission dialog SHALL fire only from the "Allow
access" button (CTA-only priming, consistent with `setup-gate`); the screen MUST NOT auto-request on
observing `NOT_DETERMINED`. The screen renders:

| Permission | Indicator | Count line | Detail | Button → intent |
|---|---|---|---|---|
| NOT_DETERMINED | Photos | "Allow photo access" | "SnapSync needs your photo library to back it up." | "Allow access" → `onRequestPermission` |
| DENIED | Error | "Photo access turned off" | "SnapSync needs photo access to continue backing up your library." | "Open Settings" → `onOpenSettings` |

The screen is composed under the rules of the `design-system` capability (semantic components only;
Material 3 containment; `ScreenLayout` owns screen structure).

#### Scenario: Not-determined renders the allow-access priming
- **WHEN** the UI state is `PermissionBlocked(NOT_DETERMINED)`
- **THEN** the screen shows the Photos indicator, "Allow photo access", the detail line, and an "Allow
  access" button that invokes `onRequestPermission`, with no progress counts

#### Scenario: Denied renders the settings path
- **WHEN** the UI state is `PermissionBlocked(DENIED)`
- **THEN** the screen shows the Error indicator, "Photo access turned off", the detail line, and an
  "Open Settings" button that invokes `onOpenSettings`, with no progress counts

#### Scenario: No auto-request on a not-determined status
- **WHEN** the UI state becomes `PermissionBlocked(NOT_DETERMINED)`
- **THEN** `request()` is not invoked until the user activates the "Allow access" button
