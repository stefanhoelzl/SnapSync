# photo-download — delta for foreground-poll-and-swift-transcriber

## MODIFIED Requirements

### Requirement: Import without foreground; relaunch and backstop

Import SHALL run without the app being foregrounded: a download completing while the app is
backgrounded SHALL trigger import in the background-execution window, and a download completing while
the app is terminated SHALL relaunch the app via `handleEventsForBackgroundURLSession` to finish.
Because no further download event wakes the app once transfers are exhausted, the client SHALL also
drain pending imports via an OS-scheduled background task (e.g. `BGProcessingTask`) so an import that
overran its wake window still completes without a foreground visit. Staged bytes + the store make any
deferred import a safe retry. The backstop's coordination — the trigger-time membership re-read
(`reloadConfig` — see `ios-app-shell`, *Background triggers re-read the membership and fail cleanly
before first unlock*), the attestation wake, then the import drain — SHALL be the
`flow/DownloadBackstop` trigger (`:domain` `flow/`, built in `compose/` with the re-read and wake
injected as effect lambdas); the untested app shell keeps only the entry-point log wrap, the
re-arm, and the OS task-completion handler. A backstop wake landing before the first unlock since
boot fails cleanly and converges at the next wake (the import's reads are caught; the adapters
distinguish unreadable from absent; nothing mints, clears, or leaves).

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
