## MODIFIED Requirements

### Requirement: Voiding this device's durable sync state

The app SHALL provide an operation that voids this device's durable sync state, leaving it in the unjoined
resting state.

The operation SHALL clear **all three** of the following, because clearing fewer leaves the device silently
inert. (There is no discovery cursor to clear: every walk is a full enumeration, so an emptied ledger alone
restores a complete upload.)

- the **upload ledger**, in full — its key is the bare filename and therefore event-independent, so a
  `COMPLETED` row suppresses re-upload regardless of which backend received those bytes;
- the **persisted membership config**, **locally only** — the operation SHALL NOT notify any backend,
  because the event belongs to the backend the device is leaving behind and the newly baked backend never
  knew this device;
- every **prunable** download row — non-terminal, carrying no created-asset marker, and not protected
  (below).

The operation SHALL be **best-effort per step**: a failing step is logged and the remaining steps still run,
because a partial reset is strictly better than an aborted one — whatever was cleared cannot mislead.

#### Scenario: Durable sync state is voided
- **WHEN** the operation runs on a device holding upload ledger rows, a membership config, and pending
  download rows
- **THEN** the ledger is emptied, the membership config is cleared with no
  backend notification, and prunable download rows are dropped

#### Scenario: Enumeration is restored against a new backend
- **WHEN** a device whose library was already fully uploaded is reset and then pointed at a newly baked
  backend
- **THEN** the next upload cycle's walk finds no row for any in-scope photo, reads each one, and uploads
  it, rather than treating it as already complete

#### Scenario: Reset while holding nothing is a no-op
- **WHEN** the operation runs on a device with no ledger rows, no config, and no downloads
- **THEN** no side effect occurs

#### Scenario: A failing step does not abort the rest
- **WHEN** one of the four steps raises
- **THEN** the failure is logged and the remaining steps still run

### Requirement: A reset on a running process may be overtaken by work already in flight

The operation SHALL be usable on a running process, and every step SHALL be one that some runtime path
already performs on a live app — clearing the config is what a leave does, emptying the ledger is what a
re-join's clear-and-seed does against an empty listing, and the download prune already runs under the download controller's lock. It
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
