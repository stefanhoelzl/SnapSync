# setup-gate Specification

## RENAMED Requirements

- FROM: `### Requirement: Two-input setup precedence`
- TO: `### Requirement: Setup gate precedence on config presence`

## MODIFIED Requirements

### Requirement: Setup gate precedence on config presence

The presentation layer SHALL gate the status screen on a **single** input: the `ConfigSource`
(config present or `null`). Whenever config is absent, the screen SHALL reduce to `UiState.Setup`
and render the setup gate instead of the sync status hero, **regardless of permission status, join
status, or snapshot**. Once config is present, permission takes precedence over the join/sync chain:
whenever permission is not `GRANTED`, the screen SHALL reduce to `UiState.PermissionBlocked(permission)`
(rendered on the status screen, specified by `sync-status-screen`), **regardless of join status or
snapshot**. Only when config is present **and** permission is `GRANTED` does the join precedence apply
on the `EventStatusSource` (see `event-rejoin-reconciliation`): `Joining` SHALL reduce to
`UiState.Joining` and `JoinFailed` to `UiState.JoinFailed`; `Joined` or `Idle` fall through to the
sync status hero, reduced from the current snapshot. The reduction MUST depend only on the latest
values of the three sources (no event history). The container's initial UI state SHALL be computed
from the sources' current values at construction.

#### Scenario: No config shows the gate even when permission is granted
- **WHEN** config is `null` and permission is `GRANTED`
- **THEN** the UI state is `Setup`, not a sync hero

#### Scenario: No config shows the gate regardless of permission
- **WHEN** config is `null` and permission is `DENIED` or `NOT_DETERMINED`
- **THEN** the UI state is `Setup` (the permission step renders inside the gate), not `PermissionBlocked`

#### Scenario: Absent config outranks join status
- **WHEN** config is absent while the join status is `Joining` or `JoinFailed`
- **THEN** the UI state is `Setup`, not `Joining` or `JoinFailed`

#### Scenario: Permission blocks the hero when config is present
- **WHEN** config is present and permission is `DENIED` or `NOT_DETERMINED`, whatever the join status or snapshot
- **THEN** the UI state is `PermissionBlocked(permission)`, not `Setup` and not a sync hero

#### Scenario: A join in flight outranks the hero
- **WHEN** config is present, permission is `GRANTED`, and the join status is `Joining`
- **THEN** the UI state is `Joining`, not a sync hero

#### Scenario: A failed join outranks the hero
- **WHEN** config is present, permission is `GRANTED`, and the join status is `JoinFailed`
- **THEN** the UI state is `JoinFailed`, not a sync hero

#### Scenario: Both satisfied with no join in flight reveals the hero
- **WHEN** config is present, permission is `GRANTED`, and the join status is `Joined` or `Idle`
- **THEN** the gate disappears and the screen renders the sync status hero from the current snapshot

### Requirement: Setup gate is a stack of two checkable cards

`UiState.Setup` SHALL carry, per step, whether it is satisfied and (for the permission step) the
permission status, so the screen can render a vertical stack of two `SetupCard`s. Because the gate is
shown **only** while config is absent (see *Setup gate precedence on config presence*), the storage
step is always unsatisfied while the gate is visible; its satisfied/collapsed state is unreachable.

- **Connect your storage** — always **passive** and pending while the gate is shown: it shows the
  instruction "Open the Camera app and scan your SnapSync QR code" and carries **no** button (config
  arrives only via the external deeplink, which simultaneously dismisses the gate).
- **Allow photo access** — reflects the current permission status: while `NOT_DETERMINED` it shows the
  "Allow access" CTA; while `DENIED` it shows the "Open Settings" CTA with the denied detail; when
  `GRANTED` it collapses to a check glyph and title. (A user may grant or deny photo access while still
  on the gate, before connecting storage.)

The two steps are independent and satisfiable in any order. The screen is composed from the
`design-system` `SetupCard` within `ScreenLayout`; it is not a separate navigation destination.

#### Scenario: Fresh launch shows both cards pending
- **WHEN** `UiState.Setup` has config absent and permission `NOT_DETERMINED`
- **THEN** the screen shows a pending "Connect your storage" card with the scan instruction and no
  button, and a pending "Allow photo access" card with the "Allow access" button

#### Scenario: Denied permission shows the settings path in the gate
- **WHEN** config is absent and permission is `DENIED`
- **THEN** the permission card shows the denied detail and an "Open Settings" button, while the storage
  card is pending with its scan instruction

#### Scenario: Granted permission collapses its card while config absent
- **WHEN** config is absent and permission is `GRANTED`
- **THEN** the permission card collapses to a check glyph with "Photo access granted", while the storage
  card remains pending with its scan instruction
