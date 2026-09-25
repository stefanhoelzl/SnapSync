## MODIFIED Requirements

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


## ADDED Requirements

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
the next cycle records them anyway. Decision record: `changes/own-work-per-wake` (D13).

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
