## REMOVED Requirements

### Requirement: Observed-completions seam

**Reason**: Completeness is now read from storage (the Change-1 completeness listing), so the
platform-observed "succeeded but not yet ledgered" key set is no longer needed to make status live.
**Migration**: The app derives `completed` from `GET /event/<id>/files` via the new
`CompletedAssetsSource` (`sync-status`); the iOS `ObservedCompletionsSource` job-reading implementation
and its no-op are deleted.

### Requirement: Overlay promotion

**Reason**: The status projection no longer combines a ledger snapshot with an observed set; an asset is
complete iff the listing reports it complete, so there is nothing to promote.
**Migration**: `SyncProgress.completed` is the completeness-listing count directly; remove the overlay
computation.

### Requirement: Sticky retention

**Reason**: Stickiness existed to stop a photo blinking backward when an observed key was released before
the ledger ding arrived. With completion read from storage there is no observed set and no ledger ding,
so there is nothing to retain.
**Migration**: None — the backward-blink failure mode cannot occur when `completed` comes from the
immutable, monotonic completeness listing.

### Requirement: Foreground-and-pending refresh cadence

**Reason**: The overlay's bounded foreground polling of `ObservedCompletionsSource.refresh()` is replaced
by event-driven refreshes of the completeness listing (foreground entry and manifest `URLSession`
completion).
**Migration**: `CompletedAssetsSource` refreshes on foreground entry and on each manifest upload
completion; there is no pending-gated polling interval (see `ledger-free-status` design Open Questions).
