# upload-completion-notify Specification

## Purpose
TBD - created by archiving change notify-driven-download. Update Purpose after archive.
## Requirements
### Requirement: Notify fires once per drained cycle that completed an upload

The upload cycle SHALL fire exactly one event notify for the configured event when it **fully drains**
(`CycleResult.COMPLETED` — its discover/create/drain completes without platform backpressure, no cap
truncation) **and** has recorded at least one upload completion this cycle. The notify SHALL be fired
**after** the in-cycle device-manifest write, because the event union (the
recipient's authority) reflects a newly-completed asset only once its owning device's manifest has been
written; firing before that would wake recipients to a union that does not yet list the new assets. A
cycle that is cap-truncated (does not fully drain) SHALL NOT notify — even if it recorded completions —
and a fully-drained cycle that recorded **no** completion SHALL NOT notify. "Completion" means a real
terminal success (a succeeded job with a recoverable key), not a re-acknowledgement of an
already-completed key.

#### Scenario: Drained cycle with a completion notifies after the manifest write

- **WHEN** an upload cycle fully drains and recorded at least one upload completion
- **THEN** the cycle writes the device manifest and then fires exactly one event notify for the
  configured event

#### Scenario: Cap-truncated cycle does not notify

- **WHEN** an upload cycle is cap-truncated (returns a still-processing result) even though it recorded
  completions
- **THEN** no event notify is fired for that cycle (the union has not been refreshed for those assets)

#### Scenario: Drained cycle with no completion does not notify

- **WHEN** an upload cycle fully drains but recorded no upload completion this cycle
- **THEN** no event notify is fired

### Requirement: Notify is an injected best-effort, bounded seam

The notify trigger SHALL be an **injected seam** so the upload cycle stays event-agnostic and
platform-free (exercised on both JVM and `iosSimulatorArm64` with a fake), with a default no-op for
tests and harness. The seam carries the configured event id at the composition root; the cycle SHALL
NOT itself construct any event id, URL, or HTTP client. The notify send SHALL be **best-effort**:
**non-throwing**, subject to a **bounded timeout**, and performed with **no retry** — a failing, slow,
or hung notify SHALL NOT fail, stall, or delay the upload cycle beyond that bound. Upload completions
are durable in the ledger independently of the notify, so a dropped notify never loses state (the next
foreground reconcile on recipients is the standing backstop).

#### Scenario: A failed notify does not fail the cycle

- **WHEN** the notify send errors or the endpoint returns a non-2xx status
- **THEN** the failure is absorbed (logged) and the upload cycle completes normally

#### Scenario: A slow notify is bounded

- **WHEN** the notify send hangs or is slow
- **THEN** it is abandoned at its timeout bound and does not stall the upload cycle

#### Scenario: Default seam is inert

- **WHEN** the upload cycle is constructed without an injected notifier (tests/harness)
- **THEN** a drained cycle with completions performs no notify and behaves exactly as before

### Requirement: Event notify request shape

The notifier SHALL issue an HTTP `POST` to `<host>/event/<eventId>/notify` for the cycle's configured
event id, with **no request body** and **no authorization token** (the event id is the capability). It
SHALL build the request with string composition only — no crypto, no signing — reusing the shared
injected HTTP client, and SHALL treat any 2xx as success and any other outcome as an absorbed failure.

#### Scenario: Notify POSTs the route with no body

- **WHEN** the notifier fires for event id `E`
- **THEN** it issues `POST <host>/event/E/notify` carrying no request body and no authorization token

