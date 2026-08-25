## MODIFIED Requirements

### Requirement: Disabling the extension clears orphaned REQUESTED rows

The app SHALL recover the in-flight jobs wiped by a disable. Disabling the upload extension
(`setUploadJobExtensionEnabled(false)`) deletes the system's `AssetResourceUploadJobConfiguration` and
therefore **wipes every in-flight OS upload job**. Whenever
the app disables the extension **and this tier runs again afterwards**, it SHALL, immediately after the
disable, **both** (a) call the ledger's
`clearRequested()` (`sync-ledger`) to drop the now-orphaned `REQUESTED` rows, and (b) **reset the
discovery cursor** (clear the App-Group change-token) so the next cycle does a **full re-enumeration**.
Both are required: `clearRequested()` only makes the keys *absent*, but a settled cursor scans
incrementally and would never re-surface them — so without the cursor reset the cleared photos are
re-discovered only when the library next changes. This SHALL apply to the disable half of the
`disable→enable` re-register, and to the leave use-case's extension-disable.

The repair SHALL NOT run when the disable is a **switch to the app-driven tier**. That tier reconciles
stranded `REQUESTED` rows precisely from `getAllTasks` and, by its own contract, "SHALL NOT depend on
`clearRequested`" (`ios-url-session-upload`, "Precise in-flight reconciliation replaces blanket clear"),
so the blanket clear is redundant there **and blunter than the reconciliation that immediately follows
it**: `clearRequested()` is ledger-wide and the discovery cursor is shared, so running it would delete
in-flight rows belonging to the tier about to start and force it into a full re-enumeration it does not
need. The repair belongs to **re-registering** this tier — where no API can enumerate the vanished jobs —
not to every disable.

The disable-and-clear SHALL be **awaited off the main thread and completed before any re-enable**. The
`clearRequested()` write SHALL run on `Dispatchers.Default` (Kotlin/Native has no `Dispatchers.IO`),
never on the `Dispatchers.Main` scope — it is a synchronous SQLite `DELETE` that on the main thread is
a hang risk under cross-process WAL contention — and SHALL use a small bounded retry around the write.
The `disable→enable` re-register SHALL NOT call `setUploadJobExtensionEnabled(true)` until the clear
has completed, so the re-enabled extension's freshly recorded `REQUESTED` rows can never be deleted by
a still-running clear. The clear SHALL NOT be fire-and-forget. The bounded-retry, off-main clear is
pure logic and SHALL live in a tested `domain`/`capability` helper injected into both disable paths,
not in the untested app shell; only the sequencing of the two iOS platform calls remains in the shell.

Without `clearRequested()`, the rows stay `REQUESTED` forever: the engine treats `REQUESTED` as
in-flight and never re-issues it, there is no API to enumerate live jobs to detect that the job is
gone, and a same-event cycle never reconciles — so the photos that were mid-upload at the disable are
permanently abandoned. With both clears, the next full enumeration re-discovers the cleared keys and
re-creates exactly the not-yet-stored jobs (stored files remain `COMPLETED` and are skipped). The app
SHALL route both disable paths through a single helper so they cannot diverge, and SHALL use the
`LedgerStore` directly (constructing no `LedgerWriter`), since `clearRequested` is an app-side
reset-family operation.

#### Scenario: A re-register self-heals instead of orphaning

- **WHEN** photos are mid-upload (`REQUESTED` rows, OS jobs registered, the discovery cursor settled)
  and the app re-registers the extension (disable→enable)
- **THEN** the disable wipes the OS jobs, `clearRequested()` drops the `REQUESTED` rows, and the
  discovery cursor is reset — so the next cycle's full re-enumeration re-discovers and re-creates the
  not-yet-stored jobs (bytes resume landing), with no permanently-stuck `REQUESTED`

#### Scenario: The re-enable does not race the clear

- **WHEN** the app re-registers the extension (disable→enable)
- **THEN** `clearRequested()` runs off-main and completes **before** `setUploadJobExtensionEnabled(true)`
  is called, so no `REQUESTED` row recorded by the re-enabled extension is deleted by the clear

#### Scenario: The clear runs off the main thread

- **WHEN** a disable triggers `clearRequested()`
- **THEN** the SQLite delete executes on `Dispatchers.Default` (not the `Dispatchers.Main` scope) with
  a bounded retry, and is awaited rather than launched fire-and-forget

#### Scenario: A switch to the app-driven tier does not run the blanket repair

- **WHEN** the extension is disabled as part of a switch to the app-driven tier (a mechanism override, or
  a downgrade to a limited grant) while `REQUESTED` rows exist
- **THEN** the extension is deregistered, `clearRequested()` and the cursor reset do **not** run, and the
  app-driven tier's own `getAllTasks` reconciliation surfaces each stranded row as terminal `FAILED` so it
  is recreated — leaving rows whose transfers are still live untouched

#### Scenario: Leave clears REQUESTED

- **WHEN** the leave use-case disables the extension while resources are `REQUESTED`
- **THEN** `clearRequested()` runs as part of the disable, leaving no orphaned `REQUESTED` rows behind

#### Scenario: Completed rows survive the clear

- **WHEN** a disable triggers `clearRequested()` and the ledger holds `COMPLETED` rows for
  already-stored files
- **THEN** those `COMPLETED` rows are retained, so a subsequent reconcile/discovery does not re-upload
  already-stored bytes
