## MODIFIED Requirements

### Requirement: Joined-layer health descriptor and status line

In the `Joined` state the screen SHALL render the event **name** as the title and a **single status
line** — never numeric counts. The status line SHALL present one of:

- `NeedsAccess` → an attention affordance reading "Turn on photo access" that is **tappable**:
  tapping SHALL invoke `onRequestPermission()` when permission is `NOT_DETERMINED` and
  `onOpenSettings()` when `DENIED`. It is the only status-line state that carries a background.
- `InSync` → a settled indicator (e.g. a check) reading "In sync", with no direction arrows.
- `Syncing` → two independent direction arrows plus an **activity-dependent label**, each arrow in a
  shown/pulse state derived as follows, **masked by the membership's participation direction**
  (`EventConfig.direction`):
  - **upload arrow**: **force-hidden** when the direction excludes upload (`DownloadOnly`); otherwise
    hidden when `completed >= total`; else **pulsing** when `pending > 0`, otherwise **static**;
  - **download arrow**: **force-hidden** when the direction excludes download (`UploadOnly`); otherwise
    hidden when `downloaded >= total`; else **pulsing** when `inFlight > 0`, otherwise **static**
    (`inFlight` from `DownloadProgress`, per the `sync-status` capability).

The `Syncing` **label** SHALL be derived from the combined arrow activity: when **any** shown arrow is
**pulsing** (work in flight), the label reads **"Synchronization ongoing…"**; when at least one arrow is
shown but **none** is pulsing (work remains but nothing is in flight), the label reads
**"Synchronization pending…"**. The exact label strings are owned by the `App*` status-line component
(see `design-system`); this screen supplies only the health value.

`InSync` SHALL be shown exactly when both arrows would be hidden — where a masked (force-hidden) arrow
counts as hidden — so `InSync` is computed over the **enabled direction(s) only**: an `UploadOnly`
membership reads `InSync` when uploads are complete regardless of any foreign downloads, and a
`DownloadOnly` membership reads `InSync` when imports are complete regardless of the own-device gallery.
Any remaining work in an **enabled** direction SHALL be `Syncing` with that direction's arrow shown.
"Shown" tracks completeness; "pulse" tracks live activity — so a photo captured but not yet uploaded (on
an upload-enabled membership) shows a **static** upload arrow under the "Synchronization pending…" label.
The masking is **silent**: no textual mode label is rendered; the single remaining arrow implies the
direction.

#### Scenario: Upload in flight pulses the up arrow and reads ongoing
- **WHEN** direction includes both, `completed < total`, `pending > 0`, and downloads are complete
- **THEN** the status line reads "Synchronization ongoing…" with the upload arrow **pulsing** and the
  download arrow hidden

#### Scenario: Work queued but OS idle shows a static arrow and reads pending
- **WHEN** direction includes upload, `completed < total` and `pending == 0`
- **THEN** the upload arrow is **shown static** (not pulsing) and the line reads "Synchronization
  pending…"

#### Scenario: Download in flight pulses the down arrow and reads ongoing
- **WHEN** direction includes download, uploads are complete, `downloaded < total`, and `inFlight > 0`
- **THEN** the status line reads "Synchronization ongoing…" with the download arrow **pulsing** and the
  upload arrow hidden

#### Scenario: Any direction in flight reads ongoing
- **WHEN** either a shown upload or a shown download arrow is pulsing
- **THEN** the label reads "Synchronization ongoing…", regardless of the other direction's state

#### Scenario: Upload-only masks the download arrow and reads In sync when uploads complete
- **WHEN** direction is `UploadOnly`, `completed >= total`, and foreign downloads are irrelevant (never fetched)
- **THEN** the download arrow is force-hidden, the upload arrow is hidden, and the status line reads "In sync"

#### Scenario: Download-only masks the upload arrow and reads In sync when imports complete
- **WHEN** direction is `DownloadOnly`, `downloaded >= total`, and the own-device gallery has un-uploaded photos
- **THEN** the upload arrow is force-hidden, the download arrow is hidden, and the status line reads "In sync" (the un-uploaded gallery does not keep it out of sync)

#### Scenario: Download-only with imports remaining shows only the download arrow
- **WHEN** direction is `DownloadOnly` and `downloaded < total`
- **THEN** only the download arrow is shown (the upload arrow is force-hidden) and the label reflects the download activity

#### Scenario: Both complete reads In sync
- **WHEN** direction is `Both`, `completed >= total`, and `downloaded >= total`
- **THEN** the status line reads "In sync" with no arrows

#### Scenario: Needs-access line is tappable to the right action
- **WHEN** the health is `NeedsAccess(NOT_DETERMINED)` and the status line is tapped
- **THEN** `onRequestPermission()` is invoked; **WHEN** the health is `NeedsAccess(DENIED)` and it is
  tapped, `onOpenSettings()` is invoked

### Requirement: Status screen renders permission-blocked states

The status screen SHALL render `UiState.PermissionBlocked` as a centered `StatusHero` followed by a
single `PrimaryButton`, switching on the carried `PermissionStatus`. **No progress counts** are shown
(the live gallery total is unavailable without photo access). The hero indicator is **semantic**
(no color/shape/style in any `App*` signature). The button activates an existing container intent —
`onRequestPermission` (which calls `PermissionRequester.request()`) or `onOpenSettings` (which calls
`PermissionRequester.openSettings()`). The system permission dialog SHALL fire only from the "Allow
access" button (CTA-only priming, consistent with `setup-gate`); the screen MUST NOT auto-request on
observing `NOT_DETERMINED`. The detail copy SHALL use **sync/share framing** — it MUST NOT describe the
app's function as "backing up" the user's library (consistent with the sharing-framing requirement in
capability `event-creation-ui`). The screen renders:

| Permission | Indicator | Count line | Detail | Button → intent |
|---|---|---|---|---|
| NOT_DETERMINED | Photos | "Allow photo access" | "SnapSync needs your photo library to sync this event's photos." | "Allow access" → `onRequestPermission` |
| DENIED | Error | "Photo access turned off" | "SnapSync needs photo access to keep syncing this event's photos." | "Open Settings" → `onOpenSettings` |

The screen is composed under the rules of the `design-system` capability (semantic components only;
Material 3 containment; `ScreenLayout` owns screen structure).

#### Scenario: Not-determined renders the allow-access priming
- **WHEN** the UI state is `PermissionBlocked(NOT_DETERMINED)`
- **THEN** the screen shows the Photos indicator, "Allow photo access", the sync-framed detail line, and an "Allow
  access" button that invokes `onRequestPermission`, with no progress counts

#### Scenario: Denied renders the settings path
- **WHEN** the UI state is `PermissionBlocked(DENIED)`
- **THEN** the screen shows the Error indicator, "Photo access turned off", the sync-framed detail line, and an
  "Open Settings" button that invokes `onOpenSettings`, with no progress counts

#### Scenario: Permission copy avoids backup framing
- **WHEN** either permission-blocked detail line renders
- **THEN** its copy frames the action as syncing/sharing event photos and does not describe it as backing up the user's photo library

#### Scenario: No auto-request on a not-determined status
- **WHEN** the UI state becomes `PermissionBlocked(NOT_DETERMINED)`
- **THEN** `request()` is not invoked until the user activates the "Allow access" button
