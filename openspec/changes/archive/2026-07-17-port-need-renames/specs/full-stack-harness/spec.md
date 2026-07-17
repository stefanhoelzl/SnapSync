# full-stack-harness — delta for port-need-renames

## MODIFIED Requirements

### Requirement: World-inspector controls drive the real world through a single controller

The inspector SHALL drive `:test:world`'s public control surface through a **single** controller,
with one named method per control and **no** inline world mutation in composables (mirroring the forge
harness's `PanelController`). It SHALL cover: **Enrollment** — a 3-state permission segment, an armed
next-request outcome (the fake `PhotoAccessRequester` resolves `request()` to the armed grant/deny), a
joined-event-id readout, and Re-provision / Create event / Leave; **Gallery ▏ Backend** side by side —
editable own-asset rows (add/remove; imported rows badged upload-suppressed via the download store's
suppressed-id set) and stored objects grouped by device (own plus "+ Inject device" for a foreign
contributor's complete assets); **Upload jobs ▏ Downloads** side by side — an upload queue of
pending/retry rows with per-job Complete and Fail carrying a chosen engine `UploadError` (Network /
Http / Cancelled / Unknown) and a job-limit indicator, and a list of pending foreign download
resources each with a Stage action; and **Failure levers** — a backend-offline toggle, the job-limit,
and an import-failure arm (the per-job `UploadError` living on the queue). There SHALL be **no**
`DownloadError` picker — a non-staged download simply remains PENDING (the only download-side failure
lever is the armed import failure).

#### Scenario: Every control maps to a world call through the controller

- **WHEN** the inspector composables are inspected
- **THEN** each control invokes a named controller method that calls the world's public surface
  (`runUploadCycle`, `platform.expireToken`, `permission.set`, `provision`, `addOwnAsset`/`removeAsset`,
  `addForeignDevice`, `platform.completeJob`/`failJob`, `jobLimit`, `stageAllDownloads`,
  `backendOffline`, `failNextImport`), and no composable mutates world state inline

#### Scenario: Fail drives the real retry chain

- **WHEN** the operator fails a created upload job with a chosen `UploadError` and invokes the extension
- **THEN** the real engine answers retry and the job's attempt increments — visible in the upload queue

#### Scenario: A foreign asset flows download → import → suppression

- **WHEN** the operator injects a foreign device, invokes the extension (union → pending download),
  stages the download, and invokes again
- **THEN** the foreign asset is imported into the gallery, badged upload-suppressed, and the own-device
  cycle creates no upload job for it

#### Scenario: No download-error picker

- **WHEN** the Downloads column is inspected
- **THEN** it offers a Stage action only — a non-staged download stays PENDING and there is no
  `DownloadError` control

### Requirement: The inspector's Create event control supplies a start date

The world inspector's **Create event** control SHALL supply a `startsAt` alongside the event name, the
real `POST /events` now requiring one (capability `event-creation`). It SHALL let the operator choose
between a start in the **past** (the event has begun — the ordinary case) and one in the **future** (the
event has not begun), so both sides of the floor can be driven through the **real** stack rather than
forged.

Driving the not-started case here — rather than only in the forge harness — is what proves the *theorem*
the design rests on: that a future start uploads nothing not because a gate refuses, but because the
clamped cutoff admits no photo. The forge harness can only show the status line; only the full-stack
world can show the empty object store behind it.

#### Scenario: Creating an event supplies a start date through the real client
- **WHEN** the operator activates Create event
- **THEN** the real `HttpEventCreation` posts a canonical `startsAt` with the name, and the
  mini-edge registers a marker carrying it

#### Scenario: A future-start event uploads nothing through the real stack
- **WHEN** the operator creates an event starting in the future, joins it, adds own assets to the
  gallery, and invokes the extension
- **THEN** no upload job is created and no object appears in the backend column — the left pane showing
  the not-started status line beside an empty store

#### Scenario: Uploads flow once the start is in the past
- **WHEN** the operator creates an event whose start is in the past, joins it, adds own assets, and
  invokes the extension
- **THEN** upload jobs are created and objects land in the backend column exactly as before this change
