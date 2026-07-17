# ios-photokit-upload (delta)

## MODIFIED Requirements

### Requirement: Cap-aware creation and tri-state processing result

When `creationRequestForJob` raises `PHPhotosErrorLimitExceeded`, the extension SHALL stop creating
jobs for the remainder of the cycle, leave the change token un-advanced, and surface a **processing**
result so the system re-invokes it promptly; on the next wake, re-derivation plus the engine's
`REQUESTED`-skip resumes exactly the un-created remainder with no duplicate jobs and no persisted
residue list.

Because the OS invokes the extension lazily (on library changes, not when an upload quietly
finishes), a drained cycle that reported `completed` would leave already-succeeded jobs
un-acknowledged until the next change. Therefore, whenever the cycle would otherwise complete but the
ledger still has **pending** (in-flight) rows, the extension SHALL instead surface **processing** to
request another invocation so those completions are recorded promptly; it reports `completed` only
once the ledger has no pending rows (everything backed up), letting the system rest. (The OS
throttles re-invocation, so this polls at its cadence rather than looping.)

The Kotlin `process()` SHALL return its `CycleResult` (`completed` / `processing` / `skipped` /
`failed`), and the Swift principal class SHALL map **every case explicitly** to
`PHBackgroundResourceUploadProcessingResult`: `completed` and `skipped` (nothing to do) map to
`.completed`, `processing` to `.processing`, `failed` to `.failure`. A Kotlin enum reaches Swift as an
ObjC class, so the compiler cannot check exhaustiveness and mandates a `default:` arm; that arm SHALL
map to `.failure`, so a future Kotlin case nobody taught the shell surfaces as a retried, visible
failure — never a silently "successful" upload cycle (changed 2026-07-17; the arm previously returned
`.completed`). If the iOS 26.1 SDK lacks a `.processing` case the Swift shell SHALL fall back to
`.completed` (correctness is unaffected — the un-advanced token / pending rows are drained on the
next system-scheduled wake; only promptness is lost).

#### Scenario: Cap during discovery yields a processing result
- **WHEN** job creation hits `limitExceeded` partway through a cycle
- **THEN** the extension stops creating jobs, does not advance the token, and `process()` returns a
  processing result (mapped to `.processing`, or `.completed` if unavailable)

#### Scenario: Pending in-flight work requests re-invocation
- **WHEN** a cycle drains and creates with no cap, but the ledger still has pending (in-flight) rows
- **THEN** `process()` returns a processing result so the system re-invokes the extension to record
  their completions, rather than resting until the next library change

#### Scenario: Fully backed up reports completion
- **WHEN** a cycle ends with no pending rows in the ledger
- **THEN** `process()` returns `completed` and the system rests

#### Scenario: Re-entry resumes the remainder without duplicates
- **WHEN** a cap-truncated cycle is followed by another `process()` invocation
- **THEN** the same change set is re-derived, the already-created jobs are skipped (`REQUESTED`), and
  only the previously un-created resources get new jobs

#### Scenario: An unknown cycle result surfaces as failure
- **WHEN** `process()` returns a case the Swift shell's switch does not name
- **THEN** the shell reports `.failure`, so the system retries and the defect stays visible, rather than reporting a successful cycle that cannot be trusted
