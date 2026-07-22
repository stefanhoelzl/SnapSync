# reconfigure-membership Specification

## Purpose

**Change the settings you picked at join, without leaving.** A member configures three participation
settings when they join an event — the capture-date **cutoff** (`photo-selection-policy`), the
upload/download **direction**, and the per-event **album** opt-in (`event-album`). Until now those were
set once and frozen: `photo-selection-policy` made the cutoff *"immutable after join in v1"*, `event-album`
made the album *"not a runtime toggle"*, and `JoinEvent`'s `AlreadyJoined` guard refuses to touch the
config of an event you are already in — so a wrong choice could be fixed only by leaving and re-joining
(which notifies the backend, re-enrolls, and re-runs reconciliation). This capability is the *v2* the
policy spec anticipated: a joined member re-opens those settings from a **settings** action beside share
and leave, and changes them **in place**. The rewrite preserves eventId, ledger, enrollment, and device
identity; it never enters the join path. Direction is a local-only gate, so a change reaches nothing on
the backend. The dangerous default the required-cutoff exists to prevent is unchanged — the `startsAt`
floor still clamps any cutoff, and the origin exclusions still filter — so the worst a member can do is
widen their own contribution above the event's start, visibly and on purpose.

Decision record: `changes/archive/2026-07-21-add-reconfigure-membership`.
## Requirements
### Requirement: A joined member changes participation settings in place, without leaving

The system SHALL let a joined member change their membership's `direction`, its capture-date **range**
(`minPhotoDate` and `maxPhotoDate` — the lower and upper bounds, `photo-selection-policy`), and
`saveToAlbum` **in place**, without leaving the event. A `ReconfigureEvent` use-case (`:domain`
`feature/membership`) SHALL read the current `EventConfig`, **guard that its `eventId` still matches** the
membership being edited, and persist
`current.copy(direction = …, minPhotoDate = …, maxPhotoDate = …, saveToAlbum = …)` as a **whole-object**
save through `ConfigStore.save` — the same one-writer discipline as
`EventName.storeEventNameIfChanged`. It SHALL NOT enter `JoinEvent` (the `AlreadyJoined` short-circuit and
the enrollment path are untouched), SHALL NOT re-enroll or clear the ledger, and SHALL preserve the
`eventId`, the sync ledger, the backend enrollment, and the device identity. Because `direction` is a
device-local gate (capability `photo-selection-policy`, `photo-download`), a reconfigure SHALL send **no**
request to the backend.

#### Scenario: A change is persisted in place
- **WHEN** a joined member confirms a reconfigure that flips direction and widens the capture-date range
- **THEN** the persisted `EventConfig` carries the new `direction`, `minPhotoDate`, and `maxPhotoDate`
  under the same `eventId`, the ledger and enrollment are unchanged, and no leave or re-enroll occurs

#### Scenario: The eventId guard prevents a stale write
- **WHEN** the current config's `eventId` no longer matches the membership the surface was opened for (e.g. a switch landed first)
- **THEN** `ReconfigureEvent` makes no write and the operation is a no-op

#### Scenario: A reconfigure reaches nothing on the backend
- **WHEN** any of direction, the capture-date range, or album is changed
- **THEN** the change is a local config write only, and no request carries it to the backend

### Requirement: The reconfigure surface reuses the join controls, pre-filled, committed atomically

The settings action SHALL open a full-screen reconfigure surface that composes the **same** design-system
controls the join surface uses — the Share-section switch header with its cutoff-preset selector
(capability `design-system`) and the album opt-in toggle — **pre-filled** with the membership's current
`direction`, `minPhotoDate`, and `saveToAlbum`, above a **read-only** event-name header for context.
Edits SHALL be committed **atomically** on a **Save** action (exactly one `ConfigStore.save`) and
**discarded** on **Cancel**. Opening and closing the surface SHALL be **client-side navigation** that
touches no port until Save, mirroring the joined layer's local leave-confirm state; it SHALL NOT introduce
a new `UiState` family. The current values SHALL be read from the presentation container's existing
config source (the same source the invite URL derives from), not from reduced state.

#### Scenario: The surface opens pre-filled with current settings
- **WHEN** a `Both` membership with `saveToAlbum = true` opens the reconfigure surface
- **THEN** both participation switches are on, the album toggle is on, the cutoff selector shows the
  current cutoff, and the event name is shown read-only

#### Scenario: Cancel discards edits
- **WHEN** the member changes controls on the surface and taps Cancel
- **THEN** no config write occurs and the membership's settings are unchanged

#### Scenario: Save commits once
- **WHEN** the member changes two controls and taps Save
- **THEN** a single whole-object `EventConfig` save is performed carrying both changes

### Requirement: The cutoff pre-fill is reconstructed from the persisted value and re-clamped to the floor

The reconfigure surface SHALL seed **both** range-bound selectors from the persisted values, because the
join UI's presets are **not** persisted — only the resulting timestamps are. A joined member SHALL edit
**both bounds in place**, each re-clamped to the event window on Save.

