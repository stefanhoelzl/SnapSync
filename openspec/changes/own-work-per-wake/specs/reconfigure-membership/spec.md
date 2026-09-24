## MODIFIED Requirements

### Requirement: On Save the affected arms are re-driven and newly-enabled arms are kicked immediately

On a successful reconfigure Save the system SHALL re-drive the same provision-side effects a join performs,
so a change takes effect immediately rather than waiting for the OS's next scheduled cycle:

- when `saveToAlbum` is now **true**, it SHALL ensure the event album and then **start a gather** into it
  (capability `event-album`, *Ensuring the album gathers what the device already holds*). The gather runs on
  every such Save, not only one that turns the album on: a lowered cutoff or a changed direction changes
  what the device holds for the event too;
- whatever the new `direction`, it SHALL run the upload **reconfigure transition** (capability
  `upload-lifecycle`), which SHALL **never touch the extension's registration** — the extension is
  registered from the join to the leave wherever the OS allows it, download-only memberships included, so
  a Save has nothing to register or deregister — and SHALL **arm the app's uploader** when photo access is
  usable (`GRANTED` or `LIMITED`), which **requests** the app process's opportunistic tail — its top-up and,
  under a full grant, its walk (capability `ios-app-shell`) — and schedules the heartbeat. That arm is a kick,
  not a decision: whether the membership now contributes is the selection policy's, and a download-only policy
  makes the kicked upload units decline. Decision records: `changes/both-uploaders-active`;
  `changes/own-work-per-wake` (the tail runner, which replaced the upload pump);
- when `direction` now **includes download**, it SHALL trigger a **download reconcile**.

Save SHALL NOT wait for the gather. It is started detached, because its cost grows with the photos the
device holds for the event, and the command that Save awaits must return when the settings are committed.

These effects SHALL be **idempotent** (re-arming an armed engine, ensuring an existing album, or
gathering photos already in the album, is a no-op). The command wiring these effects SHALL be built only in
the shared composition (`compose/SnapSyncApp.kt`), over the existing album/upload-arm/download seams.

#### Scenario: Enabling share kicks an upload immediately
- **WHEN** a `DownloadOnly` membership is reconfigured to include upload and photo access is granted
- **THEN** the app's uploader is armed and the tail requested, so the newly-admitted photos start uploading
  without waiting for the OS cadence

#### Scenario: A Save never touches the extension's registration
- **WHEN** a membership is reconfigured in any direction on iOS ≥26.1 under a full grant, with the upload
  extension registered
- **THEN** no registration call is made — no disable, no enable, no deregistration — and the extension's
  in-flight jobs continue

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
