## MODIFIED Requirements

### Requirement: Per-asset manifest generation and side-channel upload

The extension SHALL maintain a durable, device-global **accumulator** in the shared App-Group store
of per-asset manifest entries (per `device-manifest`: per asset its `assetId`, `creationDate`, and
per resource its `role`, `contentType`, `key` (the storage object name), and `filename` (the human
capture name)). It SHALL write or update an asset's accumulator entry on **every discovery** of that
asset — **even when the engine answers `AlreadyUploaded`** — so the accumulator is a rebuildable cache
reflecting every discovered-not-deleted asset, not a source of truth. The accumulator MUST NOT be
driven through the `SyncEngine`, the `createJob` path, or the ledger.

On each cycle the extension SHALL **project** the accumulator to the current event's `device.json`
(filtering to assets whose capture date meets the event's cutoff; under the current whole-library
scope the projection is the identity) and **PUT it synchronously, in-cycle**, to
`<host>/events/<eventId>/devices/<deviceId>` with `Content-Type: application/json` — **not** over a
background `URLSession`, and **not** via the engine or ledger. The extension SHALL be the **sole
writer** of `device.json`; each write is a complete, self-contained full-state snapshot (no
read-modify-write). It MAY skip the PUT when the projection is **byte-identical** to the last written
`device.json`. Because the resource field names (`key`, `filename`) are part of that snapshot content,
a build that changes them produces a projection that differs from any previously-stored snapshot, so
the first cycle on the new build re-PUTs `device.json` with the new names — no special one-shot flag
is needed. A process kill mid-PUT is benign — `device.json` is write-only in v1 and the next cycle
re-projects and re-PUTs, so the loss is caught and converges. The previous `PENDING`/`DONE` manifest
markers and the app's `handleEventsForBackgroundURLSession` manifest wiring are **removed**; the app
reads no manifest state.

#### Scenario: Accumulator entry is written on every discovery, including AlreadyUploaded

- **WHEN** the extension discovers asset `A`, and the engine answers `AlreadyUploaded` for every one
  of `A`'s resources (its keys are already `REQUESTED`/`COMPLETED`)
- **THEN** the extension still writes/updates `A`'s entry in the device-global accumulator (no job is
  created and the ledger is not written)

#### Scenario: Each cycle projects the accumulator and PUTs device.json synchronously

- **WHEN** a `process()` cycle finishes its discovery and the projection differs from the last write
- **THEN** the extension projects the accumulator to the current event's `device.json` and PUTs it
  synchronously, in-cycle, to `<host>/events/<eventId>/devices/<deviceId>` (`Content-Type:
  application/json`), with no background `URLSession` task and no engine/ledger involvement, each
  resource carrying `key` (the storage object name) and `filename` (the human capture name)

#### Scenario: Unchanged projection skips the PUT

- **WHEN** a cycle's projection is byte-identical to the last written `device.json`
- **THEN** the extension MAY skip the PUT for that cycle

#### Scenario: Field-name change re-PUTs the manifest once

- **WHEN** the first cycle runs on a build that renamed the resource fields to `key`/`filename` and a
  previously-stored `device.json` uses the old field names
- **THEN** the projection is no longer byte-identical to the stored snapshot, so the extension re-PUTs
  `device.json` with the new field names (clean cutover, no separate backfill)

#### Scenario: A kill mid-PUT is caught next cycle

- **WHEN** the extension process is killed while the synchronous `device.json` PUT is in flight
- **THEN** the partial write is discarded and the next cycle re-projects the accumulator and re-PUTs
  `device.json`, converging without any cross-process marker
