## MODIFIED Requirements

### Requirement: Resource-changed decision
When a platform submits `ResourceChanged(resource)`, the engine SHALL answer one `SyncDecision`
derived from the ledger entry for `resource.filename`, and SHALL **write nothing** — `handle` of a
`ResourceChanged` is a pure query (it reads the ledger and mints a request for `Work` answers, but
recording `REQUESTED` happens only on a later `UploadStarted`, see "Upload-started recording"):

- entry `COMPLETED` or `REQUESTED` → `AlreadyUploaded` (no job). An uploaded resource is
  **immutable**, so a `COMPLETED` key is backed up for good and never re-uploaded; a `REQUESTED` key
  has a job in flight whose outcome is either reported to the engine (`UploadStarted` for a re-created job,
  `UploadFailed`) or recorded by the platform through the ledger's guarded terminal write (capability
  `sync-ledger`). In neither SHALL the engine re-issue work.
- entry `DISCOVERED`, or entry absent → `Upload` carrying a freshly minted `UploadRequest`

A key yields `Work` when nothing is in flight for it and its bytes are not on the backend — an absent
entry, or a `DISCOVERED` one (the walk found it and no job exists — either nothing has been attempted, or an
attempt failed and returned the row to `DISCOVERED`). There is no content-version comparison and no re-upload
of an existing key. `Upload` and `Retry` SHALL implement a common `Work` interface exposing the request, so
platforms execute all work arms identically. There is no attempt counter: the engine carries no per-key
history beyond the ledger's state. For `AlreadyUploaded` the ledger SHALL be left untouched (the same as every
`ResourceChanged` answer, which never writes).

`DISCOVERED` answering `Work` is what keeps the ledger usable as the cycle's record of its own backlog
(capability `sync-ledger`): the state exists so a cycle can remember a resource it saw but could not
enqueue, and an engine that treated that memory as "already handled" would suppress the very work it
was written to preserve.

The decision SHALL be an exhaustive `when` over `LedgerState` with no `else` branch, so a state added
without classifying it here fails to compile rather than falling into whichever arm a default named.

#### Scenario: Unknown resource uploads without writing the ledger
- **WHEN** `handle(ResourceChanged(resource))` is called and the ledger has no entry for its
  filename
- **THEN** `Upload` is returned and the ledger still has no entry for the key
  (recording is deferred to `UploadStarted`)

#### Scenario: Completed key skips
- **WHEN** the ledger entry is `COMPLETED`
- **THEN** `AlreadyUploaded` is returned, no provider call is made, and the ledger entry is
  unchanged

#### Scenario: In-flight request skips on re-submission
- **WHEN** the ledger entry is `REQUESTED` and the same resource is re-submitted as `ResourceChanged`
- **THEN** `AlreadyUploaded` is returned, no provider call is made, and the ledger is unchanged — an
  in-flight job is not duplicated

#### Scenario: Discovered entry uploads
- **WHEN** the ledger entry is `DISCOVERED` and the resource is submitted as `ResourceChanged`
- **THEN** `Upload` is returned, so a resource the cycle recorded but could not
  enqueue is re-derived as work rather than suppressed by its own record

#### Scenario: A failed upload's entry re-uploads
- **WHEN** an upload of the resource failed, returning its entry to `DISCOVERED`, and the same resource is
  submitted as `ResourceChanged`
- **THEN** `Upload` is returned and nothing is written until `UploadStarted`

#### Scenario: A new ledger state must be classified here
- **WHEN** a value is added to `LedgerState` and this decision is not updated
- **THEN** the build fails, because the decision is an exhaustive `when` with no `else` branch

#### Scenario: Resource instance round-trips
- **WHEN** a `Work` decision is returned for a resource
- **THEN** `decision.request.resource` is the identical instance the platform supplied (no
  copying), so the platform can read its opaque `data` payload back at the execution edge

### Requirement: Request minting via the request provider
For every `Work` decision the engine SHALL obtain the request by calling
`UploadRequestProvider.provide(resource)` with the platform's resource instance, and SHALL carry
the returned `UploadRequest` on the decision unmodified. The provider SHALL NOT be called when the
answer is `AlreadyUploaded`. Encoding and placement of the filename remain the provider's
responsibility under the deterministic-and-injective filename→destination contract.

