# full-stack-harness — delta

## MODIFIED Requirements

### Requirement: World-inspector controls drive the real world through a single controller

The inspector SHALL drive `:test:world`'s public control surface through a **single** controller,
with one named method per control and **no** inline world mutation in composables (mirroring the forge
harness's `PanelController`). It SHALL cover: **Enrollment** — a 4-state permission segment
(`NOT_DETERMINED`, `DENIED`, `LIMITED`, `GRANTED` — the partial grant is a first-class state,
capability `limited-photo-access`), an armed
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
