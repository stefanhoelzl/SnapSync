## MODIFIED Requirements

### Requirement: Sole writer, synchronous in-cycle upload

The upload extension SHALL be the **sole** writer of the *projected* device manifest. It SHALL PUT the
manifest **synchronously within the upload cycle** — no background `URLSession` and no app involvement.
The extension MAY skip the PUT when the projected snapshot is unchanged since the last successful write.
A kill mid-PUT SHALL be tolerated: the partial write is lost and recomputed on the next cycle (benign,
because the manifest is write-only in v1 and converges).

Enrollment (capability `join-event`) writes a **register-only empty** manifest to the same resource, so
the skip-if-unchanged record is a belief about the server that a second writer can falsify. Any
successful register-only write SHALL therefore **invalidate** that record, so the next cycle re-PUTs the
projection rather than skipping it. A **failed** register-only write SHALL leave the record intact — the
server was not changed, so the belief is still true.

#### Scenario: Synchronous PUT with skip-if-unchanged

- **WHEN** the upload cycle has produced the projected snapshot
- **THEN** the extension PUTs the manifest synchronously in-cycle, or skips the PUT when the snapshot is
  unchanged since the last successful write

#### Scenario: Re-joining an event does not empty this device's manifest

- **WHEN** the device re-enrolls in an event it has already contributed to — after a leave, a durable
  state reset, or a reinstall — and the projected snapshot is unchanged from before
- **THEN** the enrollment's empty manifest is overwritten by the projection on the next cycle, so the
  event union still lists this device's uploaded photos

#### Scenario: A failed enrollment does not force a redundant PUT

- **WHEN** a register-only enrollment write is not confirmed by the edge
- **THEN** the skip-if-unchanged record is unchanged, and an unchanged projection still skips its PUT

#### Scenario: Kill mid-PUT is caught next cycle

- **WHEN** the extension is killed during a manifest PUT
- **THEN** the partial write is discarded and the manifest is recomputed and rewritten on the next cycle
