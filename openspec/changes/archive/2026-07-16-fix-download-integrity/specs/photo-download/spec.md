## MODIFIED Requirements

### Requirement: Background resource download to durable staging

Selected resources SHALL be downloaded via a background `URLSession` (so transfers continue while the
app is suspended), fetching each resource **directly** from the presigned S3 URL carried in its union
`resource.url` (capability `bunny-list-endpoint`) — i.e. straight from bunny's S3 endpoint, not through
the backend. The session SHALL allow **both Wi-Fi and cellular** (not Wi-Fi-only) and SHALL NOT be
discretionary, so downloads make progress on mobile networks too, with a bounded number of in-flight
tasks (enqueue more as tasks complete). A transfer failure SHALL leave the resource pending for retry (no
terminal failure state).

A finished transfer is not a successful one, and SHALL NOT be staged on the strength of having finished.
Before its bytes are moved into staging, the download client SHALL evaluate the transfer's outcome — the
HTTP status, the expected byte count, and the received byte count — and SHALL treat as a **failed**
transfer, to be retried:

- a response whose status is not a success status (`2xx`); and
- a response whose received body is **shorter than its `Content-Length`** (a truncated download) — the
  integrity signal formerly guaranteed by the download proxy, now evaluated against bunny's S3 GET response.

A status check is not redundant with the transport's own error reporting: a background `URLSession` reports
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
an earlier good file.

The transport seam SHALL carry these outcome facts to the client, and the decision SHALL be taken in code
covered by `commonTest` rather than in the platform edge, which is the platform boundary and nothing more.

#### Scenario: A completed download is staged durably

- **WHEN** a background download task for a resource finishes with a success status and no short read
- **THEN** its bytes are moved to durable App-Group staging and the resource is marked downloaded in
  the store

#### Scenario: A resource is fetched directly from bunny's S3 endpoint

- **WHEN** a resource is enqueued for download
- **THEN** the background task targets its presigned S3 `url` directly (no backend byte proxy), needing
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

### Requirement: Import without foreground; relaunch and backstop

Import SHALL run without the app being foregrounded: a download completing while the app is
backgrounded SHALL trigger import in the background-execution window, and a download completing while
the app is terminated SHALL relaunch the app via `handleEventsForBackgroundURLSession` to finish.
Because no further download event wakes the app once transfers are exhausted, the client SHALL also
drain pending imports via an OS-scheduled background task (e.g. `BGProcessingTask`) so an import that
overran its wake window still completes without a foreground visit. Staged bytes + the store make any
deferred import a safe retry.

That last property is **conditional, and the transfer check is its condition**. A deferred import is a safe
retry only because staged bytes were accounted for at transfer time. Absent that check, a permanently
invalid body — an error document staged under a photo's path — makes the retry a trap rather than a
safeguard: the import fails on every reconcile, and the transfer is never re-run, because a resource
recorded as staged is never re-planned. The asset is then permanently unimportable and permanently retried,
and the photo never arrives. Retrying a failed import is correct for a transient failure and poison for
invalid bytes; only rejecting bad bytes before staging keeps the two apart.

#### Scenario: Background import on download completion

- **WHEN** a download completes while the app is backgrounded (not foreground)
- **THEN** the asset whose set is now complete is imported in the background

#### Scenario: Import tail is drained without foreground

- **WHEN** an asset's resources are all staged but its import did not complete in a download-wake
  window and no further download is pending
- **THEN** a scheduled background task completes the import without requiring the user to open the app

#### Scenario: An invalid body never reaches the importer

- **WHEN** a transfer's bytes are rejected on status or length
- **THEN** they are never staged, so no import is ever attempted against them and no asset becomes
  permanently unimportable
