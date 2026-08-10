## MODIFIED Requirements

### Requirement: Developer launch-environment RESET-STATE trigger

The iOS app SHALL read a `SNAPSYNC_RESET_STATE` variable from the process environment **once per
process launch**. Its **presence** (any value) SHALL void this device's durable sync state, so that a
build pointed at a **different backend** starts from nothing rather than from state describing bytes
that backend does not hold.

The trigger SHALL clear **all four** of the following, because clearing fewer leaves the device
silently inert:

- the **upload ledger**, in full — its key is the bare filename and therefore event-independent, so a
  `COMPLETED` row suppresses re-upload regardless of which backend received those bytes;
- the **discovery cursor** (the persisted photo-library change token) — with the cursor retained, the
  next cycle observes no changes and enumerates nothing, so a ledger clear alone still uploads
  nothing; clearing it restores full re-enumeration;
- the **persisted membership config**, **locally only** — the trigger SHALL NOT notify any backend,
  because the event belongs to the backend the device is leaving behind and the newly baked backend
  never knew this device;
- every **prunable** download row — non-terminal, carrying no created-asset marker, and not protected
  (below).

The trigger SHALL **retain** download rows in the terminal imported state. Their recorded local asset
identifier is the suppression handle the upload path reads to avoid re-uploading a downloaded asset;
discarding it would make the device re-upload photos it imported.

It SHALL retain two further shapes, for the same reason and neither of them terminal
(capability `download-store`):

- a **non-terminal row carrying a created-asset marker** — an import that committed but was never
  confirmed. The marker, not the state, is the record that an asset was created;
- a row whose **import is in flight**, which carries no marker *yet* because its change block has not
  run. Dropping it makes that block's marker write land on no row, so the asset it creates has no
  suppression handle at all and is uploaded back into the event.

Because the second shape is knowable only to the download feature, the reset SHALL perform its download
half **through that feature's own lock** rather than by reading a snapshot of what is in flight: a reset
suspends between its steps, so a snapshot leaves a window in which a row is claimed after the read and
deleted by the prune.

The trigger SHALL NOT clear the device's attestation credential: a rejected token is already dropped
and re-minted on a `401` (capability `device-attestation`), so crossing backends heals it without
operator action.

The trigger SHALL be applied **at most once per process** and SHALL rely on the
developer-launch-only injectability of a process-environment variable, so it is inert in production
**with no compile-time guard**.

Decision record: `changes/archive/2026-07-23-add-local-backend-rig`.

#### Scenario: Present variable voids durable sync state
- **WHEN** the app is cold-launched with `SNAPSYNC_RESET_STATE` present on a device holding upload
  ledger rows, a discovery cursor, a membership config, and pending download rows
- **THEN** the ledger is emptied, the discovery cursor is cleared, the membership config is cleared
  with no backend notification, and prunable download rows are dropped — leaving the device in the
  unjoined resting state

#### Scenario: Imported downloads survive the reset
- **WHEN** the app is cold-launched with `SNAPSYNC_RESET_STATE` present on a device that has imported
  foreign photos
- **THEN** those imported rows and their recorded local asset identifiers are retained, so the upload
  path still suppresses them and no downloaded photo is re-uploaded

#### Scenario: An in-flight import survives the reset
- **WHEN** a reset runs while an import for a foreign asset is in flight, so its row is non-terminal
  and carries no marker yet
- **THEN** that row is retained, and the marker its change block writes afterwards lands on a row that
  still exists

#### Scenario: Reset restores enumeration against a new backend
- **WHEN** a device whose library was already fully uploaded is relaunched with
  `SNAPSYNC_RESET_STATE` present and a newly baked backend host
- **THEN** the next upload cycle re-enumerates the library from scratch and uploads its in-scope
  photos to the new backend, rather than treating them as already complete

#### Scenario: Reset while holding nothing is a no-op
- **WHEN** the app is cold-launched with `SNAPSYNC_RESET_STATE` present on a device with no ledger
  rows, no cursor, no config, and no downloads
- **THEN** no side effect occurs

#### Scenario: Production launch is inert
- **WHEN** the app is launched from SpringBoard or via TestFlight with no `SNAPSYNC_RESET_STATE` in
  its environment
- **THEN** no reset side effect occurs and behavior is identical to the app without this feature, with
  no compile-time flag distinguishing the build
