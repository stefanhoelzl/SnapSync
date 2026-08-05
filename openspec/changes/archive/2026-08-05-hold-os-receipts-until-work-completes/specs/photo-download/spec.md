## ADDED Requirements

### Requirement: Each import is bounded, and a timeout stops the drain for that wake

Each per-asset import SHALL be bounded by a deadline. The bound SHALL be placed on the **wait** for the
library's completion callback, never on the library call itself: the asset-creation request returns to
its caller and only the awaiting coroutine suspends, so abandoning the wait frees a continuation and no
thread. An unbounded wait is not merely a lost photo — the import holds the download controller's
serializing lock, so every later reconcile, import, leave and switch in that process queues behind it
forever.

When an import exceeds its deadline the drain SHALL stop for that wake rather than continue to the next
importable asset, and the expiry SHALL be logged. A stall in the photo library is a property of the
device at that moment, not of the photo: continuing would abandon further transactions, each of which may
still commit and so become a duplicate candidate.

An abandoned wait SHALL leave the asset un-imported in the store, so the existing durable retry path
imports it at a later wake.

#### Scenario: A stalled import releases the lock

- **WHEN** an import's completion callback has not arrived when its deadline expires
- **THEN** the wait is abandoned, the controller's lock is released, the expiry is logged, and later
  reconciles and imports in that process proceed

#### Scenario: A timed-out import is retried later

- **WHEN** an import is abandoned at its deadline
- **THEN** the asset remains not-imported in the store and is imported at a subsequent wake

#### Scenario: One deadline stops the wake's drain

- **WHEN** an import exceeds its deadline while further assets are importable
- **THEN** no further import is attempted in that wake, and the remaining assets are drained at the
  next one

### Requirement: A failed union fetch still drains the staged imports

A reconcile whose union fetch fails SHALL still drain the assets whose resources are already staged,
rather than returning. Discovery and import are independent: the drain reads only the download store and
bytes already on disk, so a network failure has nothing to say about whether they can be imported.

This was inert while a failing fetch consumed the whole wake. Once the client carries an explicit request
timeout (capability `ios-app-shell`) a failure returns in seconds with most of the wake budget unspent,
and skipping the drain strands importable assets until some later wake for no reason.

Planning and enqueueing SHALL still be skipped, since those are exactly what the missing union would have
informed.

#### Scenario: A fast union failure still imports what is staged

- **WHEN** the union fetch fails and assets in the store already have all their resources staged
- **THEN** those assets are imported in that same wake, and no new downloads are planned or enqueued

#### Scenario: Last-good state survives the failure

- **WHEN** the union fetch fails
- **THEN** no planned or staged rows are dropped

## MODIFIED Requirements

### Requirement: Import without foreground; relaunch and backstop

Import SHALL run without the app being foregrounded: a download completing while the app is
backgrounded SHALL trigger import in the background-execution window, and a download completing while
the app is terminated SHALL relaunch the app via `handleEventsForBackgroundURLSession` to finish.
The imports that a background-session wake triggers SHALL complete, or reach their deadline, **before**
that wake's OS completion handler is released (capability `ios-app-shell`); the staged-resource callback
SHALL therefore be awaitable and its outstanding work tracked by the download-job owner, rather than
dispatched and forgotten by the composition. Reporting the session's events drained while the imports
they caused are merely queued is what leaves an asset staged-but-unimported at suspension.
Because no further download event wakes the app once transfers are exhausted, the client SHALL also
drain pending imports via an OS-scheduled background task (e.g. `BGProcessingTask`) so an import that
overran its wake window still completes without a foreground visit. Staged bytes + the store make any
deferred import a safe retry. The backstop's coordination — the trigger-time membership re-read
(`reloadConfig` — see `ios-app-shell`, *Background triggers re-read the membership and fail cleanly
before first unlock*), the attestation wake, then the import drain — SHALL be the
`flow/DownloadBackstop` trigger (`:domain` `flow/`, built in `compose/` with the re-read and wake
injected as **suspend** effect lambdas, per the law *A trigger flow never outlives its own run*); the
untested app shell keeps only the entry-point log wrap, the re-arm, and the OS task-completion handler.
A backstop wake landing before the first unlock since boot fails cleanly and converges at the next wake
(the import's reads are caught; the adapters distinguish unreadable from absent; nothing mints, clears,
or leaves).

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

#### Scenario: A wake's imports complete before its handler is released

- **WHEN** a background-session wake delivers several staged resources
- **THEN** the imports they trigger complete, or reach their deadline, before the OS completion handler
  for that wake is released

#### Scenario: Import tail is drained without foreground

- **WHEN** an asset's resources are all staged but its import did not complete in a download-wake
  window and no further download is pending
- **THEN** a scheduled background task completes the import without requiring the user to open the app

#### Scenario: An invalid body never reaches the importer

- **WHEN** a transfer's bytes are rejected on status or length
- **THEN** they are never staged, so no import is ever attempted against them and no asset becomes
  permanently unimportable
