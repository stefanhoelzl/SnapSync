# sync status screen Specification

## Purpose

The shared status screen that observes sync status snapshots and shows the user, truthfully,
what state their backup is in: in progress ("n of N images synced"), complete ("N images synced"),
or nothing to sync. The snapshot contract and its classification are owned by the `sync-status`
capability; this screen reduces and renders.
## Requirements
### Requirement: Sync status snapshots reduce to UI state

The presentation layer SHALL reduce config presence, permission, each observed `SyncStatus`, and any
**pending join** to a display-ready `UiState`. `SyncProgress`, its `SyncStatusSource` seam, the
`SyncStatus` vocabulary, and the three-state classification are owned by the `sync-status` capability —
this screen consumes them.

`UiState` SHALL have exactly these families: the create layer (`CreateEvent(error?)` /
`CreatingEvent`, owned by the `event-creation-ui` capability and outranking everything on config
absence); the **`JoiningEvent`** family (owned by the `join-event` capability, carrying the pending
`eventId` and its details phase), which represents an interactive join confirmation in progress; and a
single **`Joined`** state carrying a health descriptor and an optional **`pendingSwitch`** (owned by
`join-event`) for a switch confirmation over the joined screen. The prior joined states `InProgress`,
`Completed`, and `NothingToSync`, the permission state `PermissionBlocked`, and the standalone
`Loading` state are **removed** and fold into `Joined` (the joined loading first-frame is a health
value, `SyncHealth.Loading`).

The reduction SHALL be: a **pending interactive join with config absent** → `JoiningEvent`
(outranking the create layer); otherwise **config absent** → the create layer (per
`event-creation-ui`); **config present** → `Joined`, **always**, regardless of permission, carrying a
`pendingSwitch` when a switch confirmation is in progress. The `Joined` health descriptor SHALL be
derived from permission and the latest snapshot:

