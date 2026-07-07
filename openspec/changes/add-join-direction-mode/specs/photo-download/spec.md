## ADDED Requirements

### Requirement: Download is gated on the membership's participation direction

The download reconcile SHALL be a **no-op** for any membership whose persisted participation direction
excludes download (`UploadOnly`) — at **every** trigger (join/(re)provision, foreground entry, and
silent push). The gate SHALL live at the **single choke point** through which all triggers funnel
(`DownloadController.reconcile`), reading the persisted `EventConfig.direction`, so the skip decision
sits in a **tested capability** rather than being duplicated across the untested app shell's call sites.
When the direction is `Both` or `DownloadOnly`, reconcile SHALL run exactly as before. This gate is
**orthogonal** to the existing active-event guard in the silent-push receive seam (capability
`push-registration`): the active-event guard answers "is this push for my current event," the direction
gate answers "should this device ever download for its current event." A push for the active event on an
`UploadOnly` membership SHALL therefore be received (active-event guard passes) yet perform **no**
reconcile (direction gate blocks), leaving no foreign photos downloaded or imported.

#### Scenario: Upload-only skips reconcile on foreground
- **WHEN** the app foregrounds while joined with direction `UploadOnly`
- **THEN** no union read, download enqueue, or import occurs (reconcile is a no-op)

#### Scenario: Upload-only skips reconcile on a push for the active event
- **WHEN** a silent push for the device's active event arrives while joined with direction `UploadOnly`
- **THEN** the receive seam's active-event guard passes but reconcile performs no union read or download enqueue

#### Scenario: Upload-only skips reconcile on join/provision
- **WHEN** a membership is provisioned (joined, re-provisioned, or switched) with direction `UploadOnly`
- **THEN** the provision path triggers no download reconcile

#### Scenario: Both and download-only run reconcile unchanged
- **WHEN** any download trigger fires while joined with direction `Both` or `DownloadOnly`
- **THEN** reconcile runs exactly as before — selecting foreign complete assets, enqueuing downloads, and importing staged assets
