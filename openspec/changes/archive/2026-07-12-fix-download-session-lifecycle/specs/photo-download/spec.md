## ADDED Requirements

### Requirement: Transfer cancellation is task-level; the background session is never invalidated

Cancelling the download client's transfers (on leave, switch, or re-provision) SHALL cancel the
outstanding background **tasks** and SHALL leave the background `URLSession` **alive and reusable**. The
client SHALL NOT call `invalidateAndCancel` (or any other invalidation) as a cancellation mechanism.

Invalidation is terminal: creating a task on an invalidated `NSURLSession` throws an Objective-C
`NSException`, which Kotlin/Native cannot catch and which aborts the process. Because a cancel is always
followed by a later download (a re-join, a foreground reconcile, or a silent push), a session destroyed by
cancellation is a crash waiting for the next reconcile. Cancellation therefore acts on tasks, never on the
transport. The background-session identifier SHALL remain stable across cancellations and app launches, so
`handleEventsForBackgroundURLSession` can re-adopt the session and redeliver completions.

#### Scenario: Cancelling transfers leaves the session usable

- **WHEN** all transfers are cancelled and a resource is subsequently enqueued for download
- **THEN** a new download task is created and started successfully — the cancel did not destroy the
  transport

#### Scenario: Leave then re-join downloads again

- **WHEN** the user leaves an event (cancelling in-flight transfers) and later re-joins an event whose
  union lists foreign assets
- **THEN** the reconcile enqueues those resources and their downloads run; the app does not abort

#### Scenario: Cancellation stops the in-flight transfers

- **WHEN** transfers are cancelled while downloads are in flight
- **THEN** those tasks are cancelled, the pending queue is emptied, and the bounded in-flight window
  frees up for later enqueues

### Requirement: A session invalidated by the system is rebuilt, not reused

The client SHALL discard a background `URLSession` that the system has invalidated (delivered as the
delegate's `didBecomeInvalidWithError`) and SHALL build a fresh session — reusing the same stable
identifier — before creating any further task. It SHALL NOT create a task on a session it has been told is
invalid.

#### Scenario: A system-invalidated session self-heals

- **WHEN** the system invalidates the background session and a resource is subsequently enqueued
- **THEN** the client builds a fresh session and creates the download task on it, rather than reusing the
  invalidated session

### Requirement: A resource whose URL is not fetchable is skipped, not fatal

A planned resource whose `url` is not a fetchable `http`/`https` URL with a host SHALL be logged and
skipped, and SHALL NOT be handed to the background session (which raises an uncatchable Objective-C
exception for an unsupported URL). This covers both a `url` that fails to parse and one that parses to an
unsupported scheme or has no host. Skipping SHALL leave the resource pending for a later reconcile,
consistent with "a failed transfer is retried, not failed" — no terminal failure state is recorded.

#### Scenario: An unsupported download URL is skipped

- **WHEN** a planned resource carries a URL that is not an `http`/`https` URL with a host
- **THEN** it is logged and skipped, the remaining resources still enqueue, and the app does not abort

### Requirement: Download-job orchestration is tested behind a transport seam

The download client's orchestration SHALL live in a platform-independent unit covered by `commonTest`
(running on **both** JVM and `iosSimulatorArm64`), with the `NSURLSession` / `NSFileManager` calls behind a
narrow **transport** seam that a test fake can substitute. That orchestration comprises the pending queue,
the bounded in-flight window and its refill, the task-description encoding that carries
`(deviceId, assetId, resourceKey)` through a transfer, the staging-path computation, the URL guard, and the
cancellation lifecycle above.

The fake SHALL be able to model a destroyed transport (task creation after destruction fails), so that
"cancellation never destroys the transport" is pinned by an automated test rather than by device
verification alone. Faking only the outer `PhotoDownloadJobs` seam is insufficient — it replaces the very
implementation that carries this logic.

#### Scenario: The cancellation lifecycle is covered without a device

- **WHEN** the download client's tests run on JVM and `iosSimulatorArm64`
- **THEN** a fake transport that fails task creation after destruction demonstrates that cancelling
  transfers and then enqueueing still creates tasks

#### Scenario: The bounded window and task-description codec are covered

- **WHEN** the download client's tests run
- **THEN** the in-flight window's refill-on-completion behavior and the round-trip of
  `(deviceId, assetId, resourceKey)` through the task description are asserted without any iOS runtime
