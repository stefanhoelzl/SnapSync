## MODIFIED Requirements

### Requirement: Background resource download to durable staging

Selected resources SHALL be downloaded via a `URLSession` whose configuration is bound per **compilation
target** (`ios-url-session-upload`, "The transport binding is fixed by the compilation target"): on
`iosArm64` — every shipped binary — a **background** session, so transfers continue while the app is
suspended and the OS relaunches the app on completion; on `iosSimulatorArm64` a **default** session, which
transfers in-process and survives neither suspension nor process death. The download transport SHALL
obtain that configuration from the same seam the app-driven upload platform uses, so the two cannot hold
different bindings in one build. Every requirement below holds identically on both targets.

The session SHALL fetch each resource **directly** from the presigned S3 URL carried in its union
`resource.url` (capability `api-endpoints`) — i.e. straight from bunny's S3 endpoint, not through
the backend. The session SHALL allow **both Wi-Fi and cellular** (not Wi-Fi-only) and SHALL NOT be
discretionary, so downloads make progress on mobile networks too, with a bounded number of in-flight
tasks (enqueue more as tasks complete). (`discretionary` and the launch-events request are background-only
properties; on the default binding they are inert, which is why they are declared where the background
configuration is built rather than at this call site.) A transfer failure SHALL leave the resource pending
for retry (no terminal failure state).

A finished transfer is not a successful one, and SHALL NOT be staged on the strength of having finished.
Before its bytes are moved into staging, the download client SHALL evaluate the transfer's outcome — the
HTTP status, the expected byte count, and the received byte count — and SHALL treat as a **failed**
transfer, to be retried:

- a response whose status is not a success status (`2xx`); and
- a response whose received body is **shorter than its `Content-Length`** (a truncated download) — the
  integrity signal formerly guaranteed by the download proxy, now evaluated against bunny's S3 GET response.

A status check is not redundant with the transport's own error reporting: a `URLSession` reports
an HTTP error as a *successful transfer of an error body* — the download-finished callback fires with the
error document in hand and the completion error is absent — so the status check is the only thing that sees
a `502`.

A transfer SHALL be rejected only on **positive evidence** that its bytes are bad, never on the absence of
evidence. Where the expected byte count is **unknown** (the server omitted `Content-Length`), the transfer
SHALL NOT be rejected for length; the status check stands alone. A received count **exceeding** the expected
count SHALL NOT be rejected either. Where the status is unknown, the transfer SHALL NOT be rejected for
status. Only a known-and-under count is a truncation, and only a known non-2xx status is a failure. The
reason is not caution but arithmetic: a rejected transfer is retried, and a retry only helps when the
condition can change. A server that omits `Content-Length` omits it on every attempt, so rejecting on an
unknown length is not a retry — it is an unbounded loop in which the photo never arrives, the same
permanent, invisible loss as accepting bad bytes, reached from the other side.

An accepted transfer's bytes SHALL be moved out of the system temporary location into a durable App-Group
staging location and recorded in the download store. A **rejected** transfer's bytes SHALL NOT be moved or
recorded: the resource stays un-staged, which is the existing pending-for-retry state rather than a new
terminal one, and the next reconcile re-downloads it. Because staging replaces whatever occupies the
destination path, evaluating the outcome **before** the move also prevents a rejected body from destroying
an earlier good file. The finished-transfer callback delivers a temporary-location URL on **both**
bindings, and the move out of it SHALL be performed within that callback on both, so the staging step does
not vary by target.

The transport seam SHALL carry these outcome facts to the client, and the decision SHALL be taken in code
covered by `commonTest` rather than in the platform edge, which is the platform boundary and nothing more.

#### Scenario: A completed download is staged durably

- **WHEN** a download task for a resource finishes with a success status and no short read
- **THEN** its bytes are moved to durable App-Group staging and the resource is marked downloaded in
  the store

#### Scenario: The shipped binary downloads over a background session

- **WHEN** the app runs on a physical device
- **THEN** transfers run over a background `URLSession` and continue while the app is suspended

#### Scenario: A simulator downloads, and claims nothing about suspension

- **WHEN** the app runs on an iOS simulator and a foreign resource is enqueued
- **THEN** the bytes transfer over the default session, are staged and imported exactly as on a device, and
  the run is not treated as evidence that transfers survive suspension or that the OS relaunches the app

#### Scenario: A resource is fetched directly from bunny's S3 endpoint

- **WHEN** a resource is enqueued for download
- **THEN** the task targets its presigned S3 `url` directly (no backend byte proxy), needing
  no per-task authorization header

#### Scenario: A failed transfer is retried, not failed

- **WHEN** a download task completes with an error
- **THEN** the resource remains pending and is retried on a later sync; no terminal failure state is
  recorded

#### Scenario: A short read is treated as a failed download

- **WHEN** a download returns a body shorter than its `Content-Length`
- **THEN** the transfer is treated as failed and retried; the truncated bytes are not accepted as the
  complete object

#### Scenario: A non-2xx response is not a photo

- **WHEN** a transfer finishes with status `502` and an error body, and the transport reports no error
- **THEN** it is treated as a failed transfer and retried; its bytes are not staged

#### Scenario: An unknown Content-Length is not a truncation

- **WHEN** a transfer finishes with a success status and the response carries no `Content-Length`
- **THEN** it is accepted and staged, because an unknown expected count cannot establish a short read, and
  rejecting it would recur on every retry rather than resolve

#### Scenario: A body longer than its Content-Length is not a truncation

- **WHEN** a transfer finishes with a success status and a received body larger than its `Content-Length`
- **THEN** it is accepted and staged, because an over-long body is not a short read

#### Scenario: Rejected bytes never become the store's truth

- **WHEN** a transfer is rejected on status or length
- **THEN** the resource is not recorded as staged, so the next reconcile re-downloads it rather than
  re-importing it

#### Scenario: A rejection does not destroy an earlier good file

- **WHEN** a transfer is rejected and a previously staged, valid file already exists at that resource's
  staging path
- **THEN** the existing file is left intact, because a rejected transfer's bytes are never moved into staging
