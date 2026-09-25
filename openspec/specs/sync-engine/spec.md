# sync engine Specification

## Purpose

The shared decision core of the sync backend: platform adapters drive it with observation events
(a resource exists with this content state, an upload was started, an upload failed) and act on the
decisions it answers with. The engine's only state is its ledger — the durable per-key memory of
what was requested, completed, and still needs a job. The engine records requests and failures (a failure
returns the row to `DISCOVERED`); a completed upload
is recorded by the platform itself, where the platform reports it, through the ledger's guarded terminal
write (capability `sync-ledger`). The sync domain
transports resources grouped by an opaque `assetId` — the engine carries the `assetId` through to
the ledger but does not interpret it; richer asset handling lives in a later layer above the seam;
encoding and placement of identity live below it, in the upload-request provider.

**Why a ledger, and not a stateless engine.** Every upload walk is a full enumeration of the in-scope
library (capability `ios-photokit-upload`; before `changes/archive/2026-09-21-always-full-enumerate` a routine
change-token expiry forced one anyway). With no memory of what is already stored, every walk would re-upload
the entire library — tens of thousands of assets, hundreds of gigabytes. The ledger is what makes skipping
*provable*: a `COMPLETED` key is never re-uploaded, so re-walking the library is idempotent and harmless. Platforms therefore report **observations, never bookkeeping** — they do not
filter, dedupe, or track what was uploaded, because exactly-once across the file system and the job system
is impossible and reports are at-least-once by construction.

Decision record: `changes/archive/2026-06-12-sync-engine-ledger`.

The resource-changed decision gained `DISCOVERED` → `Upload` in
`changes/archive/2026-08-27-fix-cap-truncation-loop`. The `UPLOADED` state and the engine's completion
recording were removed in `changes/archive/2026-09-15-retire-uploaded-state`. The `FAILED` state, the
attempt count and `UploadJob` (the engine now speaks `UploadRequest`) were removed in
`changes/archive/2026-09-21-shrink-the-ledger-row`.
Decision record for the work query that mints nothing: `changes/archive/2026-09-25-own-work-per-wake`.
## Requirements
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
For every `Work` decision the engine SHALL obtain the request from the `UploadRequestProvider` with the
platform's resource instance — `provide(resource)` for an `Upload` (a `ResourceChanged` answer), and
`provideForRetry(resource)` for a `Retry` (an `UploadFailed` answer), which reads the credential from its store
of record rather than from any in-process copy (capability `edge-upload-provider`) — and SHALL carry
the returned `UploadRequest` on the decision unmodified. The provider SHALL NOT be called when the
answer is `AlreadyUploaded`, nor by the `isWork` query (see "A work query that mints nothing"). Encoding
and placement of the filename remain the provider's responsibility under the deterministic-and-injective
filename→destination contract.

#### Scenario: Provider receives the resource
- **WHEN** `handle(ResourceChanged(resource))` yields a `Work` decision
- **THEN** the provider was invoked exactly once with that same resource instance, and
  `decision.request` is its return value, unmodified

#### Scenario: No minting for skipped work
- **WHEN** `handle(ResourceChanged(resource))` yields `AlreadyUploaded`
- **THEN** the provider was not invoked

#### Scenario: A retry mints through the retry read
- **WHEN** `handle(UploadFailed(request, error))` yields `Retry`
- **THEN** `provideForRetry` was invoked exactly once with `request.resource`, `provide` was not invoked,
  and `decision.request` is its return value, unmodified

### Requirement: Failure adjudication — retry forever
When a platform submits `UploadFailed(request, error)`, the engine SHALL answer `Retry` carrying a request
newly minted via `provideForRetry(request.resource)` — for every `UploadError` variant, with no attempt budget and no
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

### Requirement: Provider failures rethrow
If the request provider throws, the engine SHALL NOT catch it: `handle` fails with that exception
and the ledger SHALL be left unchanged for that event (the engine records only after minting
succeeds). The event counts as unprocessed; re-handling the same event later is safe because
recording is an idempotent per-key upsert.

#### Scenario: Provider failure propagates and leaves no trace
- **WHEN** the provider throws during `handle(ResourceChanged(resource))` for a key with no ledger
  entry
- **THEN** the caller receives the provider's exception unswallowed, the ledger still has no entry
  for the key, and a subsequent `handle` of the same event succeeds when the provider does

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

### Requirement: Resource asset identity
`Resource` SHALL carry `assetId: String` — an opaque grouping identifier for the asset a resource
belongs to (several resources of one photo share it). The engine SHALL carry `assetId` through to
the ledger (via the record operations) but SHALL NOT interpret it — like `filename`, it is pure
identity whose meaning belongs to the platform (iOS: the asset's `localIdentifier`, normalized;
tests/console: any string). It plays no part in the decision: a `ResourceChanged` is still decided
solely from the ledger entry for `filename`.

#### Scenario: assetId is carried into the recorded entry
- **WHEN** a resource with `assetId = "A"` is uploaded and the platform reports `UploadStarted`
- **THEN** the ledger entry for its key has `assetId == "A"`

#### Scenario: assetId does not change the decision
- **WHEN** a `ResourceChanged` is handled for a resource whose key is absent from the ledger
- **THEN** the answer is `Upload` regardless of the resource's `assetId` (the decision reads only
  `filename`)

### Requirement: A work query that mints nothing

The engine SHALL answer `isWork(resource)`: whether `handle(ResourceChanged(resource))` would answer a `Work`
decision, **without** minting the request that answer carries. It serves a caller that only records what a walk
found and acts later — the upload cycle's discovery pass records `DISCOVERED` rows and creates no job, so a
request minted there (a URL, headers, and a device-token read) would be thrown away, and the act re-derives
the request through `handle`, which mints the one that is sent.

`isWork` SHALL use the **same** ledger classification as the resource-changed decision: one exhaustive `when`
over `LedgerState` with no `else` branch, which both answers go through, so the engine remains the one place
that decides whether a key uploads. `isWork` SHALL write nothing, SHALL NOT call the provider, and therefore
SHALL NOT fail because the provider fails. A `true` answer SHALL log the same line that `handle`'s `Upload` arm
logs, so the device log reads the same whichever of the two a caller asked.

`handle(ResourceChanged)` is unchanged by this query: it still mints for every `Work` answer and never for
`AlreadyUploaded`, and every job a cycle creates for a discovered resource is still minted through it. A
provider failure (for example, the app's token read on an unreadable Keychain) therefore surfaces when the job
is created, after the walk's `DISCOVERED` rows are recorded, rather than before. Those rows are the walk's own facts, idempotent, and
the next cycle records them anyway. Decision record: `changes/archive/2026-09-25-own-work-per-wake` (D13).

#### Scenario: The query agrees with the decision
- **WHEN** `isWork(resource)` and `handle(ResourceChanged(resource))` are each asked for a key that is
  absent, `DISCOVERED`, `REQUESTED` and `COMPLETED` in turn
- **THEN** `isWork` is `true` exactly where `handle` answers `Upload`, and `false` exactly where it answers
  `AlreadyUploaded`

#### Scenario: The query mints nothing and writes nothing
- **WHEN** `isWork(resource)` is asked for a key that is work
- **THEN** the provider is not invoked, and the ledger is unchanged

#### Scenario: A failing provider cannot fail the query
- **WHEN** the provider throws on every call and `isWork(resource)` is asked
- **THEN** the query answers from the ledger without throwing