#### Scenario: Provider receives the resource
- **WHEN** `handle(ResourceChanged(resource))` yields a `Work` decision
- **THEN** the provider was invoked exactly once with that same resource instance, and
  `decision.request` is its return value, unmodified

#### Scenario: No minting for skipped work
- **WHEN** `handle(ResourceChanged(resource))` yields `AlreadyUploaded`
- **THEN** the provider was not invoked

### Requirement: Failure adjudication — retry forever
When a platform submits `UploadFailed(request, error)`, the engine SHALL answer `Retry` carrying a request
newly minted via `provide(request.resource)` — for every `UploadError` variant, with no attempt budget and no
attempt count — and SHALL record `DISCOVERED` for the key: a failed upload returns its row to the state that
needs a job. `Retry` SHALL remain a distinct `Work` arm from `Upload`; platforms execute both identically, and
the arm names the decision's provenance for logs and the harness journal. The engine SHALL NOT record
`REQUESTED` for the retry here; the ledger is left in `DISCOVERED` until the platform creates the retry job and
reports `UploadStarted` (write-after-act). Recording `DISCOVERED` is an idempotent **guarded** upsert: it SHALL
NOT overwrite a row in a done state (capability `sync-ledger`, "Record operations"). The answer SHALL be
`Retry` whether or not the record applied — the engine's decision does not depend on the guard.

#### Scenario: Fresh request on retry, ledger left DISCOVERED
- **WHEN** `handle(UploadFailed(request, Http(403)))` is called
- **THEN** `Retry` is returned with a request newly obtained from the provider for the same resource
  instance, and the ledger entry for the key is `DISCOVERED` (the new `REQUESTED` is written only when the
  platform reports `UploadStarted`)

#### Scenario: Every error kind retries
- **WHEN** failures with `Network`, `Http(500)`, `Cancelled`, and `Unknown("x")` are each handled
- **THEN** each yields exactly one `Retry` — none is dropped

#### Scenario: A late failure never un-completes a key
- **WHEN** `handle(UploadFailed(request, Network))` is called for a key whose ledger entry is `COMPLETED`
- **THEN** `Retry` is still returned, and the ledger entry is unchanged — still `COMPLETED`

### Requirement: Upload-started recording (write-after-act)
The engine SHALL accept a `SyncEvent.UploadStarted(request)` observation, reported by the platform
**after** it has created (or retried) the upload job for `request`. On `UploadStarted` the engine SHALL
record `REQUESTED` for the key, and SHALL answer `AlreadyUploaded` (there is
nothing further for the platform to do). `REQUESTED` SHALL be recorded **only** on `UploadStarted` —
never on `ResourceChanged` or `UploadFailed`. Recording is an idempotent per-key **guarded** upsert that
SHALL NOT overwrite a row in a done state (capability `sync-ledger`, "Record operations"),
so a duplicated or replayed `UploadStarted` converges to the same entry and a late one never un-completes a key;
a dropped `UploadStarted`
(the platform created the job but died before reporting) leaves no `REQUESTED`, which a later
`ResourceChanged` re-derivation safely re-issues as `Work`.

#### Scenario: Created job records REQUESTED
- **WHEN** `handle(UploadStarted(request))` is called for a key with no entry (or a `DISCOVERED` entry)
- **THEN** the ledger entry becomes `REQUESTED`, and `AlreadyUploaded` is returned

#### Scenario: Decision then act then record converge
- **WHEN** `ResourceChanged` yields `Upload` (no write), the platform creates the job, and reports
  `UploadStarted(request)`
- **THEN** the ledger holds `REQUESTED` exactly once for the key, regardless of how many times the
  `ResourceChanged`→`UploadStarted` pair is replayed

#### Scenario: Dropped UploadStarted is re-issued, not stranded
- **WHEN** an `Upload` decision is acted on but its `UploadStarted` is never delivered, and the same
  resource is later re-submitted as `ResourceChanged`
- **THEN** the engine returns `Work` again (the key has no `REQUESTED`), so the create is retried
  rather than skipped

#### Scenario: A late start never un-completes a key
- **WHEN** `handle(UploadStarted(request))` is called for a key whose ledger entry is `COMPLETED`
- **THEN** `AlreadyUploaded` is returned and the ledger entry is unchanged
