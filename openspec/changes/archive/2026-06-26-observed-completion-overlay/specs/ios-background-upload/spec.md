## ADDED Requirements

### Requirement: App reads succeeded upload jobs (read-only observation)

The `:app:ios` module SHALL provide the iOS `ObservedCompletionsSource` by reading the system's upload
jobs from the **app** process via `PHAssetResourceUploadJob.fetchJobsWithAction(.acknowledge)`,
keeping the jobs whose state is `succeeded`, and mapping each to its ledger key via the destination
request URL's last path segment (the same key mapping the extension uses; the only field reliably
present for every job state). This read SHALL be **strictly read-only**: it SHALL NOT call
`acknowledge`, `retry`, or any change request, so it never consumes a job the extension must still
acknowledge — the extension remains the single ledger writer. The read SHALL be guarded by the same
iOS-version check as the extension registration, returning the empty set where the background-upload
API is unavailable. As a device-only PhotoKit binding it lives in the untested `:app:ios` shell; the
key mapping it relies on is exercised by the extension's existing logic.

#### Scenario: Succeeded jobs map to observed keys

- **WHEN** the app process refreshes the source and the system holds two `succeeded`, unacknowledged
  jobs
- **THEN** the source's set contains exactly those two jobs' keys (each the destination URL's last
  path segment), and no `acknowledge`/`retry` is performed

#### Scenario: Unavailable API yields the empty set

- **WHEN** the background-upload API is unavailable on the running OS
- **THEN** the source yields the empty set and performs no PhotoKit job call

### Requirement: Extension posts the cross-process ledger ding once per cycle

The extension SHALL post the cross-process ledger notification **once**, after its `process()` cycle
completes, rather than per `put`. The App-Group backend SHALL NOT post the Darwin notification on each
`put`. A cycle that performs no `put` MAY still post (a redundant ding is harmless); a crash before
the post defers the app's update to its next trigger (foreground re-read or poll), which is safe
because the ledger is durable and dings are level-triggered.

#### Scenario: One ding per cycle regardless of write count

- **WHEN** a `process()` cycle records several rows and then returns
- **THEN** the app process receives a single cross-process ding for that cycle, not one per recorded
  row
