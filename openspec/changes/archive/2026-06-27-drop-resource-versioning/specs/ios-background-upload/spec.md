## MODIFIED Requirements

### Requirement: Engine-gated real upload-job creation

For each discovered `Resource` the extension SHALL drive the shared `SyncEngine` with
`ResourceChanged` and act on the decision. On a `Work` decision (`Upload`) it SHALL build the
destination request from the real `EdgeUploadRequestProvider` (a plain `PUT` to the locally-built
edge URL `<host>/event/<eventId>/file/<filename>`, no signing), create a system upload job via
`creationRequestForJob(destination:resource:)`, and **then** report `UploadStarted(job)` to the
engine so the ledger records `REQUESTED` (write-after-act — `REQUESTED` is recorded only after the
job exists, never before). On `AlreadyUploaded` it SHALL create no job and write nothing. Completion
and failure outcomes are reduced into the ledger by the drain (see "Completion and retry
adjudication"), so `COMPLETED` and `FAILED` are recorded.

#### Scenario: New resource emits a real edge destination, then records REQUESTED
- **WHEN** the engine returns a `Work` decision for a discovered resource
- **THEN** a real edge `PUT` destination is built locally, a system upload job is created with it,
  and only after the create succeeds does the extension report `UploadStarted`, which records
  `REQUESTED` for the key

#### Scenario: Already-recorded resource is skipped
- **WHEN** the engine returns `AlreadyUploaded` for a discovered resource (its key is `REQUESTED` or
  `COMPLETED`)
- **THEN** no system job is created and the ledger is not written

#### Scenario: Create failure leaves no REQUESTED
- **WHEN** `creationRequestForJob` fails (e.g. `limitExceeded`) before `UploadStarted` is reported
- **THEN** the ledger has no `REQUESTED` for that key, so a later re-derivation re-issues the create

### Requirement: Resource identity and fan-out

For each discovered asset the extension SHALL fan the asset out to its `PHAssetResource`s, wrapping each as an engine `Resource` with `filename = "<localId>-<kind>.<ext>"` (the PHAsset's `localIdentifier` with `/` replaced by `_`), the `PHAssetResource` as opaque `data`, and **empty metadata** (the bunny native Storage API has no custom-metadata channel). The `Resource` carries no content version (an uploaded resource is immutable). v1 is a **single-device, one-way backup**, so the per-device `localIdentifier` is the resource identity: it requires **no iCloud account** and is always available. The `/`→`_` substitution keeps the filename a single slash-free segment, so the edge endpoint — which percent-decodes the `file/<…>` path param and **rejects any decoded `/`** — accepts it and composes a flat storage key. The extension SHALL NOT resolve `PHCloudIdentifier` and SHALL NOT skip any asset for an unresolved cloud id.

#### Scenario: Each resource becomes a distinct key
- **WHEN** an asset with multiple `PHAssetResource`s is discovered
- **THEN** each resource is wrapped as a `Resource` with its own `<localId>-<kind>.<ext>` filename and no content version