For the **lower bound** (From), it SHALL seed from the persisted `minPhotoDate`: when
`minPhotoDate == startsAt` it SHALL show the **Event-start** preset; otherwise it SHALL show the
**Custom** preset carrying that timestamp. The original **Now** choice SHALL NOT be reconstructable (it was
a wall-clock instant at join). A changed lower bound SHALL be clamped to the immutable `startsAt` floor —
`max(chosen, startsAt)` — exactly as at join (capability `photo-selection-policy`), so a reconfigure can
never lower a membership's cutoff below the event's start.

For the **upper bound** (Until), it SHALL seed from the persisted `maxPhotoDate`: when
`maxPhotoDate == endsAt` (or the ceiling is absent/unbounded) it SHALL show the **Event-end** preset;
otherwise it SHALL show the **Custom** preset carrying that timestamp. A changed upper bound SHALL be
clamped to the event's `endsAt` ceiling — `min(chosen, endsAt)` — exactly as at join (capability
`photo-selection-policy`), so a reconfigure can never widen a membership's range above the event's end.

#### Scenario: A cutoff equal to the floor seeds the Event-start preset
- **WHEN** the surface opens for a membership whose `minPhotoDate` equals `startsAt`
- **THEN** the lower-bound selector shows the Event-start preset selected

#### Scenario: A cutoff above the floor seeds the Custom preset
- **WHEN** the surface opens for a membership whose `minPhotoDate` is later than `startsAt`
- **THEN** the lower-bound selector shows the Custom preset carrying that timestamp

#### Scenario: A changed cutoff is clamped to the floor
- **WHEN** the member picks a lower bound earlier than `startsAt` and taps Save
- **THEN** the persisted `minPhotoDate` is `startsAt`, never the earlier value

#### Scenario: An upper bound equal to the ceiling seeds the Event-end preset
- **WHEN** the surface opens for a membership whose `maxPhotoDate` equals `endsAt`
- **THEN** the upper-bound selector shows the Event-end preset selected

#### Scenario: An upper bound below the ceiling seeds the Custom preset
- **WHEN** the surface opens for a membership whose `maxPhotoDate` is earlier than `endsAt`
- **THEN** the upper-bound selector shows the Custom preset carrying that timestamp

#### Scenario: A changed upper bound is clamped to the ceiling
- **WHEN** the member picks an upper bound later than `endsAt` and taps Save
- **THEN** the persisted `maxPhotoDate` is `endsAt`, never the later value

### Requirement: On Save the affected arms are re-driven and newly-enabled arms are kicked immediately

On a successful reconfigure Save the system SHALL re-drive the same provision-side effects a join performs,
so a change takes effect immediately rather than waiting for the OS's next scheduled cycle:

- when `saveToAlbum` is now **true**, it SHALL ensure the event album (capability `event-album`);
- when `direction` now **includes upload**, it SHALL arm the upload producer per the current photo
  permission and **schedule** the upload pump;
- when `direction` now **includes download**, it SHALL trigger a **download reconcile**.

These effects SHALL be **idempotent** (re-arming an already-armed producer, or ensuring an existing album,
is a no-op). The command wiring these effects SHALL be built only in the shared composition
(`compose/SnapSyncApp.kt`), over the existing album/upload-arm/download seams.

#### Scenario: Enabling share kicks an upload immediately
- **WHEN** a `DownloadOnly` membership is reconfigured to include upload and photo access is granted
- **THEN** the upload producer is armed and the upload pump is scheduled without waiting for the OS cadence

#### Scenario: Enabling receive triggers a reconcile
- **WHEN** an `UploadOnly` membership is reconfigured to include download
- **THEN** a download reconcile is triggered so the event union begins importing

#### Scenario: Turning the album on ensures the album
- **WHEN** a membership with `saveToAlbum = false` is reconfigured to `true` and access is granted
- **THEN** the event album is ensured (created or reused) before further syncs place photos

### Requirement: A disabling change drains in-flight uploads but cancels in-flight downloads

When a reconfigure **disables** an arm, new work SHALL stop through the next cycle's fresh config read
(capabilities `photo-selection-policy`, `photo-download`). For **in-flight** work at the moment of the
change: in-flight **uploads** SHALL be left to **drain** — the byte URL is device-partitioned and
event-independent, so an in-flight upload stays valid and cancelling it would only re-upload identical
bytes; in-flight **downloads** SHALL be **cancelled** (via `DownloadController.onLeaveOrSwitch`), so
foreign photos stop arriving once the member has turned **receive** off.

#### Scenario: Turning share off drains in-flight uploads
- **WHEN** a `Both` membership with an upload in flight is reconfigured to `DownloadOnly`
- **THEN** the in-flight upload is allowed to complete and no new upload work is started on the next cycle

