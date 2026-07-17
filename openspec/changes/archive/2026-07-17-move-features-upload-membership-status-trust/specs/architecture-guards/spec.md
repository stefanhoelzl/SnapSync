# architecture-guards — delta for move-features-upload-membership-status-trust

## REMOVED Requirements

### Requirement: The status module never names an engine type

**Reason**: Superseded by the armed feature-blindness zone gate. Migration step 5 moves the
status projections (`SyncStatusSource`, `LedgerBackedSyncStatusSource`, `LedgerCountsSource`,
`OwnDeviceGalleryStatusSource`) out of `:domain:status` into `:domain`'s `feature/status` zone,
where the feature-blindness gate holds a strictly stronger form of the same invariant: a
`feature/status` file may reference only `model/`, `ports/`, and itself — so `app.snapsync.engine`
(a legacy package), the ledger trio's new homes (`feature/upload`'s `SyncEngine`/`LedgerWriter`,
`ports/`' `LedgerStore` — reachable only as a port, which the projections do not take), and every
other sibling feature are all violations by source-text match, fully-qualified references
included, with the zone gate's own non-vacuity contract (an existing-but-empty zone directory
fails). The Konsist guard could not survive the move regardless: its non-vacuity twin pins
`:domain:status`'s production scope at more than three files with `LedgerBackedSyncStatusSource`
present, which the emptied module fails — leaving it in place turns `build` red on this step, as
the migration plan recorded.

**Migration**: none for consumers — the guard was a test, not an API. The residual
`:domain:status` module (the download read-model seam, until step 6) is covered by the ordinary
compile boundary; the moved sources are covered by the zone gates.
