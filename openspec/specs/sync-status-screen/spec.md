# sync status screen Specification

## Purpose

The shared status screen that observes sync status snapshots and shows the user whether backup work is idle or in progress.

## Requirements

### Requirement: Sync status snapshots reduce to UI state

The presentation layer SHALL observe `SyncStatus` snapshots (`pending`, `completed`) through the `SyncStatusSource` contract and reduce each observed snapshot to a `UiState`. The mapping SHALL be: `pending == 0` → `Idle`; `pending > 0` → `Uploading(done = completed, total = pending + completed)`. The reduction MUST depend only on the latest snapshot (no event history), so any missed intermediate snapshot cannot corrupt the displayed state.

#### Scenario: No pending uploads shows Idle
- **WHEN** a `SyncStatus` with `pending = 0` is observed
- **THEN** the UI state becomes `Idle`

#### Scenario: Pending uploads show progress X of N
- **WHEN** a `SyncStatus` with `pending = 7, completed = 3` is observed
- **THEN** the UI state becomes `Uploading` with `done = 3` and `total = 10`

#### Scenario: A newer snapshot replaces the displayed state entirely
- **WHEN** a snapshot `pending = 5, completed = 5` is observed after `pending = 9, completed = 1`, regardless of any snapshots in between
- **THEN** the UI state is `Uploading` with `done = 5` and `total = 10`, derived from the latest snapshot alone

### Requirement: Status screen renders UI state

The status screen SHALL render each `UiState` so the user can see at a glance whether backup work is in progress. `Idle` SHALL present a quiescent "up to date" appearance; `Uploading` SHALL present the progress as "X of N" together with a visual progress indication.

#### Scenario: Idle state
- **WHEN** the UI state is `Idle`
- **THEN** the screen shows the app title and an idle status with no progress indication

#### Scenario: Uploading state
- **WHEN** the UI state is `Uploading` with `done = 3` and `total = 10`
- **THEN** the screen shows an uploading status, the text "3 of 10", and a progress indication at 30%

The screen is composed under the rules of the `design-system` capability (semantic `App*` components only; Material 3 containment; `ScreenLayout` owns screen structure).
