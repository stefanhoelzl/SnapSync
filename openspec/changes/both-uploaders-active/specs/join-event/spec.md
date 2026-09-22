## MODIFIED Requirements

### Requirement: Confirming enrolls the device, then provisions

The `JoinEvent` use-case SHALL, on confirm, **first** enroll the device through the dedicated **join
request** `PUT /events/:eventId/devices/:deviceId`, which carries **no body**, and **only on a successful
enrollment** commit the join by saving the config (`eventId`, the loaded name, the event's **`startsAt`**
and **`endsAt`**, the **clamped** capture-date **range** — `minPhotoDate` floor-clamped and `maxPhotoDate`
ceiling-clamped, see below and capability `photo-selection-policy` — the chosen participation
**direction**, **and whether the join opted into an event album — `saveToAlbum`**, capability
`event-album`) and, for **every** direction, starting the membership's uploads through the upload arm's
join transition (capability `upload-lifecycle`): the upload extension is registered wherever the OS allows
it, and the app's uploader is armed when photo access is usable.

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

When the chosen direction is `DownloadOnly` the uploads SHALL be started exactly as for any other
direction — the extension registered where the OS allows it, the app's uploader armed — and the device
still enrolls and still runs the download machinery, but contributes no photos. It contributes none because
its selection policy admits nothing: each cycle declines on the policy (no walk, a settle, the empty
manifest, `SKIPPED`, so the app's heartbeat is never re-armed; capability `upload-lifecycle`). No direction
check SHALL stand in the join, the transitions, or either uploader. Keeping the registration is what lets a
later reconfigure to upload need no registration step, and what keeps a deregistration from wiping jobs.
Decision record: `changes/both-uploaders-active`. Enrollment SHALL be performed
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

#### Scenario: A download-only confirm enrolls and starts uploads that contribute nothing
- **WHEN** the user confirms with direction `DownloadOnly` and enrollment succeeds on iOS ≥26.1 under a
  full grant
- **THEN** the config is saved with direction `DownloadOnly`, the device is an enrolled member, the upload
  extension is registered and the app's uploader armed like any join's, and every upload cycle declines on
  the policy, uploading nothing and publishing the empty manifest

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

### Requirement: The autoJoin flag auto-confirms the gate
When a decoded event link carries `autoJoin = true`, the system SHALL run the **same** gate — decode,
fetch details, and (when already joined to a different event) leave-then-join — but SHALL **auto-fire**
the confirm once details reach the loaded phase, rather than waiting for a user tap.

The `autoJoin` reading SHALL be reached **only after** the delivery has been established as one the gate
has not already acted on (capability `event-link`): a repeat of a link whose pending join is open, or
whose event is already the joined one, is ignored **whatever `autoJoin` says**. That ordering is the
whole of the protection, because this is the one path with no confirmation surface to absorb a second
delivery — everything else asks for a tap, and a tap happens once however many times the link arrived.
The platform does deliver the same link more than once (measured: twice on an iOS 18.7.9 cold launch
~130 ms apart, and twice on iOS 26.6 both while running and cold), so before that ordering an
`autoJoin` link provisioned once per delivery. The auto-fired
confirm SHALL use the **default** cutoff (the loaded event's **`startsAt`** — never an absent cutoff,
capability `photo-selection-policy`) unless the event link carries an explicit dev/test cutoff (see capability
`event-link`), in which case that value SHALL be used **subject to the floor**: the persisted cutoff
is `max(override, startsAt)`, so an event link's cutoff can raise a membership above the event's start but
never lower it below. SHALL use the **default** direction **Both** unless the event link carries an explicit
dev/test `direction` override (`both`/`upload`/`download`, capability `event-link`), in which case
that direction SHALL be used; and SHALL use the **default** album choice **off** unless the event link
carries an explicit dev/test `saveToAlbum` override (capability `event-link`), in which case that
value SHALL be used. This keeps the headless developer launch path working (it cannot tap a confirm
control) and lets it force a direction and album choice on device; to exercise date filtering against a
distant-past library, the developer SHALL create the event with an early `startsAt` (the create screen's
picker is unbounded) rather than relying on an unclamped override. Because the auto path has no
interactive surface, a load failure (404 or network) or a failed enrollment SHALL **abort and log** rather
than parking on a retryable error state.

#### Scenario: autoJoin provisions without a tap, using startsAt as the cutoff, Both direction, and album off
- **WHEN** an event link with `autoJoin = true` and no explicit cutoff, direction, or album override is decoded and its details load successfully
- **THEN** the confirm is auto-fired with the cutoff defaulting to the loaded `startsAt`, the direction defaulting to `Both`, and `saveToAlbum` defaulting to off

#### Scenario: autoJoin honors an explicit dev/test cutoff above the floor
- **WHEN** an event link with `autoJoin = true` carries an explicit dev/test cutoff **later** than the event's `startsAt` and its details load
- **THEN** the auto-fired confirm provisions with that explicit cutoff

#### Scenario: autoJoin clamps an explicit dev/test cutoff below the floor
- **WHEN** an event link with `autoJoin = true` carries an explicit dev/test cutoff **earlier** than the event's `startsAt`
- **THEN** the auto-fired confirm provisions with `startsAt`, so a hostile QR cannot auto-join at a wider scope than the event itself allows

#### Scenario: autoJoin honors an explicit dev/test direction override
- **WHEN** an event link with `autoJoin = true` carries `direction = download` and its details load
- **THEN** the auto-fired confirm provisions with direction `DownloadOnly`

#### Scenario: autoJoin honors an explicit dev/test saveToAlbum override
- **WHEN** an event link with `autoJoin = true` carries `saveToAlbum = true` and its details load
- **THEN** the auto-fired confirm provisions with `saveToAlbum = true`, so a headless launch exercises album placement

#### Scenario: autoJoin still leaves an existing event
- **WHEN** an event link with `autoJoin = true` for a different event is decoded while already joined
- **THEN** the existing event is left first and the new event is joined, without any confirmation UI

#### Scenario: autoJoin aborts on failure instead of showing Retry
- **WHEN** the details fetch returns 404 (or the enrollment fails) on an `autoJoin` launch
- **THEN** the flow aborts and logs, presenting no retryable error surface

#### Scenario: A repeated autoJoin link provisions once
- **WHEN** the same event link carrying `autoJoin = true` is delivered twice through two different
  platform delivery hooks
- **THEN** the device provisions exactly once, the second delivery performing no enrollment, and the
  ignored repeat is recorded (capability `event-link`)

### Requirement: A provision into a new membership clears, then loads the upload ledger

The upload ledger is the **current membership's share set** (capability `sync-ledger`). A provision SHALL
therefore decide its membership **transition** first, with exactly three answers:

- **Stay** — the event being provisioned is the one already joined (a re-provision). Nothing SHALL be torn
  down and nothing SHALL be loaded, and the uploads SHALL be left exactly as they are: no registration
  call (no disable → enable toggle), and no arm, disarm or cancel of the app's uploader: the provision's
  `Stay` branch SHALL only save the config, and the upload arm's join transition SHALL be reached only through
  the membership entry that `Join` and `LeavePrevious` run (capability `upload-lifecycle`).
  A re-scan changes nothing about the membership, and the toggle would wipe the extension's in-flight
  jobs; the stale-record repair does not need it, because a reinstall wipes the config and so always
  arrives as a real join. Decision record: `changes/both-uploaders-active`.
- **Join** — no membership is configured (a first join, and every join reached after a leave — including
  the interactive switch, whose confirm runs the leave before this join commits).
- **LeavePrevious** — a different event is configured (a switch reached by a route that provisions over a
  live membership).

The provision SHALL then run in this order:

1. **Transition.** Decide `Stay` / `Join` / `LeavePrevious`.
2. **Leave the previous membership** — on `LeavePrevious` only: **stop uploads** (the upload arm's leave
   transition, capability `upload-lifecycle`), then send the best-effort backend leave of the previous
   event, awaited (a failure is logged and does not stop the provision).
3. **Load** — on `Join` and `LeavePrevious` only (never `Stay`), and **awaited**: fetch this device's stored-file listing
   (`GET /api/v2/files/devices/<deviceId>`, capability `upload-state-reconciliation`), bounded at **15
   seconds**. On success the ledger SHALL be replaced, in **one atomic** `resetTo(listing)`, by one bare
   `COMPLETED` row per resource the backend already holds, so nothing already stored re-uploads. On failure
   or timeout the ledger SHALL be **cleared** (`clear()`). Either way the new membership starts with nothing
   from before it. The load never blocks the provision.
4. **Save** the config — on every answer.
5. **Start the uploads** — on `Join` and `LeavePrevious` only (the upload arm's join transition, after the save,
   so a registered extension never reads the previous membership's config); nothing on `Stay`. Steps 2–5 on an
   entry are one ordered feature rule (the membership entry), so the provision flow keeps one call per branch.
6. Refresh status → ensure the event album → start downloads and push.

**Why clear on failure, not keep.** A leftover `COMPLETED` row inside the new window — left by a device
that departed under the old contract, which kept the ledger, or by a leave whose best-effort clear failed —
suppresses a needed upload forever with no error. The clear is what closes both cases.

**A failed load blocks nothing.** There SHALL be no flag, no gate, and no retry state: the join completes
and uploading proceeds. The stated cost is that the device re-uploads what the backend already holds —
idempotent overwrites of the same objects (the destination is `(deviceId, assetId, role)`), bounded by the
event window. A transport failure SHALL be logged at `Warn`; an undecodable listing at `Error`, because it
will not heal.

**The order is load-bearing.** The load runs **before** the save, so no cycle — an app-driven pump running
concurrently included — can ever see the **new** membership over the previous membership's ledger. A crash
between the load and the save leaves either (first join) an unjoined device holding a loaded ledger, which
the next join clears anyway, or (switch) the previous membership over a reloaded ledger, whose next walk
simply re-records its work (`DISCOVERED` rows are re-found by the walk; stored bytes are already
`COMPLETED`). Neither loses a photo. The leave and the load are one ordered entry into the new membership
(`MembershipEntry`), asked of the transition once, before the save — after the save it would always answer
`Stay`. The load also runs **before** the arm, so the first cycle the arm starts already sees the loaded rows; a
registration that survives from before the join (a device reset leaves one) finds no membership until the
save, so its cycle declines and cannot race the load either. `Stay` SHALL NOT load: a reset there would drop the `DISCOVERED`/`REQUESTED` rows of a live
membership without stopping anything.

The load SHALL run for **every** direction, including download-only, so a member who later enables upload
re-uploads nothing already stored. A reconfigure of the joined membership, including one that widens its
range, SHALL NOT re-fetch the listing: the join already loaded everything the backend holds for this
device. The load SHALL touch no download-store row (capability `download-store`).

The rule SHALL live in a `feature/membership` use-case over the `LedgerStore` and `DeviceFilesSource` ports;
the shared composition SHALL hand the provision flow a `suspend () -> Unit` effect built over it, since the
flow may not name a port (capability `module-architecture`). The app process performs the load on every
tier: the load is one of the reset-family writes whose code owns it, a guarded one-transaction write that
is safe whichever process's cycle is running (capability `sync-ledger`).

#### Scenario: A first join loads the ledger from the listing
- **WHEN** a join commits with no membership configured and the device's stored-file listing is fetched
  successfully
- **THEN** before the config is saved and before the upload mechanism is armed, the ledger is replaced in
  one transaction by a bare `COMPLETED` row per listed resource, and those resources do not re-upload

#### Scenario: A failed listing clears the ledger and the join completes
- **WHEN** a join commits and the listing fetch fails or exceeds 15 seconds
- **THEN** the ledger is cleared, the failure is logged, the join completes, and uploading proceeds with no
  gate or retry state

#### Scenario: A leftover completed row cannot suppress an upload in the new membership
- **WHEN** a device joins an event while its ledger still holds a `COMPLETED` row, from before, for a photo
  inside the new window whose bytes the backend no longer holds
- **THEN** the join's clear-then-load removes that row, and the photo is uploaded

#### Scenario: A switch stops, replaces the ledger, then saves
- **WHEN** a provision configures an event different from the one joined
- **THEN** uploads are stopped and the previous event's backend leave is sent, then the ledger is cleared
  and loaded, then the new config is saved, all before the upload mechanism is armed

#### Scenario: The interactive switch loads at its join
- **WHEN** a member confirms a switch, the leave clears the ledger and the config, and the member then
  confirms the join surface for the new event
- **THEN** that join is taken with no membership configured, and it loads the ledger like any first join

#### Scenario: Re-provisioning the joined event loads nothing
- **WHEN** the event already joined is provisioned again by any route
- **THEN** no leave transition runs and no backend leave is fired, no listing is fetched, and no ledger row
  is cleared, reset, or loaded

#### Scenario: A download-only join still loads
- **WHEN** a join commits with direction `DownloadOnly`
- **THEN** the ledger is still cleared and loaded from the listing, and the uploads are started like any
  join's, the policy admitting nothing

#### Scenario: Re-provisioning the joined event leaves the uploads untouched
- **WHEN** the event already joined is provisioned again while the upload extension is registered with
  jobs in flight and the app's uploader has transfers in flight
- **THEN** no registration call is made, the app's uploader is neither armed, disarmed nor cancelled, and
  the in-flight jobs and transfers continue and record their completions

