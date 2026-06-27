## MODIFIED Requirements

### Requirement: Two-input setup precedence

The presentation layer SHALL gate the status screen on **two** inputs: the `ConfigSource`
(config present or `null`) and the `PermissionStatusSource` (the photo-library status). Whenever
config is absent **or** permission is not `GRANTED`, the screen SHALL reduce to `UiState.Setup`
and render the setup gate instead of the sync status hero, **regardless of join status**. Once config
is present **and** permission is `GRANTED`, a second precedence applies on the `EventStatusSource`
(see `event-rejoin-reconciliation`): `Joining` SHALL reduce to `UiState.Joining` and `JoinFailed` to
`UiState.JoinFailed`; only when the join status is `Joined` or `Idle` (no join in flight or needed)
SHALL the sync status hero appear, reduced from the current snapshot. The reduction MUST depend only
on the latest values of the three sources (no event history). The container's initial UI state SHALL
be computed from the sources' current values at construction.

#### Scenario: No config shows the gate even when permission is granted
- **WHEN** config is `null` and permission is `GRANTED`
- **THEN** the UI state is `Setup`, not a sync hero

#### Scenario: Granted permission alone does not reveal the hero
- **WHEN** permission is `GRANTED` but config is `null`
- **THEN** the gate remains; the sync hero is not shown

#### Scenario: Setup outranks join status
- **WHEN** config is absent or permission is not `GRANTED`, while the join status is `Joining` or `JoinFailed`
- **THEN** the UI state is `Setup`, not `Joining` or `JoinFailed`

#### Scenario: A join in flight outranks the hero
- **WHEN** config is present, permission is `GRANTED`, and the join status is `Joining`
- **THEN** the UI state is `Joining`, not a sync hero

#### Scenario: A failed join outranks the hero
- **WHEN** config is present, permission is `GRANTED`, and the join status is `JoinFailed`
- **THEN** the UI state is `JoinFailed`, not a sync hero

#### Scenario: Both satisfied with no join in flight reveals the hero
- **WHEN** config is present, permission is `GRANTED`, and the join status is `Joined` or `Idle`
- **THEN** the gate disappears and the screen renders the sync status hero from the current snapshot
