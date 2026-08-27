## MODIFIED Requirements

### Requirement: Resource-changed decision
When a platform submits `ResourceChanged(resource)`, the engine SHALL answer one `SyncDecision`
derived from the ledger entry for `resource.filename`, and SHALL **write nothing** — `handle` of a
`ResourceChanged` is a pure query (it reads the ledger and mints a request for `Work` answers, but
recording `REQUESTED` happens only on a later `UploadStarted`, see "Upload-started recording"):

- entry `COMPLETED`, `UPLOADED` or `REQUESTED` → `AlreadyUploaded` (no job). An uploaded resource is
  **immutable**, so a `COMPLETED` key is backed up for good and never re-uploaded; an `UPLOADED` key's
  bytes are already stored and what it still owes is the promotion pass, never a re-upload
  (capability `sync-ledger`); a `REQUESTED` key
  has a job in flight whose eventual `UploadStarted`/`UploadFailed`/`UploadCompleted` the engine
  relies on. In none of the three SHALL the engine re-issue work.
- entry `DISCOVERED`, entry `FAILED`, or entry absent → `Upload` carrying an `UploadJob` with
  `attempt = 0`

A key yields `Work` when nothing is in flight for it and its bytes are not on the backend — an absent
entry, a `DISCOVERED` one (the walk found it and nothing has been attempted), or a `FAILED` one. There
is no content-version comparison and no re-upload of an existing key. `Upload` and `Retry` SHALL
implement a common `Work` interface exposing the job, so platforms execute all work arms identically.
For `AlreadyUploaded` the ledger SHALL be left untouched (the same as every `ResourceChanged` answer,
which never writes).

`DISCOVERED` answering `Work` is what keeps the ledger usable as the cycle's record of its own backlog
(capability `sync-ledger`): the state exists so a cycle can remember a resource it saw but could not
enqueue, and an engine that treated that memory as "already handled" would suppress the very work it
was written to preserve.

The decision SHALL be an exhaustive `when` over `LedgerState` with no `else` branch, so a state added
without classifying it here fails to compile rather than falling into whichever arm a default named.

#### Scenario: Unknown resource uploads without writing the ledger
- **WHEN** `handle(ResourceChanged(resource))` is called and the ledger has no entry for its
  filename
- **THEN** `Upload` is returned with `attempt == 0` and the ledger still has no entry for the key
  (recording is deferred to `UploadStarted`)

#### Scenario: Completed key skips
- **WHEN** the ledger entry is `COMPLETED`
- **THEN** `AlreadyUploaded` is returned, no provider call is made, and the ledger entry is
  unchanged

#### Scenario: Uploaded key skips
- **WHEN** the ledger entry is `UPLOADED`
- **THEN** `AlreadyUploaded` is returned and nothing is written — its bytes are stored, and the
  promotion pass owes it an album placement and a notify, never another upload

#### Scenario: In-flight request skips on re-submission
- **WHEN** the ledger entry is `REQUESTED` and the same resource is re-submitted as `ResourceChanged`
- **THEN** `AlreadyUploaded` is returned, no provider call is made, and the ledger is unchanged — an
  in-flight job is not duplicated

#### Scenario: Discovered entry uploads
- **WHEN** the ledger entry is `DISCOVERED` and the resource is submitted as `ResourceChanged`
- **THEN** `Upload` is returned with `attempt == 0`, so a resource the cycle recorded but could not
  enqueue is re-derived as work rather than suppressed by its own record

#### Scenario: Failed entry re-uploads
- **WHEN** the ledger entry is `FAILED` and the same resource is submitted as `ResourceChanged`
- **THEN** `Upload` is returned with `attempt == 0` and nothing is written until `UploadStarted`

#### Scenario: A new ledger state must be classified here
- **WHEN** a value is added to `LedgerState` and this decision is not updated
- **THEN** the build fails, because the decision is an exhaustive `when` with no `else` branch

#### Scenario: Resource instance round-trips
- **WHEN** a `Work` decision is returned for a resource
- **THEN** `decision.job.request.resource` is the identical instance the platform supplied (no
  copying), so the platform can read its opaque `data` payload back at the execution edge
