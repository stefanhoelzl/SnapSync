# device-state-reset Specification

## Purpose

Void this device's durable sync state, so a build pointed at a **different backend** starts from nothing
rather than from state describing bytes that backend does not hold.

Crossing backends inverts every premise the retained state was right to keep. The upload ledger's key is the
bare filename and is therefore event-independent, so a `COMPLETED` row stays *true* across a leave — and
becomes a lie the moment the bytes live on a backend the device no longer talks to. The device then uploads
**nothing**, with no error, no failed request, and no log line, which is indistinguishable from a broken rig.
It bites in both directions: going to a local backend, and coming back.

This is not "leave harder". `leave-event` deliberately keeps the ledger and is right to, because a leave does
not remove this device's bytes from its storage partition. The reset exists for the one case where that
reasoning stops holding.

Decision record: `changes/archive/2026-08-24-retire-launch-env-triggers`.

## Requirements

### Requirement: Voiding this device's durable sync state

The app SHALL provide an operation that voids this device's durable sync state, leaving it in the unjoined
resting state.

The operation SHALL clear **all four** of the following, because clearing fewer leaves the device silently
inert:

- the **upload ledger**, in full — its key is the bare filename and therefore event-independent, so a
  `COMPLETED` row suppresses re-upload regardless of which backend received those bytes;
- the **discovery cursor** (the persisted photo-library change token) — with the cursor retained, the next
  cycle observes no changes and enumerates nothing, so a ledger clear alone still uploads nothing; clearing
  it restores full re-enumeration;
- the **persisted membership config**, **locally only** — the operation SHALL NOT notify any backend,
  because the event belongs to the backend the device is leaving behind and the newly baked backend never
  knew this device;
- every **prunable** download row — non-terminal, carrying no created-asset marker, and not protected
  (below).

The operation SHALL be **best-effort per step**: a failing step is logged and the remaining steps still run,
because a partial reset is strictly better than an aborted one — whatever was cleared cannot mislead.

#### Scenario: Durable sync state is voided
- **WHEN** the operation runs on a device holding upload ledger rows, a discovery cursor, a membership
  config, and pending download rows
- **THEN** the ledger is emptied, the discovery cursor is cleared, the membership config is cleared with no
  backend notification, and prunable download rows are dropped

#### Scenario: Enumeration is restored against a new backend
- **WHEN** a device whose library was already fully uploaded is reset and then pointed at a newly baked
  backend
- **THEN** the next upload cycle re-enumerates the library from scratch and uploads its in-scope photos,
  rather than treating them as already complete

#### Scenario: Reset while holding nothing is a no-op
- **WHEN** the operation runs on a device with no ledger rows, no cursor, no config, and no downloads
- **THEN** no side effect occurs

#### Scenario: A failing step does not abort the rest
- **WHEN** one of the four steps raises
- **THEN** the failure is logged and the remaining steps still run

### Requirement: Rows carrying an import handle are retained

The operation SHALL **retain** download rows in the terminal imported state. Their recorded local asset
identifier is the suppression handle the upload path reads to avoid re-uploading a downloaded asset;
discarding it would make the device re-upload photos it imported.

It SHALL retain two further shapes, for the same reason and neither of them terminal (capability
`download-store`):

- a **non-terminal row carrying a created-asset marker** — an import that committed but was never confirmed.
  The marker, not the state, is the record that an asset was created;
- a row whose **import is in flight**, which carries no marker *yet* because its change block has not run.
  Dropping it makes that block's marker write land on no row, so the asset it creates has no suppression
  handle at all and is uploaded back into the event.

The invariant is that **handle-carrying** rows are permanent, not that **terminal** rows are.

Because the second shape is knowable only to the download feature, the operation SHALL perform its download
half **through that feature's own lock** rather than by reading a snapshot of what is in flight: the
operation suspends between its steps, so a snapshot leaves a window in which a row is claimed after the read
and deleted by the prune.

The operation SHALL report the number of imported rows it retained, read **before** the prune, so that
"imported rows were kept" is verifiable rather than assumed.

#### Scenario: Imported downloads survive
- **WHEN** the operation runs on a device that has imported foreign photos
- **THEN** those imported rows and their recorded local asset identifiers are retained, so the upload path
  still suppresses them and no downloaded photo is re-uploaded

#### Scenario: An in-flight import survives
- **WHEN** the operation runs while an import for a foreign asset is in flight, so its row is non-terminal
  and carries no marker yet
- **THEN** that row is retained, and the marker its change block writes afterwards lands on a row that still
  exists

#### Scenario: The prune runs under the download feature's lock
- **WHEN** a download ref is claimed concurrently with the operation
- **THEN** the claim and the prune are serialized by the download controller's own lock, so no row is
  claimed after the read and then deleted

### Requirement: The attestation credential is not cleared

The operation SHALL NOT clear the device's attestation credential. A token minted by another backend is
rejected there with a `401`, and `DeviceAttestation.rejected()` already drops it and re-attests (capability
`device-attestation`) — so crossing backends heals it with no operator action, and clearing it here would
only cost an extra round trip.

#### Scenario: Crossing backends heals attestation without operator action
- **WHEN** a reset device makes its first attested request against the newly baked backend using a token the
  previous backend minted
- **THEN** the request is rejected with a `401`, the token is dropped and re-minted, and the operation
  proceeds

### Requirement: A reset on a running process may be overtaken by work already in flight

The operation SHALL be usable on a running process, and every step SHALL be one that some runtime path
already performs on a live app — clearing the config is what a leave does, invalidating the discovery cursor
is what a reconfigure does, and the download prune already runs under the download controller's lock. It
SHALL NOT require a relaunch to leave the process coherent.

An upload cycle **already in flight** when the operation runs MAY complete and write rows into the ledger it
just emptied. This is stated rather than prevented: the cycle gate re-reads the membership config each run,
so the *next* cycle skips, the rows are visible in the reported ledger counts, and a second reset clears
them.

#### Scenario: A reset needs no relaunch
- **WHEN** the operation runs on a foregrounded app
- **THEN** the membership clears, the status reduces to the unjoined resting state, and no relaunch is
  required for the process to be coherent

#### Scenario: An in-flight cycle may outlive the reset
- **WHEN** an upload cycle is mid-run as the operation clears the ledger
- **THEN** that cycle may write rows after the clear, the next cycle skips on the cleared membership, and
  the surviving rows are visible in the reported ledger counts
