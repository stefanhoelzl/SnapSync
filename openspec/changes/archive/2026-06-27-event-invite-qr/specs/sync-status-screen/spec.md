## MODIFIED Requirements

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

The synced and total counts SHALL appear as text. For InProgress, the detail line SHALL carry a second
caption: the in-progress count rendered as `"{inProgress} in progress"` **only when `inProgress > 0`**
(omitted when nothing is actively uploading), followed by `" · {finishedAgo}"` only when a completion
exists (`finishedAgo` non-null). When both are absent there is **no detail line**. The screen renders:

| State | Indicator | Count line | Detail | Invite QR + share | Leave action |
|---|---|---|---|---|---|
| Loading | Loading (indeterminate), no dot | "Loading …" | — | no | no |
| Joining | Loading (indeterminate), no dot | "Checking what's already backed up …" | — | no | no |
| JoinFailed | Error (red dot) | "Couldn't reach the server" | "Scan the event QR code again" | no | no |
| InProgress | InProgress (yellow dot) | "{synced} of {total} images synced" | "{inProgress} in progress" when inProgress > 0, joined by " · " to "{finishedAgo}" when not null; absent when neither applies | yes | yes (cluster) |
| NothingToSync | Complete (green dot) | "Nothing to sync yet" | — | yes | yes (cluster) |
| Completed | Complete (green dot) | "{total} images synced" | relative time | yes | yes (cluster) |

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

#### Scenario: In-progress state shows the count and the in-progress caption with last-sync time
- **WHEN** the UI state is InProgress with `synced = 12`, `total = 47`, `inProgress = 35`, and `finishedAgo = "5 min ago"`
- **THEN** the screen shows the yellow dot, the line "12 of 47 images synced", and the muted detail
  "35 in progress · 5 min ago", with no headline and no progress ring

#### Scenario: In-progress with no prior completion shows the in-progress caption and no time
- **WHEN** the UI state is InProgress with `synced = 0`, `total = 47`, `inProgress = 47`, and `finishedAgo = null`
- **THEN** the screen shows the yellow dot, the count line, and the muted detail "47 in progress" with
  no relative time appended

#### Scenario: In-progress with nothing actively uploading omits the in-progress label
- **WHEN** the UI state is InProgress with `synced = 3`, `total = 5`, `inProgress = 0`, and `finishedAgo = "2 min ago"`
- **THEN** the detail line shows just "2 min ago" — the "0 in progress" label is omitted

#### Scenario: In-progress with nothing uploading and no completion shows no detail line
- **WHEN** the UI state is InProgress with `inProgress = 0` and `finishedAgo = null`
- **THEN** the screen shows the count line with no detail line

#### Scenario: Nothing-to-sync state
- **WHEN** the UI state is NothingToSync
- **THEN** the screen shows the green dot and the line "Nothing to sync yet" with no detail line

#### Scenario: Completed state shows total and relative time
- **WHEN** the UI state is Completed with `total = 47` and relative time "5 min ago"
- **THEN** the screen shows the green dot, the line "47 images synced", and the muted detail "5 min ago"

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

The screen is composed under the rules of the `design-system` capability (semantic components
only; Material 3 containment; `ScreenLayout` owns screen structure).
