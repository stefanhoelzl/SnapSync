## MODIFIED Requirements

### Requirement: Confirming enrolls the device, then provisions

The `JoinEvent` use-case SHALL, on confirm, **first** enroll the device through the dedicated **join
request** `PUT /events/:eventId/devices/:deviceId`, which carries **no body**, and **only on a successful
enrollment** commit the join by saving the config (`eventId`, the loaded name, the event's **`startsAt`**
and **`endsAt`**, the **clamped** capture-date **range** — `minPhotoDate` floor-clamped and `maxPhotoDate`
ceiling-clamped, see below and capability `photo-selection-policy` — the chosen participation
**direction**, **and whether the join opted into an event album — `saveToAlbum`**, capability
`event-album`) and, **when the chosen direction includes upload** (`Both` or `UploadOnly`), enabling the
background-upload producer.

Enrollment writes **no manifest**. Joining and contributing are separate requests: the join creates or
reactivates the membership and is the only request that decides capacity, while the manifest publish
carries contribution only. The device SHALL NOT write a register-only empty manifest, and SHALL NOT
invalidate the manifest producer's skip-if-unchanged record on joining — there is no longer a second
writer to falsify it (capability `device-manifest`).

A refusal SHALL be distinguishable rather than collapsed into a single failure. **Capacity** — the event
already holds its maximum number of devices — is a refusal the user can act on and SHALL be surfaced as
such; an **absent event** and a **transport failure** are different answers and SHALL NOT be reported as
capacity.

The persisted lower bound (`minPhotoDate`) SHALL be `max(chosen_from, startsAt)` — the event's start
applied as a **floor** — and the persisted upper bound (`maxPhotoDate`) SHALL be
`min(chosen_until, endsAt)` — the event's end applied as a **ceiling** via a new `clampToCeiling`. Both
clamps SHALL be applied in the use-case (not only in the UI) so that **every** entry path is covered —
the interactive confirm, the switch confirm, the retry, and the `autoJoin` path with a deeplink-supplied
range alike. The single `JoinEvent` choke point bounds hostile-link values from **both** sides, so a link
can never widen a membership below the event's start nor above the event's declared end.

When the chosen direction is `DownloadOnly` the producer SHALL **not** be enabled — the device still
enrolls and still runs the download machinery, but contributes no photos. Enrollment SHALL be performed
for **all** directions, so a download-only device is an enumerable, notifiable, event-alive member exactly
like a contributor; enrollment SHALL make the device a member immediately — before any photo upload — by
creating the membership itself rather than by any document it writes. A contributing device's asset
manifest is written later by the normal upload cycle, **scoped by the persisted capture-date range**. The
`saveToAlbum` choice SHALL be persisted for **all** directions (the album is populated by whichever
direction(s) sync). A **failed** enrollment SHALL keep the user on the join surface with an error and a
**Retry** action, and SHALL persist nothing and enable no producer (no half-joined state). The platform
effects (the enrollment request and the producer enable) SHALL be injected so the use-case is pure
`commonMain`.

#### Scenario: Confirm clamps the cutoff to the event's start
- **WHEN** the user confirms a join to an event whose `startsAt` is `2026-07-14T18:00:00Z` with a chosen
  from-bound of `2026-07-14T12:00:00Z` and enrollment succeeds
- **THEN** the saved config carries `minPhotoDate = 2026-07-14T18:00:00Z` and `startsAt =
  2026-07-14T18:00:00Z`

#### Scenario: A cutoff above the floor is persisted unchanged
- **WHEN** the user confirms with a chosen from-bound of `2026-07-14T21:00:00Z` against a `startsAt` of
  `2026-07-14T18:00:00Z`
- **THEN** the saved config carries `minPhotoDate = 2026-07-14T21:00:00Z` and `startsAt =
  2026-07-14T18:00:00Z`

#### Scenario: Confirm clamps the upper bound to the event's end
- **WHEN** the user confirms a join to an event whose `endsAt` is `2026-07-21T23:00:00Z` with a chosen
  until-bound of `2026-07-25T00:00:00Z` and enrollment succeeds
