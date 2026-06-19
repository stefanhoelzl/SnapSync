## MODIFIED Requirements

### Requirement: Change signal
`LedgerBackend.changes` SHALL emit `Unit` after every successful `put`. A ding carries no payload
and promises nothing beyond "re-read the truth" — consumers MUST treat it as a level trigger
(conflation, duplicate dings, and signals missed while busy are all safe because every re-read
queries current state). Where the underlying store is written by another process, the backend SHALL
feed `changes` from a cross-process notification: the iOS App-Group backend SHALL post a Darwin
notification (a `CFNotificationCenter` darwin-notify name) after every successful `put` and SHALL
merge an observer of that notification into its `changes` flow, so a `put` performed by the
extension process dings a collector in the app process. The seam itself does not change.

#### Scenario: Put dings
- **WHEN** a collector is active on `changes` and `put` completes
- **THEN** the collector receives an emission

#### Scenario: Cross-process put dings the other process
- **WHEN** the extension process performs a `put` on the App-Group ledger and a collector in the app process is active on `changes`
- **THEN** the app-process collector receives an emission (via the Darwin notification) and re-reads current truth
