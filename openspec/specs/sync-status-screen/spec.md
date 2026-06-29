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
snapshot reduces to one of the three states mirroring `SyncState` — `InProgress(synced, total, inProgress)`,
`Completed(total)`, or `NothingToSync` (the config-absent create layer and its top-rung
precedence on config presence are specified by the `event-creation-ui` capability; the
`UiState.PermissionBlocked(permission)` permission states shown when config is present but permission
is not `GRANTED` are specified below), and a `Loading` snapshot reduces to `UiState.Loading` **only
when config is present and permission is GRANTED** (an absent config short-circuits to the create
layer, and a present config with permission not `GRANTED` short-circuits to `UiState.PermissionBlocked`,
regardless of the snapshot). `UiState` carries only final display data: the displayed synced count
`synced = min(completed, total)`, the `total`, and the count of photos actively uploading for InProgress
(`inProgress`, taken from `SyncProgress.pending` — it does **not** classify and need not equal
`total - synced`); the `total` for Completed. No state carries a relative-time or "last … ago" field —
the screen reports completeness and live activity only.

Once config is present, a permission not equal to `GRANTED` SHALL reduce to
`UiState.PermissionBlocked(permission)`, outranking the snapshot reduction. `UiState.PermissionBlocked`
is a derived state (the reduction of a real non-`GRANTED` `PermissionStatus`) and is therefore permitted
under the no-placeholder rule.

There is **no** join-status reduction: `EventStatus` and the `UiState.Joining`/`UiState.JoinFailed`
states are removed (reconciliation runs in the extension and status is read from the completeness
listing — see `event-rejoin-reconciliation`). During a (re)join the screen simply shows the
listing-derived snapshot (typically `InProgress` with a rising synced count).

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

#### Scenario: Loading snapshot under satisfied gate reduces to Loading
- **WHEN** the sync source holds `SyncStatus.Loading`, config is present, and permission is GRANTED
- **THEN** the UI state is `UiState.Loading`

#### Scenario: Absent config outranks a Loading snapshot
- **WHEN** the sync source holds `SyncStatus.Loading` and config is absent (creation status `Idle`)
- **THEN** the UI state is the create-input state (the create layer), not Loading

#### Scenario: Permission blocks a snapshot when config is present
- **WHEN** config is present and permission is `DENIED` or `NOT_DETERMINED`, for any snapshot
- **THEN** the UI state is `UiState.PermissionBlocked(permission)`, not a sync hero and not the create layer

#### Scenario: A (re)join shows the listing snapshot, not a join screen
- **WHEN** config is present, permission is `GRANTED`, and a reconciliation is in flight in the extension
- **THEN** the UI state is the current listing-derived snapshot (e.g. `InProgress`), never a `Joining` or `JoinFailed` state

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

In the **joined layer** — the `InProgress`, `NothingToSync`, and `Completed` states — the screen
SHALL additionally render the **invite affordances** and a flat, icon-only **leave** action. The
invite affordances are: the **join QR** (the event's invite deeplink, derived per `event-invite-qr`)
rendered via the design system's `AppQrCode` with the caption "Scan to join this event", displayed
above the hero; and a flat, icon-only **share** action. Both the share and the leave actions are
carried through the design system's bottom-right **action cluster** (see `design-system`; the glyphs
are semantic affordances, not styled by the screen). Activating share SHALL invoke the screen's
`onShareInvite` callback (handing the invite deeplink to the platform share; fire-and-forget — the
screen observes no result). Activating leave SHALL raise the leave confirmation ("Leave event?",
confirm / cancel; see `leave-event`); confirming SHALL invoke the screen's `onLeaveEvent` callback and
dismissing SHALL change nothing. The invite affordances and the leave action SHALL NOT appear in the
loading, setup-gate, permission-blocked, joining, or join-failed states. The invite deeplink enters
the screen as a parameter (like the transient invalid-link error), not as reduced state; `UiState` and
the snapshot→state reduction are unchanged by these affordances.

The synced and total counts SHALL appear as text. For InProgress, the detail line SHALL carry the
in-progress count rendered as `"{inProgress} in progress"` **only when `inProgress > 0`** (omitted when
nothing is actively uploading); when `inProgress = 0` there is **no detail line**. The `Completed`
state SHALL render **no detail line** (the green dot and "{total} images synced" already convey a
finished, safe backup). No state renders a relative-time or "last … ago" line. The screen renders:

| State | Indicator | Count line | Detail | Invite QR + share | Leave action |
|---|---|---|---|---|---|
| Loading | Loading (indeterminate), no dot | "Loading …" | — | no | no |
| Joining | Loading (indeterminate), no dot | "Checking what's already backed up …" | — | no | no |
| JoinFailed | Error (red dot) | "Couldn't reach the server" | "Scan the event QR code again" | no | no |
| InProgress | InProgress (yellow dot) | "{synced} of {total} images synced" | "{inProgress} in progress" when inProgress > 0; absent when inProgress = 0 | yes | yes (cluster) |
| NothingToSync | Complete (green dot) | "Nothing to sync yet" | — | yes | yes (cluster) |
| Completed | Complete (green dot) | "{total} images synced" | — | yes | yes (cluster) |

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

#### Scenario: In-progress state shows the count and the in-progress caption
- **WHEN** the UI state is InProgress with `synced = 12`, `total = 47`, and `inProgress = 35`
- **THEN** the screen shows the yellow dot, the line "12 of 47 images synced", and the muted detail
  "35 in progress", with no headline and no progress ring

#### Scenario: In-progress with nothing actively uploading shows no detail line
- **WHEN** the UI state is InProgress with `synced = 3`, `total = 5`, and `inProgress = 0`
- **THEN** the screen shows the count line with no detail line (the "0 in progress" label is omitted)

#### Scenario: Nothing-to-sync state
- **WHEN** the UI state is NothingToSync
- **THEN** the screen shows the green dot and the line "Nothing to sync yet" with no detail line

#### Scenario: Completed state shows the total with no detail line
- **WHEN** the UI state is Completed with `total = 47`
- **THEN** the screen shows the green dot and the line "47 images synced", with no detail line

#### Scenario: Joined-layer states show the invite QR and share action
- **WHEN** the UI state is InProgress, NothingToSync, or Completed and an invite deeplink is supplied
- **THEN** the screen renders the join QR with the caption "Scan to join this event" above the hero and
  a flat icon-only share action in the bottom action cluster

#### Scenario: Non-joined states hide the invite affordances
- **WHEN** the UI state is Loading, Setup, PermissionBlocked, Joining, or JoinFailed
- **THEN** no invite QR, caption, or share action is rendered, even if an invite deeplink is supplied

#### Scenario: Activating share invokes the callback
- **WHEN** the user activates the share action in the joined layer
- **THEN** the screen invokes its `onShareInvite` callback and observes no result from it

#### Scenario: Joined-layer states show the leave action
- **WHEN** the UI state is InProgress, NothingToSync, or Completed
- **THEN** the screen renders a flat icon-only leave action in the bottom action cluster

#### Scenario: Non-joined states hide the leave action
- **WHEN** the UI state is Loading, Setup, Joining, or JoinFailed
- **THEN** no leave action is rendered

#### Scenario: Confirming the leave invokes the callback
- **WHEN** the user activates the leave action and confirms the "Leave event?" prompt
- **THEN** the screen invokes its `onLeaveEvent` callback

#### Scenario: Cancelling the leave changes nothing
- **WHEN** the user activates the leave action and cancels the prompt
- **THEN** the prompt is dismissed and `onLeaveEvent` is not invoked

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

