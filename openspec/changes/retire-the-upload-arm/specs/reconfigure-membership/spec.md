## MODIFIED Requirements

### Requirement: On Save the affected arms are re-driven and newly-enabled arms are kicked immediately

On a successful reconfigure Save the system SHALL re-drive the same provision-side effects a join performs,
so a change takes effect immediately rather than waiting for the OS's next scheduled cycle:

- when `saveToAlbum` is now **true**, it SHALL ensure the event album and then **start a gather** into it
  (capability `event-album`, *Ensuring the album gathers what the device already holds*). The gather runs on
  every such Save, not only one that turns the album on: a lowered cutoff or a changed direction changes
  what the device holds for the event too;
- when `direction` now **includes upload**, it SHALL run the upload **reconfigure transition**
  (capability `upload-lifecycle`): the compared registration reconcile — registering the OS-driven
  extension through its ritual when it is wanted and absent, as it is after a download-only join — and,
  where resolution yields the app-driven engine, arming it, which **schedules** the upload pump;
- when `direction` now **includes download**, it SHALL trigger a **download reconcile**.

Save SHALL NOT wait for the gather. It is started detached, because its cost grows with the photos the
device holds for the event, and the command that Save awaits must return when the settings are committed.

These effects SHALL be **idempotent** (re-arming an armed engine, finding a wanted registration already
present, ensuring an existing album, or
gathering photos already in the album, is a no-op). The command wiring these effects SHALL be built only in
the shared composition (`compose/SnapSyncApp.kt`), over the existing album/upload-arm/download seams.

#### Scenario: Enabling share kicks an upload immediately
- **WHEN** a `DownloadOnly` membership is reconfigured to include upload and photo access is granted
- **THEN** the upload mechanism resolution yields is brought up — the app engine armed and its pump
  scheduled, or the extension registered — without waiting for the OS cadence

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

#### Scenario: Enabling share on the OS-driven tier registers the extension
- **WHEN** a `DownloadOnly` membership, joined under `GRANTED` on iOS ≥26.1 (so the extension was deregistered at
  the join), is reconfigured to include upload
- **THEN** the extension is registered through the disable → demote → enable ritual, so the OS can invoke it

### Requirement: A disabling change drains in-flight uploads but cancels in-flight downloads

When a reconfigure **disables** an arm, new work SHALL stop through the next cycle's fresh config read
(capabilities `photo-selection-policy`, `photo-download`). For **in-flight** work at the moment of the
change: in-flight **uploads** SHALL be left to **drain** — the byte URL is device-partitioned and
event-independent, so an in-flight upload stays valid and cancelling it would only re-upload identical
bytes; in-flight **downloads** SHALL be **cancelled** (via `DownloadController.onLeaveOrSwitch`), so
foreign photos stop arriving once the member has turned **receive** off.

A drained upload SHALL be **settled**, not merely allowed to finish. Its terminal outcome SHALL be
acknowledged to the platform and recorded in the ledger, by the same cycle path that settles any other
terminal job (capability `upload-lifecycle`, *The gate bounds new work, not settlement*). Draining
without settling delivers none of the benefit the drain is justified by: the bytes land, but no durable
state records that they did, so the ledger row remains un-terminal and a later re-enable re-uploads
exactly the resources the drain preserved — the outcome the "cancelling would only re-upload identical
bytes" rationale exists to avoid. Settling is also what discharges the platform's acknowledgement
obligation on a tier whose extension the disable deliberately leaves registered; leaving it undischarged
was measured to make the system discard the outstanding jobs and defer the extension.

Turning a direction off SHALL run no upload transition: it SHALL NOT disarm the app-driven engine and SHALL
NOT deregister an OS-driven upload extension, because either is what would cancel the in-flight work this
requirement preserves.
The obligations that follow from the extension remaining registered are the cycle's to discharge, per
`upload-lifecycle`.

#### Scenario: Turning share off drains in-flight uploads
- **WHEN** a `Both` membership with an upload in flight is reconfigured to `DownloadOnly`
- **THEN** the in-flight upload is allowed to complete and no new upload work is started on the next cycle

#### Scenario: A drained upload is recorded, not merely completed
- **WHEN** an upload that was in flight at the moment of a disabling reconfigure completes
- **THEN** its terminal outcome is acknowledged and recorded in the ledger, so re-enabling the direction
  later does not re-upload that resource

#### Scenario: Turning receive off cancels in-flight downloads
- **WHEN** a `Both` membership with a download in flight is reconfigured to `UploadOnly`
- **THEN** the in-flight downloads are cancelled and no new download work is started on the next cycle
