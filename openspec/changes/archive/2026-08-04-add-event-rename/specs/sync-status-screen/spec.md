## ADDED Requirements

### Requirement: The joined layer offers a rename affordance on the event heading

In the `Joined` state the screen SHALL render an **edit affordance beside the event-name heading**
(`ScreenLayout`'s `onEditHeading`, capability `design-system`). Tapping it SHALL open the rename dialog
(capability `event-rename`).

Unlike the diagnostic-dump gesture — a **hidden** double-tap on the app-name label, which exposes no click
semantics precisely so it does not read as a control (capability `diagnostic-logging`) — the rename
affordance SHALL be a **visible, discoverable control** with click semantics and an accessibility label.
The two occupy different slots of the same layout, the app-name label and the heading, so neither can
shadow the other.

The affordance SHALL be present in **every** `Joined` health value — including `NeedsAccess`,
`Unattested`, `NotStarted`, `Loading`, `InSync`, and `Syncing` — because renaming needs neither photo
access nor a started event. It SHALL be **suppressed while an event-switch is in progress** (a
`pendingSwitch` is present), for the same reason the settings affordance is: a rename must not race a
switch's config write.

The affordance SHALL be offered only where a heading is rendered — so not on the create screen, not in
any join-gate phase, and not while the reconfigure surface is open.

#### Scenario: Rename appears beside the heading in every joined health state
- **WHEN** the UI state is `Joined` with health `NeedsAccess(DENIED)` and no switch is pending
- **THEN** the screen renders an edit affordance beside the event-name heading, and tapping it opens the
  rename dialog

#### Scenario: Rename is available without photo access and before the event starts
- **WHEN** the UI state is `Joined` with health `NotStarted`, or with health `NeedsAccess(NOT_DETERMINED)`
- **THEN** the rename affordance is present and tappable

#### Scenario: Rename is suppressed during a pending switch
- **WHEN** the UI state is `Joined` carrying a `pendingSwitch`
- **THEN** the rename affordance is not offered, so a rename cannot race the switch's config write

#### Scenario: Rename is absent where there is no heading
- **WHEN** the screen shows the create screen, any join-gate phase, or the reconfigure surface
- **THEN** no rename affordance is rendered

#### Scenario: The rename affordance is a control, the diagnostic gesture is not
- **WHEN** the joined screen's accessibility tree is inspected
- **THEN** the heading's rename affordance exposes a click action and a label, while the app-name label
  still exposes no click action and no control affordance

### Requirement: The joined heading reflects a renamed event

The event-name heading SHALL render the persisted membership's current name, so a successful rename
performed on this device is visible as soon as it is persisted, and a rename performed by another member
becomes visible when the membership refresh folds it in (capability `join-event`).

The rename SHALL NOT introduce a `UiState` family or a new branch in the status reduction: the rename
status SHALL be a screen-level value the container exposes alongside the event name and the membership,
exactly as those are.

#### Scenario: The heading updates after a local rename
- **WHEN** a rename succeeds and the new name is persisted
- **THEN** the heading renders the new name without any further user action

#### Scenario: The heading updates after a remote rename
- **WHEN** another member renames the event and this device's foreground refresh persists the new name
- **THEN** the heading renders the new name

#### Scenario: The reduction gains no rename branch
- **WHEN** the status reduction's `UiState` families are enumerated
- **THEN** none of them carries a rename state, and the rename status is a screen-level value like the
  event name and the membership
