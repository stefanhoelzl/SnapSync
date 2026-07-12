## MODIFIED Requirements

### Requirement: App-driven lifecycle

On iOS 18–26.0 the enable / disable / re-provision / leave lifecycle SHALL be performed by the app
in-process and ordered, with **no** `setUploadJobExtensionEnabled` toggle:

- **enable** (full photo-access grant): run a cycle immediately (foreground) and schedule the first
  `BGProcessingTask`.
- **disable** (access revoked): cancel all in-flight upload **tasks**, delete their staged temp files, and
  cancel the scheduled task. The background `URLSession` itself SHALL be left intact — see "Cancellation
  never invalidates the background session" below.
- **re-provision** (valid `snapsync://` rescan): cancel in-flight tasks for the old event and delete
  their staged temp files, update the `eventId`, reconcile against storage (seed already-stored
  resources as `COMPLETED` via `:capability:membership`), then run a cycle — all ordered in one process,
  with no disable→enable toggle and no cross-process race.
- **leave**: cancel all in-flight tasks, cancel the scheduled task, delete staged temp files, then
  clear the ledger and discovery cursor and forget the `eventId` (the platform-neutral leave).

#### Scenario: Re-provision is an in-process ordered sequence
- **WHEN** a new valid `snapsync://` config is scanned on iOS 18–26.0
- **THEN** the app cancels old-event tasks, updates the event, reconciles already-stored resources to `COMPLETED`, and runs a fresh cycle — with no OS toggle and no cross-process timing hazard

#### Scenario: Leave cancels transfers and wipes local state
- **WHEN** the user leaves the event on iOS 18–26.0
- **THEN** in-flight tasks and the scheduled `BGProcessingTask` are cancelled, staged temp files are deleted, and the ledger, discovery cursor, and stored `eventId` are cleared

#### Scenario: Disable cancels tasks without destroying the session
- **WHEN** photo access is revoked on iOS 18–26.0
- **THEN** the in-flight upload tasks and the scheduled `BGProcessingTask` are cancelled and staged temp files deleted, while the background `URLSession` remains valid — so a later re-grant can run a cycle without rebuilding it

## ADDED Requirements

### Requirement: Cancellation never invalidates the background session

Every lifecycle verb that stops transfers SHALL cancel the individual `URLSession` **tasks**, and none of
them — **disable**, **re-provision**, **leave**, or **switch** — SHALL invalidate the background
`URLSession`.

A background `URLSession` is a process-lifetime singleton. Invalidation is **terminal**: creating a task on
an invalidated session throws an Objective-C `NSException`, which Kotlin/Native cannot catch and which
aborts the process. Because every one of these verbs is followed by a later upload — a re-grant after
disable, a fresh cycle after re-provision, a new event after switch — a session destroyed as a means of
cancelling is a crash awaiting the next cycle. Invalidation is reserved for process teardown or for
deliberately discarding a session to rotate its identifier, and is used for neither here. The session
identifier SHALL remain stable so `handleEventsForBackgroundURLSession` can re-adopt it across launches.

This requirement records the rule the tier already implements, and removes the previous instruction to
"invalidate/cancel the background `URLSession`" on disable — which, if implemented literally, would abort
the app on the next upload after a revoke→re-grant. The same rule governs the download client
(`photo-download`), where following that instruction did abort the app in production.

#### Scenario: Re-grant after a disable uploads without a crash

- **WHEN** photo access is revoked (cancelling transfers) and later granted again, and a cycle runs
- **THEN** upload tasks are created on the still-valid background session and the app does not abort

#### Scenario: No lifecycle verb invalidates the session

- **WHEN** disable, re-provision, leave, or switch stops in-flight transfers
- **THEN** each cancels the individual upload tasks and the background `URLSession` remains valid and
  reusable
