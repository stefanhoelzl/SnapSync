## MODIFIED Requirements

### Requirement: Independent download-progress projection

The status surface SHALL expose download progress as an **independent** indicator, separate from the
own-device upload status: a count of foreign complete assets imported (`downloaded`) out of the
foreign complete assets currently in the union (`total`), asset-counted to match the upload progress
convention. `DownloadProgress` SHALL additionally carry an **`inFlight`** count — the number of
foreign assets with at least one resource whose download has been **sent to the OS but not yet
staged** (the download analogue of `SyncProgress.pending`). `inFlight` is **display-only**: it drives
only the live-activity signal of the download direction arrow (per `sync-status-screen`) and SHALL NOT
alter the `downloaded`/`total` completeness notion. This projection SHALL NOT alter the own-device
upload "Completed" notion — uploads are "done" when the device's own qualifying assets are all present
in storage, regardless of download progress. `total` MAY grow as other contributors add assets, and
the indicator SHALL reflect that honestly. `inFlight` SHALL be sourced from the `download-store`
`inFlightCount()` read and refreshed on foreground entry alongside `downloaded`/`total`.

#### Scenario: Download line is independent of upload completion

- **WHEN** the device's own uploads are complete but foreign downloads are still in progress
- **THEN** the download projection reports its own `downloaded`/`total`/`inFlight`; upload "Completed"
  and download progress do not gate each other

#### Scenario: Download denominator is foreign complete assets

- **WHEN** the union reports `total` foreign complete assets and `downloaded` of them are imported
- **THEN** the projection reports `downloaded` of `total`, asset-counted

#### Scenario: In-flight reflects downloads sent to the OS

- **WHEN** `k` foreign assets have a resource download enqueued to the OS and not yet staged
- **THEN** `DownloadProgress.inFlight == k`; **WHEN** all such downloads have staged or none are
  enqueued, `inFlight == 0`

#### Scenario: Denominator grows with new contributions

- **WHEN** other contributors add complete assets to the event
- **THEN** `total` increases accordingly on the next union read, with no false "all downloaded" state
