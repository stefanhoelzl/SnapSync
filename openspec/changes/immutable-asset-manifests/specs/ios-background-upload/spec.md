## MODIFIED Requirements

### Requirement: Resource identity and fan-out

For each discovered asset the extension SHALL fan the asset out to its **original** `PHAssetResource`s
only, mapping each to a generic role and wrapping it as an engine `Resource` with
`filename = "<assetId>-<role>.<ext>"`, where `assetId` is the PHAsset's `localIdentifier` with `/`
replaced by `_`, and `role` is `primary` for the original `photo`/`video`/`audio` resource and
`motion` for the original `pairedVideo` (Live Photo) resource. The extension SHALL NOT upload edit
artifacts — `fullSizePhoto`/`fullSizeVideo`/`fullSizePairedVideo` renders, `adjustmentData`,
`adjustmentBasePhoto`/`adjustmentBasePairedVideo`/`adjustmentBaseVideo`, the RAW `alternatePhoto`, or
proxies — so an asset's resource set is fixed at capture and never grows. Each wrapped `Resource`
carries the `PHAssetResource` as opaque `data` and **empty metadata** (the bunny native Storage API
has no custom-metadata channel) and no content version (an uploaded resource is immutable). v1 is a
**single-device, one-way backup**, so the per-device `localIdentifier` is the asset identity: it
requires **no iCloud account** and is always available. The `/`→`_` substitution keeps the filename a
single slash-free segment, so the edge endpoint — which percent-decodes the `file/<…>` path param and
**rejects any decoded `/`** — accepts it and composes a flat storage key. The extension SHALL NOT
resolve `PHCloudIdentifier` and SHALL NOT skip any asset for an unresolved cloud id.

#### Scenario: Each original resource becomes a distinct role key

- **WHEN** a Live Photo with localIdentifier `L` is discovered (original still plus original paired video)
- **THEN** two `Resource`s are wrapped with filenames `L-primary.<ext>` and `L-motion.<ext>`, yielding
  distinct ledger keys

#### Scenario: Edit artifacts are excluded

- **WHEN** a discovered asset has been edited (it exposes a full-size render and adjustment data alongside its original)
- **THEN** only its original resource(s) are wrapped; the render and adjustment data are not wrapped and never uploaded

#### Scenario: No iCloud account required

- **WHEN** the device has no iCloud account (no asset has a resolvable cloud identifier)
- **THEN** assets are still discovered and keyed by their `localIdentifier`, and uploads proceed — none are skipped for a missing cloud id

## ADDED Requirements

### Requirement: Per-asset manifest generation and side-channel upload

Each asset's manifest SHALL be delivered over a **vanilla background `URLSession`** (a file-backed
`uploadTask`), not the OS `PHAssetResource` upload-job API (which can carry only a `PHAssetResource`, not
synthetic bytes). Following Apple's extension-initiated background-upload model — the **extension starts**
the transfer and the system relaunches the **containing app** to handle its completion — generation and
the initial enqueue SHALL be the extension's, while result handling (retry, done-marking) SHALL be the
app's. The manifest SHALL NOT be driven through the `SyncEngine`, the `createJob` path, or the ledger.

The **extension** SHALL, the first time it discovers an asset it backs up (no manifest file present),
synthesize the manifest (per `asset-manifest`) from the `PHAsset` and its originals — `creationDate`,
and per resource `role`, `contentType` (from the UTI), `filename`, and `originalFilename` — write it to
the shared App Group container in a **PENDING** state, set the upload task's identity to the `assetId`
(`taskDescription`), and enqueue exactly one background `uploadTask` to
`<host>/event/<eventId>/file/<assetId>.manifest.json` with `Content-Type: application/json`. On a later
discovery of the same asset the extension SHALL NOT regenerate or re-upload a manifest already marked
**DONE**; for a **PENDING** manifest with no in-flight task on its background session it SHALL
re-enqueue exactly one upload (resurrecting a stalled one), using only locally observable task state
(no storage probe).

The **app** SHALL own the shared background session's delegate via
`handleEventsForBackgroundURLSession`: on a task's **successful** completion it SHALL mark that asset's
manifest **DONE** (so the extension stops touching it); on **failure** it SHALL re-enqueue the upload
with backoff. The PENDING→DONE file state is the cross-process dedup marker — written PENDING by the
extension, flipped to DONE by the app on success.

#### Scenario: Extension generates and enqueues on first discovery

- **WHEN** the extension first discovers asset `A` with no manifest file present
- **THEN** it writes `A`'s manifest PENDING to the App Group and enqueues exactly one background
  `URLSession` `PUT` to `…/event/<eventId>/file/A.manifest.json` (`Content-Type: application/json`,
  `taskDescription = A`)

#### Scenario: A DONE manifest is never re-uploaded

- **WHEN** the extension re-discovers an asset whose manifest is marked DONE
- **THEN** it neither regenerates nor re-enqueues that manifest

#### Scenario: A stalled PENDING manifest is resurrected

- **WHEN** the extension re-discovers an asset whose manifest is PENDING and whose background session has no in-flight task for it
- **THEN** it re-enqueues exactly one upload (and does not duplicate an already-pending task)

#### Scenario: The app retries on failure and marks DONE on success

- **WHEN** the app receives a manifest task completion via `handleEventsForBackgroundURLSession`
- **THEN** on success it marks that manifest DONE, and on failure it re-enqueues the upload with backoff

#### Scenario: The manifest never enters the engine or ledger

- **WHEN** a manifest is generated, uploaded, retried, or marked DONE
- **THEN** no `Resource` is minted for it, `createJob` is not invoked, and no ledger row is recorded
