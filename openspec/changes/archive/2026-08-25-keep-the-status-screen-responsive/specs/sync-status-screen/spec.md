## MODIFIED Requirements

### Requirement: The joined layer offers a settings affordance next to share and leave

In the `Joined` state the screen SHALL render a flat icon-only **settings** action (capability
`design-system`) in the joined-layer action cluster, ordered **settings · share · leave**. Tapping it
SHALL open the reconfigure surface (capability `reconfigure-membership`). The settings action SHALL be
present in **every** `Joined` health value — including `NeedsAccess` (`NOT_DETERMINED` / `DENIED`),
`Unattested`, `NotStarted`, `Loading`, `InSync`, and `Syncing` — because a member can change
direction / cutoff / album without photo access (enabling upload simply does nothing until access is
granted, exactly as at join). It SHALL be present **whether or not a `pendingSwitch` is carried**: a
reconfigure racing a switch's config write is already prevented by `ReconfigureEvent`'s own `eventId`
guard (a surface opened for a different membership persists nothing) and by the screen closing the
surface when the config clears, and suppressing it here suppressed it during a join's own in-flight
commit too — the commit carries a pending join for the event being joined, which is not a switch.
Unlike the share action — which additionally requires a non-null invite URL — the settings action's
presence SHALL depend only on the joined layer rendering.

#### Scenario: Settings appears in the joined action row in every health state
- **WHEN** the UI state is `Joined` with health `NeedsAccess(DENIED)`
- **THEN** the joined layer renders a settings action alongside share and leave, and tapping it opens the reconfigure surface

#### Scenario: Settings is available without photo access
- **WHEN** the UI state is `Joined` with health `NeedsAccess(NOT_DETERMINED)`
- **THEN** the settings action is present and tappable (a member may adjust direction, cutoff, or album before granting access)

#### Scenario: Settings stays offered while a pending join or switch is carried
- **WHEN** the UI state is `Joined` carrying a `pendingSwitch`
- **THEN** the settings action is still offered, exactly as it is with none — including for the whole of a join's own commit, which carries a pending join for the event being joined

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
access nor a started event. It SHALL be present **whether or not a `pendingSwitch` is carried**, for the
same reason the settings affordance is: `RenameEvent`'s own `eventId` guard already makes a rename that
lands after a switch a no-op, and suppressing it here suppressed it for the whole of a join's own commit.

The affordance SHALL be offered only where a heading is rendered — so not on the create screen, not in
any join-gate phase, and not while the reconfigure surface is open.

#### Scenario: Rename appears beside the heading in every joined health state
- **WHEN** the UI state is `Joined` with health `NeedsAccess(DENIED)`
- **THEN** the screen renders an edit affordance beside the event-name heading, and tapping it opens the
  rename dialog

#### Scenario: Rename is available without photo access and before the event starts
- **WHEN** the UI state is `Joined` with health `NotStarted`, or with health `NeedsAccess(NOT_DETERMINED)`
- **THEN** the rename affordance is present and tappable

#### Scenario: Rename stays offered while a pending join or switch is carried
- **WHEN** the UI state is `Joined` carrying a `pendingSwitch`
- **THEN** the rename affordance is still offered, exactly as it is with none — including for the whole of a join's own commit

#### Scenario: Rename is absent where there is no heading
- **WHEN** the screen shows the create screen, any join-gate phase, or the reconfigure surface
- **THEN** no rename affordance is rendered

#### Scenario: The rename affordance is a control, the diagnostic gesture is not
- **WHEN** the joined screen's accessibility tree is inspected
- **THEN** the heading's rename affordance exposes a click action and a label, while the app-name label
  still exposes no click action and no control affordance

## ADDED Requirements

### Requirement: A failing command never disables the status container

The presentation container SHALL keep processing commands after any one of them fails. A throwable escaping
a single command SHALL NOT prevent a later command from running, and SHALL NOT terminate the composition's
scope.

This is a **liveness** requirement about the container, not about any one screen: the commands crossing it
are every user tap the app has — leave, share, settings save, rename, join confirm, cancel, create — so a
container that stops processing them leaves a screen that renders its last state, looks alive, and silently
ignores the member. The MVI library's default is the opposite: with no exception handler configured it
re-throws, cancelling the non-supervisor job that parents every command, after which no later command runs
for the life of the process. The container SHALL therefore configure a handler rather than rely on that
default, and the requirement SHALL be pinned by a test so a library upgrade that changes the behavior fails
the build instead of silently restoring it.

The failure SHALL NOT become silent in the process (law "Absence is never silent"). The seam carrying the
throwable SHALL default to inert, so harnesses and tests that do not bind it construct unchanged — but every
**live composition** SHALL bind it, and SHALL report the throwable at **`Error`** severity, which is the
threshold at which a log line becomes a crash-reporting event rather than a breadcrumb (capability
`crash-reporting`). Binding nothing in a shipped app would lose the only signal that a user's command failed
at all, since the container now absorbs the throwable instead of letting it reach the composition scope.

#### Scenario: A later command still runs after one fails
- **WHEN** a command throws, and another command is issued afterwards
- **THEN** the later command runs and its state change is observed

#### Scenario: The failure is reported, not swallowed
- **WHEN** a command throws in a live composition
- **THEN** the throwable is reported at `Error` severity through the container's error seam, reaching the
  device log and the crash reporter

#### Scenario: The composition scope survives
- **WHEN** a command throws
- **THEN** the composition's scope remains active and the app does not terminate