#### Scenario: Turning receive off cancels in-flight downloads
- **WHEN** a `Both` membership with a download in flight is reconfigured to `UploadOnly`
- **THEN** the in-flight downloads are cancelled and no new download work is started on the next cycle

### Requirement: Consequences are surfaced as inline helper text, never a blocking dialog

The reconfigure surface SHALL communicate the consequences of a change with **inline helper text** and
SHALL NOT gate Save behind a confirmation dialog (Save itself is the confirmation). The helper text SHALL
make clear that turning the album **on** adds only photos synced **from now on** (no backfill), and that a
**narrowing** change (raising the cutoff, or turning a direction off) does **not** retract photos already
shared to or received from the event.

#### Scenario: Album-on carries forward-only helper text
- **WHEN** the album toggle is turned on on the surface
- **THEN** helper text states that only photos synced from now on are added to the album

#### Scenario: Save is not gated by a confirmation dialog
- **WHEN** the member taps Save after any combination of changes
- **THEN** the change is applied without an intervening confirmation dialog

### Requirement: Lowering the cutoff re-shares newly-in-scope older photos, on every tier

A reconfigure that **lowers** the cutoff SHALL share the newly-in-scope older photos to the event — uploaded and listed — on the next upload cycle, **on both upload tiers** (the OS-driven PhotoKit tier and the app-driven `URLSession` tier alike). Lowering the cutoff moves `minPhotoDate` earlier, widening the membership's scope above the immutable `startsAt` floor. Because the platform discovery walk is bounded by a persisted,
forward-only change cursor that would otherwise never re-visit unchanged older assets, a cutoff-lowering
reconfigure SHALL **invalidate that discovery cursor** so the next cycle performs a **full re-enumeration
at the new cutoff**; the ledger's `COMPLETED` rows still suppress re-upload of already-shared photos, so
only the genuinely newly-in-scope assets are uploaded. This invalidation SHALL be driven by the shared
domain reconfigure path (`ReconfigureEvent`), so it is **tier-agnostic** and does not depend on any one
producer's start/stop behaviour.

This makes real the widening the capability's purpose already promises ("the worst a member can do is
widen their own contribution above the event's start, visibly and on purpose") and removes the prior
silent divergence where lowering the cutoff back-shared older photos on the PhotoKit tier but not on the
`URLSession` tier.

Raising the cutoff (narrowing) SHALL remain non-retractive per the existing narrowing rule — already-
shared photos are not un-shared — and SHALL NOT require a cursor invalidation.

#### Scenario: Lowering the cutoff shares the newly-in-scope older photos on the PhotoKit tier
- **WHEN** a member on the iOS ≥26.1 PhotoKit tier reconfigures the cutoff from a later instant to an
  earlier one, bringing older in-scope photos into range
- **THEN** the next upload cycle enumerates those older photos and uploads and lists them, none having
  been shared before

#### Scenario: Lowering the cutoff shares the newly-in-scope older photos on the URLSession tier
- **WHEN** a member on the iOS 18–26.0 `URLSession` tier makes the same cutoff-lowering reconfigure
- **THEN** the discovery cursor is invalidated and the next cycle re-enumerates at the new cutoff, so the
  older in-scope photos are uploaded and listed — the same outcome as the PhotoKit tier

#### Scenario: Already-shared photos are not re-uploaded on re-enumeration
- **WHEN** the full re-enumeration after a cutoff-lowering reconfigure re-encounters photos already shared
  under the previous cutoff
- **THEN** their `COMPLETED` ledger rows suppress re-upload, so only the newly-in-scope photos upload

#### Scenario: Raising the cutoff needs no re-enumeration and un-shares nothing
- **WHEN** a member raises the cutoff (narrowing scope)
- **THEN** no discovery cursor is invalidated, no re-enumeration is forced, and photos already shared
  remain shared

### Requirement: The reconfigure surface shows a live count of the photos that will be shared

The reconfigure surface SHALL render the same live shareable-count row the join surface renders
(capability `join-event`, `join-share-count`): beneath the Share switch, `XX photos from your gallery will
be shared`, recomputed as the cutoff choice changes, with the zero-state gloss, the `counting…` state,
hidden when Share is off, and omitted when the photo grant does not permit a count. Because a cutoff-
lowering reconfigure now back-shares the newly-in-scope older photos on every tier (above), the count on
this surface is truthful: the number shown is the number that will be shared.

#### Scenario: The reconfigure surface shows the count for the pending cutoff
- **WHEN** the reconfigure surface is open with Share on and photo access permits a count
- **THEN** a row beneath the Share switch reads `XX photos from your gallery will be shared` for the
  currently-selected cutoff, updating as the member changes it

#### Scenario: Lowering the cutoff on reconfigure raises the count truthfully
- **WHEN** the member drags the cutoff earlier so more of their gallery comes into scope
- **THEN** the count rises to the new in-scope total, and confirming actually shares that many (the older
  photos are re-enumerated and uploaded on both tiers)