- **THEN** the saved config carries `maxPhotoDate = 2026-07-21T23:00:00Z` and `endsAt =
  2026-07-21T23:00:00Z`

#### Scenario: An upper bound below the ceiling is persisted unchanged
- **WHEN** the user confirms with a chosen until-bound of `2026-07-20T12:00:00Z` against an `endsAt` of
  `2026-07-21T23:00:00Z`
- **THEN** the saved config carries `maxPhotoDate = 2026-07-20T12:00:00Z` and `endsAt =
  2026-07-21T23:00:00Z`

#### Scenario: Both clamps live in the use-case, so every path is covered
- **WHEN** a range reaches `JoinEvent` from any entry path — interactive confirm, switch confirm, retry,
  or an `autoJoin` deeplink override
- **THEN** the same `max(chosen_from, startsAt)` floor clamp and `min(chosen_until, endsAt)` ceiling clamp
  are applied before the config is saved

#### Scenario: Confirm persists the album choice
- **WHEN** the user confirms with `saveToAlbum = true` and enrollment succeeds
- **THEN** the saved config carries `saveToAlbum = true` alongside the event id, name, startsAt, endsAt,
  range, and direction

#### Scenario: Confirm enrolls with a bodyless join, then commits with the direction and range
- **WHEN** the user confirms with direction `Both` and the bodyless join request succeeds
- **THEN** the config is saved with the event id, name, `startsAt`, `endsAt`, the clamped range
  (`minPhotoDate`/`maxPhotoDate`), direction `Both`, and the chosen `saveToAlbum`, the upload producer is
  enabled, and the UI reduces to `Joined`

#### Scenario: Joining writes no manifest
- **WHEN** the join request succeeds for an event this device has contributed to before
- **THEN** no manifest is written by the join, the membership's existing asset set is left intact, and the
  manifest producer's skip-if-unchanged record is not invalidated

#### Scenario: A download-only confirm enrolls but does not enable the producer
- **WHEN** the user confirms with direction `DownloadOnly` and enrollment succeeds
- **THEN** the config is saved with direction `DownloadOnly`, the upload producer is **not** enabled, and
  the device is still an enrolled member

#### Scenario: An upload-only confirm enables the producer
- **WHEN** the user confirms with direction `UploadOnly` and enrollment succeeds
- **THEN** the config is saved with direction `UploadOnly` and the upload producer is enabled

#### Scenario: A full event is refused distinguishably
- **WHEN** the join request is refused because the event already holds its maximum number of devices
- **THEN** the join surface reports that the event is full, rather than reporting a generic failure

#### Scenario: A failed enrollment does not join
- **WHEN** the user confirms and the join request fails
- **THEN** no config is saved and no producer is enabled, and the join surface shows an error with a Retry
  action

#### Scenario: Enrollment makes the device a member before any upload
- **WHEN** enrollment succeeds against an event this device has never contributed to, for any direction
- **THEN** the device holds a membership, so the event enumerates and can notify it, even though no photo
  bytes have been uploaded and no manifest has been published

### Requirement: Enrollment fires only on a genuine new join

The join request SHALL fire **only** when joining an event the device is not currently in — which, since
a switch's leave clears the config before its join commits, is always a join taken with **no config**. A
re-scan or re-provision of the event the device is **already** joined to SHALL be a no-op that issues no
join request, so re-scanning never rewrites a live membership's configuration. This is consistent with
`event-rejoin-reconciliation`'s no-op on re-provision of the already-joined event.

The join request is itself idempotent — enrolling twice is harmless — so this rule no longer protects a
manifest from being clobbered. It protects the **persisted configuration**: a re-scan must not re-run the
commit that saves the capture-date range, direction and album choice.

#### Scenario: Re-scanning the current event issues no join
- **WHEN** a deeplink for the event the device is already joined to is decoded
- **THEN** no join request is issued and the persisted membership is left untouched

#### Scenario: Switching to a different event enrolls
- **WHEN** a switch's leave has cleared the config and the member confirms the join for the new event
- **THEN** the join request for the new event is issued as part of that join