- permission ≠ `GRANTED` → `NeedsAccess(permission)` (the sole attention state; there is no separate
  "not syncing" state — the status projection's only operational signal is permission);
- else `SyncStatus.Loading` → a joined loading first-frame;
- else `SyncStatus.Ready` → `InSync` when settled, else `Syncing(...)` (the completeness/activity
  arrow derivation is specified in *Joined-layer health descriptor and status line*).

`UiState` SHALL carry **no** upload/download counts — the joined states no longer surface `synced`,
`total`, or an in-progress number. The event **name** and the invite URL are supplied to the screen as
parameters (per `event-invite-qr` and the config capability), not as reduced state, so the reduction
gains no branch for them.

The reduction MUST depend only on the latest snapshot (no event history). The container's initial UI
state SHALL be computed from the sources' current values at construction. `UiState.Loading` and every
`Joined` health value are derived from real source values (never placeholders). Once a join has
committed there is no join-status reduction: during the (re)join provisioning the screen simply shows
the current `Joined` health (typically `Syncing`); the `JoiningEvent` family is the **pre-commit**
confirmation gate only.

#### Scenario: Settled snapshot reduces to In sync
- **WHEN** config is present, permission is `GRANTED`, and a `Ready` snapshot with `completed == total`
  is observed (downloads also settled)
- **THEN** the UI state is `Joined` with health `InSync`

#### Scenario: Work remaining reduces to Syncing
- **WHEN** config is present, permission is `GRANTED`, and a `Ready` snapshot with `completed < total`
  is observed
- **THEN** the UI state is `Joined` with health `Syncing(...)`, and no synced/total counts are carried

#### Scenario: Permission off with config present reduces to NeedsAccess, not a gate
- **WHEN** config is present and permission is `DENIED` or `NOT_DETERMINED`, for any snapshot
- **THEN** the UI state is `Joined` with health `NeedsAccess(permission)` — the joined layer still
  renders (name, QR, share, leave), and there is no hero-replacing `PermissionBlocked` screen

#### Scenario: Absent config outranks everything
- **WHEN** config is absent (creation status `Idle`), no interactive join is pending, for any permission and any snapshot
- **THEN** the UI state is the create layer, not `Joined`

#### Scenario: A pending interactive join outranks the create layer
- **WHEN** config is absent but an interactive join has been decoded and is awaiting confirmation
- **THEN** the UI state is the `JoiningEvent` family for that pending event, not the create layer

#### Scenario: A newer snapshot replaces the displayed health entirely
- **WHEN** a snapshot is observed after any earlier snapshots, regardless of any missed in between
- **THEN** the `Joined` health derives from the latest snapshot alone

### Requirement: Status screen renders UI state

The status screen SHALL render each state as a centered hero via the design system's `StatusHero`: a
single LED-style status dot above one count line, with an optional muted detail line. The dot is carried
by a **semantic** `StatusIndicator` — no color, shape, or style appears in any `App*` signature; the
Material 3 skin in `:domain:ui:components` maps the semantic indicator to pixels (`InProgress` → a
yellow dot, `Complete` → a primary dot). `NothingToSync` uses the `Complete` (primary) indicator. There is
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
state SHALL render **no detail line** (the primary dot and "{total} images synced" already convey a
finished, safe backup). No state renders a relative-time or "last … ago" line. The screen renders:

| State | Indicator | Count line | Detail | Invite QR + share | Leave action |
|---|---|---|---|---|---|
| Loading | Loading (indeterminate), no dot | "Loading …" | — | no | no |
| Joining | Loading (indeterminate), no dot | "Checking what's already backed up …" | — | no | no |
| JoinFailed | Error (red dot) | "Couldn't reach the server" | "Scan the event QR code again" | no | no |
| InProgress | InProgress (yellow dot) | "{synced} of {total} images synced" | "{inProgress} in progress" when inProgress > 0; absent when inProgress = 0 | yes | yes (cluster) |
| NothingToSync | Complete (primary dot) | "Nothing to sync yet" | — | yes | yes (cluster) |
| Completed | Complete (primary dot) | "{total} images synced" | — | yes | yes (cluster) |

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
- **THEN** the screen shows the primary dot and the line "Nothing to sync yet" with no detail line

#### Scenario: Completed state shows the total with no detail line
- **WHEN** the UI state is Completed with `total = 47`
- **THEN** the screen shows the primary dot and the line "47 images synced", with no detail line

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

### Requirement: Joined-layer health descriptor and status line

In the `Joined` state the screen SHALL render the event **name** as the title and a **single status
line** — never numeric counts. The status line SHALL present one of:

- `NeedsAccess` → an attention affordance reading "Turn on photo access" that is **tappable**:
  tapping SHALL invoke `onRequestPermission()` when permission is `NOT_DETERMINED` and
  `onOpenSettings()` when `DENIED`. It is the only status-line state that carries a background.
- `InSync` → a settled indicator (e.g. a check) reading "In sync", with no direction arrows.
- `Syncing` → two independent direction arrows plus an **activity-dependent label**, each arrow in a
  shown/pulse state derived as follows:
  - **upload arrow**: hidden when `completed >= total`; else **pulsing** when `pending > 0`, otherwise
    **static**;
  - **download arrow**: hidden when `downloaded >= total`; else **pulsing** when `inFlight > 0`,
    otherwise **static** (`inFlight` from `DownloadProgress`, per the `sync-status` capability).

The `Syncing` **label** SHALL be derived from the combined arrow activity: when **any** shown arrow is
**pulsing** (work in flight), the label reads **"Synchronization ongoing…"**; when at least one arrow is
shown but **none** is pulsing (work remains but nothing is in flight), the label reads
**"Synchronization pending…"**. The exact label strings are owned by the `App*` status-line component
(see `design-system`); this screen supplies only the health value.

`InSync` SHALL be shown exactly when both arrows would be hidden (upload complete **and** download
complete); any remaining work SHALL be `Syncing` with the corresponding arrow(s) shown. "Shown" tracks
completeness; "pulse" tracks live activity — so a photo captured but not yet uploaded shows a **static**
upload arrow under the "Synchronization pending…" label (honest that work remains without faking motion).

#### Scenario: Upload in flight pulses the up arrow and reads ongoing
- **WHEN** `completed < total`, `pending > 0`, and downloads are complete
- **THEN** the status line reads "Synchronization ongoing…" with the upload arrow **pulsing** and the
  download arrow hidden

#### Scenario: Work queued but OS idle shows a static arrow and reads pending
- **WHEN** `completed < total` and `pending == 0`
- **THEN** the upload arrow is **shown static** (not pulsing) and the line reads "Synchronization
  pending…"

#### Scenario: Download in flight pulses the down arrow and reads ongoing
- **WHEN** uploads are complete, `downloaded < total`, and `inFlight > 0`
- **THEN** the status line reads "Synchronization ongoing…" with the download arrow **pulsing** and the
  upload arrow hidden

#### Scenario: Any direction in flight reads ongoing
- **WHEN** either the upload or the download arrow is pulsing
- **THEN** the label reads "Synchronization ongoing…", regardless of the other direction's state

#### Scenario: Both complete reads In sync
- **WHEN** `completed >= total` and `downloaded >= total`
- **THEN** the status line reads "In sync" with no arrows

#### Scenario: Needs-access line is tappable to the right action
- **WHEN** the health is `NeedsAccess(NOT_DETERMINED)` and the status line is tapped
- **THEN** `onRequestPermission()` is invoked; **WHEN** the health is `NeedsAccess(DENIED)` and it is
  tapped, `onOpenSettings()` is invoked

