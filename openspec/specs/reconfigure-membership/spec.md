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

The system SHALL let a joined member change their membership's `direction`, `minPhotoDate` (cutoff), and
`saveToAlbum` **in place**, without leaving the event. A `ReconfigureEvent` use-case (`:domain`
`feature/membership`) SHALL read the current `EventConfig`, **guard that its `eventId` still matches** the
membership being edited, and persist `current.copy(direction = …, minPhotoDate = …, saveToAlbum = …)` as a
**whole-object** save through `ConfigStore.save` — the same one-writer discipline as
`EventName.storeEventNameIfChanged`. It SHALL NOT enter `JoinEvent` (the `AlreadyJoined` short-circuit and
the enrollment path are untouched), SHALL NOT re-enroll or clear the ledger, and SHALL preserve the
`eventId`, the sync ledger, the backend enrollment, and the device identity. Because `direction` is a
device-local gate (capability `photo-selection-policy`, `photo-download`), a reconfigure SHALL send **no**
request to the backend.

#### Scenario: A change is persisted in place
- **WHEN** a joined member confirms a reconfigure that flips direction and lowers the cutoff
- **THEN** the persisted `EventConfig` carries the new `direction` and `minPhotoDate` under the same
  `eventId`, the ledger and enrollment are unchanged, and no leave or re-enroll occurs

#### Scenario: The eventId guard prevents a stale write
- **WHEN** the current config's `eventId` no longer matches the membership the surface was opened for (e.g. a switch landed first)
- **THEN** `ReconfigureEvent` makes no write and the operation is a no-op

#### Scenario: A reconfigure reaches nothing on the backend
- **WHEN** any of direction, cutoff, or album is changed
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

The reconfigure surface SHALL seed the cutoff selector from the persisted `minPhotoDate`, because the join
UI's cutoff presets (Now / Event start / Custom) are **not** persisted — only the resulting timestamp is.
When `minPhotoDate == startsAt` it SHALL show the **Event-start** preset; otherwise it SHALL show the
**Custom** preset carrying that timestamp. The original **Now** choice SHALL NOT be reconstructable (it was
a wall-clock instant at join). A changed cutoff SHALL be clamped to the immutable `startsAt` floor —
`max(chosen, startsAt)` — exactly as at join (capability `photo-selection-policy`), so a reconfigure can
never lower a membership's cutoff below the event's start.

#### Scenario: A cutoff equal to the floor seeds the Event-start preset
- **WHEN** the surface opens for a membership whose `minPhotoDate` equals `startsAt`
- **THEN** the cutoff selector shows the Event-start preset selected

#### Scenario: A cutoff above the floor seeds the Custom preset
- **WHEN** the surface opens for a membership whose `minPhotoDate` is later than `startsAt`
- **THEN** the cutoff selector shows the Custom preset carrying that timestamp

#### Scenario: A changed cutoff is clamped to the floor
- **WHEN** the member picks a cutoff earlier than `startsAt` and taps Save
- **THEN** the persisted cutoff is `startsAt`, never the earlier value

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

