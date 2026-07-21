# sync-status-screen Specification

## ADDED Requirements

### Requirement: The joined layer offers a settings affordance next to share and leave

In the `Joined` state the screen SHALL render a flat icon-only **settings** action (capability
`design-system`) in the joined-layer action cluster, ordered **settings · share · leave**. Tapping it
SHALL open the reconfigure surface (capability `reconfigure-membership`). The settings action SHALL be
present in **every** `Joined` health value — including `NeedsAccess` (`NOT_DETERMINED` / `DENIED`),
`Unattested`, `NotStarted`, `Loading`, `InSync`, and `Syncing` — because a member can change
direction / cutoff / album without photo access (enabling upload simply does nothing until access is
granted, exactly as at join). It SHALL be **suppressed while an event-switch is in progress** (a
`pendingSwitch` is present) so a reconfigure cannot race a switch's config write. Unlike the share action
— which additionally requires a non-null invite URL — the settings action's presence SHALL depend only on
the joined layer rendering (and the absence of a pending switch).

#### Scenario: Settings appears in the joined action row in every health state
- **WHEN** the UI state is `Joined` with health `NeedsAccess(DENIED)` and no switch is pending
- **THEN** the joined layer renders a settings action alongside share and leave, and tapping it opens the reconfigure surface

#### Scenario: Settings is available without photo access
- **WHEN** the UI state is `Joined` with health `NeedsAccess(NOT_DETERMINED)`
- **THEN** the settings action is present and tappable (a member may adjust direction, cutoff, or album before granting access)

#### Scenario: Settings is suppressed during a pending switch
- **WHEN** the UI state is `Joined` carrying a `pendingSwitch`
- **THEN** the settings action is not offered, so a reconfigure cannot race the switch's config write
