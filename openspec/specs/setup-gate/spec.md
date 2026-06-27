# setup-gate Specification

## Purpose

The two-input setup gate that stands between the user and the sync status hero: the screen reduces
to a stack of two checkable `SetupCard`s (connect storage × allow photo access), and the gate's
intents — including the incoming config deeplink — route through the presentation container.
## Requirements
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

### Requirement: Setup gate is a stack of two checkable cards

`UiState.Setup` SHALL carry, per step, whether it is satisfied and (for the permission step) the
permission status, so the screen can render a vertical stack of two `SetupCard`s:

- **Connect your storage** — satisfied when config is present. While unsatisfied it is **passive**:
  it shows the instruction "Open the Camera app and scan your SnapSync QR code" and carries **no**
  button (config arrives only via the external deeplink). When satisfied it collapses to a check
  glyph and title "Storage connected".
- **Allow photo access** — satisfied when permission is `GRANTED`. While `NOT_DETERMINED` it shows
  the "Allow access" CTA; while `DENIED` it shows the "Open Settings" CTA with the denied detail;
  when `GRANTED` it collapses to a check glyph and title.

The two steps are independent and satisfiable in any order. The screen is composed from the
`design-system` `SetupCard` within `ScreenLayout`; it is not a separate navigation destination.

#### Scenario: Fresh launch shows both cards pending
- **WHEN** `UiState.Setup` has config absent and permission `NOT_DETERMINED`
- **THEN** the screen shows a pending "Connect your storage" card with the scan instruction and no
  button, and a pending "Allow photo access" card with the "Allow access" button

#### Scenario: Storage satisfied collapses its card
- **WHEN** config becomes present while permission is still `NOT_DETERMINED`
- **THEN** the storage card collapses to a check glyph with "Storage connected" and the permission
  card remains pending with its CTA

#### Scenario: Denied permission shows the settings path
- **WHEN** config is present and permission is `DENIED`
- **THEN** the permission card shows the denied detail and an "Open Settings" button, while the
  storage card is collapsed/satisfied

### Requirement: Gate intents route through the container

The presentation container SHALL expose the gate intents `onRequestPermission` and `onOpenSettings`,
which call the injected `PermissionRequester`'s `request()` and `openSettings()` respectively, and
an `onOpenUrl(raw: String)` intent that decodes an incoming deeplink via the `deeplink-config`
decoder and, on success, calls `ConfigStore.save`. The UI layer MUST NOT call platform permission
or config APIs directly. The system permission dialog SHALL fire only from the "Allow access"
button (CTA-only priming); observing `NOT_DETERMINED` MUST NOT trigger an automatic request.

#### Scenario: Allow access requests permission
- **WHEN** the user activates "Allow access"
- **THEN** the container invokes `PermissionRequester.request()` as a pass-through, and the result
  arrives via the permission source

#### Scenario: A valid deeplink saves config through the store
- **WHEN** `onOpenUrl` receives a structurally-valid `snapsync://config?…` URL
- **THEN** the decoded `S3Config` is handed to `ConfigStore.save`, and the resulting config change
  arrives via the `ConfigSource`

#### Scenario: No auto-request on launch
- **WHEN** the container starts observing and permission is `NOT_DETERMINED`
- **THEN** `request()` is not invoked until the user activates the CTA

### Requirement: Invalid deeplink shows a transient error

When `onOpenUrl` receives a URL the decoder rejects, the container SHALL emit a transient,
self-clearing error surfaced on the storage card (e.g. "That QR code wasn't valid") and SHALL NOT
change the persisted config. To carry this, the container's side-effect type SHALL widen from
`Nothing` to a small effect type representing the transient invalid-link error.

#### Scenario: Malformed deeplink surfaces an error and changes nothing
- **WHEN** `onOpenUrl` receives a URL the decoder rejects
- **THEN** a transient invalid-link error is emitted, the storage step stays unsatisfied, and the
  persisted config is unchanged

#### Scenario: A later valid deeplink still succeeds
- **WHEN** an invalid deeplink is followed by a valid one
- **THEN** the valid one decodes and saves normally; the earlier error does not block it

