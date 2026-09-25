## MODIFIED Requirements

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

**The order is load-bearing.** The load runs **before** the save, so no cycle — the app process's
opportunistic tail running concurrently included (capability `ios-app-shell`) — can ever see the **new**
membership over the previous membership's ledger. A crash
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
