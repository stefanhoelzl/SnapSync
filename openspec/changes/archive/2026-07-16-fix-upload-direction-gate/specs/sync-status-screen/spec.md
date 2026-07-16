## MODIFIED Requirements

### Requirement: Joined-layer health descriptor and status line

In the `Joined` state the screen SHALL render the event **name** as the title and a **single status
line** — never numeric counts. The status line SHALL present one of:

- `NeedsAccess` → an attention affordance reading "Turn on photo access" that is **tappable**:
  tapping SHALL invoke `onRequestPermission()` when permission is `NOT_DETERMINED` and
  `onOpenSettings()` when `DENIED`. It is the only status-line state that carries a background.
- **`NotStarted`** → a **clock** indicator reading **"Starts &lt;date&gt;, &lt;time&gt;"**, rendered in the
  device's local timezone. It is **not** tappable and carries **no** background (it is information, not
  an action). It renders in the **same slot** as every other status line — directly beneath the invite
  QR — so the joined layer never grows a second line.
- `InSync` → a settled indicator (e.g. a check) reading "In sync", with no direction arrows.
- `Syncing` → two independent direction arrows plus an **activity-dependent label**, each arrow in a
  shown/pulse state derived **from its own counts alone**:
  - **upload arrow**: hidden when `completed >= total`; else **pulsing** when `pending > 0`, otherwise
    **static**;
  - **download arrow**: hidden when `downloaded >= total`; else **pulsing** when `inFlight > 0`, otherwise
    **static** (`inFlight` from `DownloadProgress`, per the `sync-status` capability).

Arrow derivation SHALL NOT read the membership's participation direction, and SHALL NOT force-hide an arrow.
An opted-out direction contributes **no work**, so its total is `0` and its arrow is hidden by the ordinary
completeness rule: the upload total is `0` for a non-contributing membership (capability
`photo-selection-policy`), and the download total is `0` for a membership that never reconciles (capability
`photo-download`, whose total is populated only by that reconcile). The arrows therefore agree with the
direction because the counts already do.

A force-hidden arrow is prohibited because it can only ever conceal a mismatch between the direction contract
and what the system is actually doing — and concealing that mismatch is how a download-only membership came to
upload the member's camera roll for a full release cycle while the screen read "In sync" (see
`upload-lifecycle`). If the counts are right the arrow is already correct; if they are wrong, an arrow the
member never asked for is the only signal anyone gets. The display SHALL NOT assert a contract the system is
not keeping.

The `Syncing` **label** SHALL be derived from the combined arrow activity: when **any** shown arrow is
**pulsing** (work in flight), the label reads **"Synchronization ongoing…"**; when at least one arrow is
shown but **none** is pulsing (work remains but nothing is in flight), the label reads
**"Synchronization pending…"**. The exact label strings are owned by the `App*` status-line component
(see `design-system`); this screen supplies only the health value.

`InSync` SHALL be shown exactly when both arrows are hidden. Because an opted-out direction's total is `0`,
this settles over the enabled direction(s) without the screen knowing which they are: an `UploadOnly`
membership reads `InSync` when uploads are complete regardless of any foreign downloads, and a `DownloadOnly`
membership reads `InSync` when imports are complete regardless of the own-device gallery. Any remaining work
in an **enabled** direction SHALL be `Syncing` with that direction's arrow shown. "Shown" tracks
completeness; "pulse" tracks live activity — so a photo captured but not yet uploaded (on an upload-enabled
membership) shows a **static** upload arrow under the "Synchronization pending…" label. The direction remains
**silent**: no textual mode label is rendered; the single remaining arrow implies it.

#### Scenario: The not-started line names the start instant
- **WHEN** the health is `NotStarted` for an event starting at `2026-07-14T18:00:00Z` and the device is in
  a `UTC+2` zone
- **THEN** the status line shows a clock indicator reading the start rendered in local time (`20:00` on
  14 Jul), beneath the QR, flat and not tappable

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
- **WHEN** either a shown upload or a shown download arrow is pulsing
- **THEN** the label reads "Synchronization ongoing…", regardless of the other direction's state

#### Scenario: Upload-only hides the download arrow through its zero total
- **WHEN** the membership is `UploadOnly` (so no reconcile ever runs and the download total is `0`) and
  `completed >= total`
- **THEN** both arrows are hidden and the status line reads "In sync"

#### Scenario: Download-only hides the upload arrow through its zero total
- **WHEN** the membership is `DownloadOnly` (so the upload total is `0`), `downloaded >= total`, and the
  own-device gallery holds un-uploaded photos
- **THEN** both arrows are hidden and the status line reads "In sync" — the un-uploaded gallery does not
  count toward the total, so it does not keep the screen out of sync

#### Scenario: Download-only with imports remaining shows only the download arrow
- **WHEN** the membership is `DownloadOnly` and `downloaded < total`
- **THEN** only the download arrow is shown (the upload arrow is hidden by its zero total) and the label
  reflects the download activity

#### Scenario: An upload arrow appears if a non-contributing membership ever uploads
- **WHEN** the membership is `DownloadOnly` yet the upload counts report work — the direction gate is not
  being honored
- **THEN** the upload arrow is **shown**, because no mask suppresses it; the screen surfaces the breach
  rather than reading "In sync"

#### Scenario: Both complete reads In sync
- **WHEN** the membership is `Both`, `completed >= total`, and `downloaded >= total`
- **THEN** the status line reads "In sync" with no arrows

#### Scenario: Needs-access line is tappable to the right action
- **WHEN** the health is `NeedsAccess(NOT_DETERMINED)` and the status line is tapped
- **THEN** `onRequestPermission()` is invoked; **WHEN** the health is `NeedsAccess(DENIED)` and it is
  tapped, `onOpenSettings()` is invoked
