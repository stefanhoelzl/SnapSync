## MODIFIED Requirements

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

The gate's read SHALL be **posture-explicit**: *no membership* is a distinct answer from *a membership whose
direction excludes download*, and **neither** enables the arm. The read SHALL NOT resolve an absent
membership to "enabled" via a permissive fallback, and the gate SHALL carry **no default value** that would
let a caller omit the posture entirely. A three-valued read collapsed into a permissive boolean is what
allowed an upload producer to be enabled for an event that did not exist (capability `upload-lifecycle`); the
same collapse here would run a reconcile with no membership to reconcile against.

Because the download total is populated **only** by this reconcile (`store.plan` is reached only past this
gate), an `UploadOnly` membership's download total is `0`, and its download arrow is hidden by the ordinary
completeness rule with no masking in the status projection (capability `sync-status-screen`).

#### Scenario: Upload-only skips reconcile on foreground
- **WHEN** the app foregrounds while joined with direction `UploadOnly`
- **THEN** no union read, download enqueue, or import occurs (reconcile is a no-op)

#### Scenario: Upload-only skips reconcile on a push for the active event
- **WHEN** a silent push arrives for the active event on an `UploadOnly` membership
- **THEN** the push is received (the active-event guard passes) and reconcile is a no-op — no foreign photo
  is downloaded or imported

#### Scenario: Upload-only skips reconcile on join/provision
- **WHEN** a membership is provisioned (joined, re-provisioned, or switched) with direction `UploadOnly`
- **THEN** the provision path triggers no download reconcile

#### Scenario: Both and download-only run reconcile unchanged
- **WHEN** any download trigger fires while joined with direction `Both` or `DownloadOnly`
- **THEN** reconcile runs exactly as before — selecting foreign complete assets, enqueuing downloads, and importing staged assets

#### Scenario: An absent membership enables nothing
- **WHEN** the direction gate is read with no membership configured
- **THEN** the answer is "no arm" — the reconcile does not run, rather than defaulting to enabled

#### Scenario: Upload-only's download total is zero without a mask
- **WHEN** the membership is `UploadOnly` and the status projection reads the download total
- **THEN** the total is `0` because nothing was ever planned, so the download arrow is hidden by the
  completeness rule rather than by a direction mask
