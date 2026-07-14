## MODIFIED Requirements

### Requirement: OPTIONS preflight falls back to plain PUT

The endpoint SHALL respond to an `OPTIONS` request such that the iOS background uploader proceeds with
a plain, single-shot (non-resumable) `PUT` — i.e. it SHALL NOT advertise resumable-upload support.
(Server-side resumable uploads are a deferred future capability.)

This SHALL hold **as observed by the device through the bunny CDN pull zone that fronts the Edge
Script**, not merely at the script's origin. The pull zone is free to answer or rewrite `OPTIONS`
itself, so the script's own response is not on its own sufficient: what the requirement constrains is
the response the **device** receives from the device-facing origin.

#### Scenario: OPTIONS does not advertise resumable

- **WHEN** an `OPTIONS` request is received for an upload path
- **THEN** the response does not advertise resumable-upload support, signaling a plain `PUT` path

#### Scenario: The device-visible OPTIONS response, through the CDN, yields a plain PUT

- **WHEN** the iOS background uploader preflights an upload path at the device-facing origin, and that
  preflight is answered by the pull zone rather than by the script
- **THEN** the response still advertises no resumable-upload support, and the uploader proceeds with a
  plain, single-shot `PUT` that the endpoint stores

### Requirement: Faithful outcome propagation

The endpoint SHALL return a `2xx` status **only** when bunny confirms the object was stored. Any
upstream failure, timeout, aborted stream, or partial write SHALL be surfaced as a `5xx` status; the
endpoint SHALL NEVER return `2xx` for an unconfirmed or partial upload. (A false success would, under
the engine's retry-forever policy, strand a truncated object permanently.)

The `2xx` the endpoint returns SHALL be one the iOS background uploader accepts as success **as
delivered through the bunny CDN pull zone** — a success code the uploader rejects would strand the
resource retrying forever even though the object is durably stored, which is the same harm as a false
failure and is invisible to the endpoint.

#### Scenario: Upstream success propagated

- **WHEN** bunny returns a success status confirming the stored object
- **THEN** the endpoint responds `2xx`

#### Scenario: Upstream failure propagated as 5xx

- **WHEN** bunny returns an error, the request times out, or the stream aborts mid-upload
- **THEN** the endpoint responds `5xx` and never `2xx`

#### Scenario: The device-visible success code is accepted by the uploader

- **WHEN** the iOS background uploader completes a `PUT` against the device-facing origin and the
  endpoint's `2xx` reaches it through the pull zone
- **THEN** the uploader treats the upload as successful and does not retry the resource
