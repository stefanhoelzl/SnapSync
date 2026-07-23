## ADDED Requirements

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
- every **non-terminal** download row.

The trigger SHALL **retain** download rows in the terminal imported state. Their recorded local asset
identifier is the suppression handle the upload path reads to avoid re-uploading a downloaded asset;
discarding it would make the device re-upload photos it imported.

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
  with no backend notification, and non-terminal download rows are dropped — leaving the device in the
  unjoined resting state

#### Scenario: Imported downloads survive the reset
- **WHEN** the app is cold-launched with `SNAPSYNC_RESET_STATE` present on a device that has imported
  foreign photos
- **THEN** those imported rows and their recorded local asset identifiers are retained, so the upload
  path still suppresses them and no downloaded photo is re-uploaded

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

## MODIFIED Requirements

### Requirement: Ordered application of membership-mutating launch triggers

The app SHALL apply the membership-mutating launch triggers (`SNAPSYNC_RESET_STATE`, `SNAPSYNC_LEAVE`,
`SNAPSYNC_CREATE_EVENT`, `SNAPSYNC_EVENT_LINK`) in the **fixed order**
`reset → leave → create → event-link`, **sequentially** — each awaited to completion before the next —
so combinations set in the same launch are well-defined and each later step observes the state the
earlier ones produced (e.g. a `SNAPSYNC_LEAVE` clears the config **before** a `SNAPSYNC_CREATE_EVENT`
mints and the create/join steps read the post-leave membership). Each trigger remains independent: a
launch MAY set any subset, and an absent trigger contributes nothing to the order.

`SNAPSYNC_RESET_STATE` SHALL run **first** so that one launch can void a foreign backend's state and
immediately mint or join against the newly baked backend. Because the reset leaves the device
unjoined, a `SNAPSYNC_LEAVE` set in the same launch is thereby a no-op rather than a backend
notification aimed at the wrong backend.

A **forge** launch (`SNAPSYNC_FORGE_STATE` naming a recognized state) SHALL ignore **all four**
membership-mutating triggers — it resets nothing, mints nothing, leaves nothing, and provisions
nothing. This inertness SHALL be **structural** (the shell's single mode switch selects a forge
delegate holding no reference to the live stack), consistent with the forge-state trigger's existing
precedence over `SNAPSYNC_EVENT_LINK`.

#### Scenario: Leave and create apply in order
- **WHEN** the app is cold-launched with both `SNAPSYNC_LEAVE` present and `SNAPSYNC_CREATE_EVENT` set,
  while joined to an event
- **THEN** the app first leaves the current membership, then mints the new event — the create step
  observes the cleared config (so an `autoJoin` create joins fresh rather than treating it as a switch)

#### Scenario: Reset precedes create in one launch
- **WHEN** the app is cold-launched with both `SNAPSYNC_RESET_STATE` present and
  `SNAPSYNC_CREATE_EVENT` set, on a device carrying state from a different backend
- **THEN** the durable sync state is voided first and the create step then mints and joins against the
  newly baked backend from a clean slate

#### Scenario: Forge wins over create and leave
- **WHEN** the app is cold-launched with a recognized `SNAPSYNC_FORGE_STATE` and any of
  `SNAPSYNC_RESET_STATE` / `SNAPSYNC_CREATE_EVENT` / `SNAPSYNC_LEAVE` in its environment
- **THEN** the forged frame renders, the membership triggers are ignored, nothing is reset, nothing is
  minted, nothing is left, and nothing is provisioned — the forge delegate has no route to the live stack
