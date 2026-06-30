## ADDED Requirements

### Requirement: Disabling the extension clears orphaned REQUESTED rows

Disabling the upload extension (`setUploadJobExtensionEnabled(false)`) deletes the system's
`AssetResourceUploadJobConfiguration` and therefore **wipes every in-flight OS upload job**. Whenever
the app disables the extension it SHALL, immediately after the disable, call the ledger's
`clearRequested()` (`sync-ledger`) to drop the now-orphaned `REQUESTED` rows. This SHALL apply to
**both** disable paths: the disable half of the `disable→enable` re-register, and the leave use-case's
extension-disable.

Without it, those rows stay `REQUESTED` forever: the engine treats `REQUESTED` as in-flight and never
re-issues it, there is no API to enumerate live jobs to detect that the job is gone, and a same-event
cycle never reconciles — so the photos that were mid-upload at the disable are permanently abandoned.
After the clear, the next discovery re-creates exactly the not-yet-stored jobs (stored files remain
`COMPLETED` and are skipped). The app SHALL route both disable paths through a single helper so they
cannot diverge, and SHALL use the `LedgerBackend` directly (constructing no `LedgerWriter`), since
`clearRequested` is an app-side reset-family operation.

#### Scenario: A re-register self-heals instead of orphaning

- **WHEN** photos are mid-upload (`REQUESTED` rows, OS jobs registered) and the app re-registers the
  extension (disable→enable)
- **THEN** the disable wipes the OS jobs **and** `clearRequested()` drops the `REQUESTED` rows, so the
  next discovery re-creates the not-yet-stored jobs — no permanently-stuck `REQUESTED`

#### Scenario: Leave clears REQUESTED

- **WHEN** the leave use-case disables the extension while resources are `REQUESTED`
- **THEN** `clearRequested()` runs as part of the disable, leaving no orphaned `REQUESTED` rows behind

#### Scenario: Completed rows survive the clear

- **WHEN** a disable triggers `clearRequested()` and the ledger holds `COMPLETED` rows for
  already-stored files
- **THEN** those `COMPLETED` rows are retained, so a subsequent reconcile/discovery does not re-upload
  already-stored bytes
