## MODIFIED Requirements

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
