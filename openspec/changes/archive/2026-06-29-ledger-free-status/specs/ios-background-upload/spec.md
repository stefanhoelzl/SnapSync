## REMOVED Requirements

### Requirement: App reads succeeded upload jobs (read-only observation)

**Reason**: This existed to feed the `observed-completion-overlay`, which is removed — the app now reads
completion from storage (the completeness listing), not from the platform's succeeded upload jobs.
**Migration**: Delete the `:app:ios` `ObservedCompletionsSource` job-reading implementation; status
derives `completed` from `CompletedAssetsSource` (`sync-status`).

### Requirement: Extension posts the cross-process ledger ding once per cycle

**Reason**: The app no longer watches the ledger across processes, so there is no consumer for a
cross-process ding; the ledger is the extension's private upload memory.
**Migration**: Remove the end-of-cycle Darwin notification post; the in-process `changes` ding on `put`
remains (see `sync-ledger`). App status freshness comes from re-LISTing on foreground entry and on
manifest `URLSession` completion (`sync-status`).
