## MODIFIED Requirements

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

Turning a direction off SHALL NOT stop the upload producer, and therefore SHALL NOT deregister an
OS-driven upload extension: stopping is what would cancel the in-flight work this requirement preserves.
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
