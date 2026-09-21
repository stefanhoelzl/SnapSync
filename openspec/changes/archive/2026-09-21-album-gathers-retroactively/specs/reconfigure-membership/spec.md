## MODIFIED Requirements

### Requirement: Consequences are surfaced as inline helper text, never a blocking dialog

The reconfigure surface SHALL communicate the consequences of a change with **inline helper text** and
SHALL NOT gate Save behind a confirmation dialog (Save itself is the confirmation). The helper text SHALL
make clear that turning the album **on** also collects the photos **already synced**, not only the ones
synced from now on (capability `event-album`, *Ensuring the album gathers what the device already holds*).

The helper text SHALL further make clear that a **narrowing** change — raising the cutoff, or turning the
share direction off — **stops the affected photos being listed to the event**, and that this is
**partial**: members who have already received a photo keep it, because a received photo lives in that
member's own library and nothing on this device reaches it. The text SHALL NOT state or imply that a
narrowing change deletes, recalls, or removes photos other members already hold.

This replaces the prior formulation, under which the helper text stated that a narrowing change does **not**
retract photos already shared. That is no longer true of the listing: a narrowing change now re-projects the
device manifest against the new policy (see *A narrowing change retracts the member's listings; leaving does
not*).

Receipt is unaffected: photos this device has already **received** from the event are untouched by any
narrowing change, exactly as before.

#### Scenario: Album-on helper text includes photos already synced
- **WHEN** the album toggle is turned on on the surface
- **THEN** helper text states that the album collects the photos already synced as well as those synced
  from now on, and no helper text states that only photos synced from now on are added

#### Scenario: Narrowing carries partial-retraction helper text
- **WHEN** the member raises the cutoff or turns the share direction off on the surface
- **THEN** helper text states that the affected photos stop being listed to the event, and that members who
  already received them keep them

#### Scenario: The helper text does not overpromise removal
- **WHEN** any narrowing change is offered on the surface
- **THEN** no helper text states or implies that photos are deleted, recalled, or removed from members who
  already hold them

#### Scenario: Save is not gated by a confirmation dialog
- **WHEN** the member taps Save after any combination of changes
- **THEN** the change is applied without an intervening confirmation dialog

### Requirement: On Save the affected arms are re-driven and newly-enabled arms are kicked immediately

On a successful reconfigure Save the system SHALL re-drive the same provision-side effects a join performs,
so a change takes effect immediately rather than waiting for the OS's next scheduled cycle:

- when `saveToAlbum` is now **true**, it SHALL ensure the event album and then **start a gather** into it
  (capability `event-album`, *Ensuring the album gathers what the device already holds*). The gather runs on
  every such Save, not only one that turns the album on: a lowered cutoff or a changed direction changes
  what the device holds for the event too;
- when `direction` now **includes upload**, it SHALL arm the upload producer per the current photo
  permission and **schedule** the upload pump;
- when `direction` now **includes download**, it SHALL trigger a **download reconcile**.

Save SHALL NOT wait for the gather. It is started detached, because its cost grows with the photos the
device holds for the event, and the command that Save awaits must return when the settings are committed.

These effects SHALL be **idempotent** (re-arming an already-armed producer, ensuring an existing album, or
gathering photos already in the album, is a no-op). The command wiring these effects SHALL be built only in
the shared composition (`compose/SnapSyncApp.kt`), over the existing album/upload-arm/download seams.

#### Scenario: Enabling share kicks an upload immediately
- **WHEN** a `DownloadOnly` membership is reconfigured to include upload and photo access is granted
- **THEN** the upload producer is armed and the upload pump is scheduled without waiting for the OS cadence

#### Scenario: Enabling receive triggers a reconcile
- **WHEN** an `UploadOnly` membership is reconfigured to include download
- **THEN** a download reconcile is triggered so the event union begins importing

#### Scenario: Turning the album on ensures the album
- **WHEN** a membership with `saveToAlbum = false` is reconfigured to `true` and access is granted
- **THEN** the event album is ensured (created or reused) before further syncs place photos

#### Scenario: Saving with the album on starts a gather without waiting for it
- **WHEN** a membership is saved with `saveToAlbum = true` and access is granted
- **THEN** a gather into the event album is started after the album is ensured, and the Save command returns
  without waiting for that gather to finish
