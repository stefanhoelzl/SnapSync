## Why

The upload ledger is device-global and never cleared, so a short-lived upload cycle has to *detect* a
membership change nobody announced. That is the only job of the `joinedEventId` marker, `UploadReconciler`
and the cycle's `reconcile` port. Its justification was a delete-and-reinstall "which no provisioning path
observes". That no longer holds: `FileBackedConfigStore` is file-only and the App Group dies with the
install, so a reinstall leaves no membership, and rejoining means scanning a QR. Every route that changes
the membership — a join, a switch, a leave — is now an explicit app action. The ledger can therefore be
**the current event's share set**: a join loads it and a leave clears it, with no detection machinery at
all.

## What Changes

- **BREAKING (contract reversal) — a leave clears the upload ledger.** `LeaveEvent` stops uploads first,
  then clears the ledger, then clears the config. This reverses `sync-ledger`'s "Lifecycle transitions never
  clear the ledger" and `upload-lifecycle`'s "Leaving stops without wiping". Only the **upload** ledger is
  cleared: the download store's handle-carrying rows stay permanent (`download-store`), because they are what
  stops the device uploading its own imports back into an event.
- **A switch clears too.** A switch is a leave then a join: `flow/Provision` stops uploads first, and the
  join's clear-then-load replaces the ledger. So the reversal covers a **provision** as well as a leave.
- **A join clears, then loads the ledger** from the per-device listing
  (`GET /api/v2/files/devices/<deviceId>`): each resource the backend already holds becomes a `COMPLETED` row,
  so nothing already stored re-uploads. On success that is one atomic `resetTo(listing)`; on failure it is
  `clear()`. Either way, a new membership starts with nothing from before it. The clear is what stops a
  leftover `COMPLETED` row from suppressing a needed upload: such a row survives on a device that left under
  the old contract, or after a leave whose best-effort clear failed. The load runs once, in the app, between
  saving the config and arming the upload mechanism, and only when the membership actually changes (a first
  join or a switch, never a re-provision of the joined event).
- **A failed listing fetch blocks nothing.** No flag, no gate, no retry state. The join completes and
  uploading proceeds. **The stated cost:** if the load fails, the device re-uploads what the backend already
  holds — idempotent overwrites of the same objects (the destination is `(deviceId, assetId, role)`), bounded
  by the event window.
- **Status counts only what the membership admits.** `completed` and `pending` are counted over the same
  admitted asset set as the total `N`, instead of over every ledger row. The loaded ledger holds everything
  this device ever uploaded, for any event. Today the same whole-listing seed plus a clamp can read "in sync"
  while in-window photos are still pending. That fixes an existing masking bug as well as the new exposure.
- **Album: a second placement moment.** When the cycle fills in a bare (listing-seeded) row's detail and the
  asset is admitted, it places that asset in the event album. Without this, photos loaded at join miss the
  album: the gather at provision runs before any walk has dated them. On iOS ≥26.1 the walk runs in the
  extension, so the app cannot order the gather after it.
- **Deleted:** the `JoinedEventMarker` port, `IosJoinedEventMarker`, its fake, the `rejoin.joinedEventId`
  runtime-identity pin, `UploadReconciler`, the cycle's `reconcile` port and its `SeedDeferred` outcome, the
  not-joined branch's leave-side reconcile, and **`UploadLedgerAudit`** (the read-only foreground check). The
  audit reads the marker, and it has been subtly wrong since the walk started deleting departed rows.
- **One-time clean-up:** the orphaned `rejoin.joinedEventId` key is removed from App-Group `NSUserDefaults`
  once. That makes a revert of this change clean (see design).
- **Not changed:** phase 1's authoritative-walk gate (`Discovery.fullEnumeration`) stays; the download store
  is untouched; the widening reconfigure does **not** re-fetch the listing (the join already loaded
  everything the backend holds for this device).

**Named future direction.** Concurrent multi-event membership is a named future (`openspec/config.yaml`). A
ledger scoped to the current event deepens the single-membership assumption. The ledger *key* stays
event-independent, and the byte store stays device-partitioned. A multi-membership future would need per-event
ledgers or a membership column, and this change makes that a larger step than it was.

## Capabilities

### New Capabilities

_None._

### Modified Capabilities

- `upload-lifecycle`: leave stops then clears; a switch is leave + join and clears too; the cycle has no
  reconcile port, no seed deferral, and no leave-side reconcile on the not-joined path.
- `sync-ledger`: "Lifecycle transitions never clear the ledger" is reversed (leave, switch and join clear);
  the ledger is the current membership's share set; `resetTo` is invoked by the join-time load, not by an
  in-cycle reconciliation; a per-asset progress read (`assetProgress()`) is added beside `aggregates()`.
- `upload-state-reconciliation`: the marker-gated in-cycle reconciliation and the read-only foreground check
  are removed; what remains is the per-device listing seam and a join-time load.
- `sync-status`: `completed`/`pending` are counted over the admitted asset set that `N` counts.
- `event-album`: a second own-photo placement moment, when a bare row's detail is filled.
- `leave-event`: the teardown order becomes stop → clear the upload ledger → clear config → notify.
- `event-link`: the join-marker scenarios go; the unreadable-config rule is re-stated without the marker.
- `join-event`: provision loads the ledger from the listing, and a switch clears first.
- `ios-app-shell`: the extension no longer self-reconciles; the app writes the ledger's reset family at join,
  switch and leave on every tier; the orphaned marker key is cleared once.
- `device-state-reset`: the "not just leave harder" rationale is rewritten; the remaining difference is no
  backend notify.
- `harness-world-model`: `World.provision()` loads the ledger as a join does; `World.leave()` clears it; the
  world no longer composes a reconciler or a marker.
- `full-stack-harness`: its re-join scenarios describe the join-time load instead of an in-cycle reconcile.
- `architecture-guards`: the `rejoin.joinedEventId` runtime-identity pin is retired.
- `device-manifest`: seeded rows come from the join-time load, not a re-join reconciliation; the "deferred
  reconciliation writes no manifest" rule goes with the deferral.
- `gallery-status`: the gallery source publishes the admitted own-asset set beside `N`; the shared
  key→`assetId` recovery loses its reconciler caller.
- `ios-url-session-upload`: the app-driven tier's join and leave scenarios lose the marker and the in-cycle
  seed.
- `ios-photokit-upload`: the extension root holds no leave-side reconciliation and no marker.

## Impact

- **Code (production):** `:domain` ports (`JoinedEventMarker` deleted), `feature/upload` (`Reconciler.kt`,
  `UploadLedgerAudit.kt` deleted; `UploadCycle`, `UploadForeground` changed), `feature/membership`
  (`LeaveEvent`, `ResetDeviceState` KDoc, a new join-time ledger load), `feature/status` (counts over the
  admitted set), `flow/Provision` (switch clear + load effects), `compose/` (`UploadCore`, `UploadRecordPorts`,
  `SnapSyncApp`); `:adapter:ios:ext-safe` (`IosJoinedEventMarker` deleted, `IosLedgerStore` key cleanup);
  `:adapter:generic:fake`; `:app:ios` (`SnapSyncRoot`, `UrlSessionUploadController`), `:app:ios:extension`
  (`UploadExtensionRoot`).
- **Tests:** `ReconcilerTest`, `UploadLedgerAuditTest` and `IosJoinedEventMarkerTest` are deleted.
  `UploadCycleTest`, `CycleGateTest`, `CycleEntryGateIntegrationTest` and `RuntimeIdentityTest` change. The
  `:test:world` `provision`/`leave` operators change.
- **Architecture diagrams:** `Provision` transcribes into `architecture/flows/`, so they regenerate.
- **No schema change, no backend change.** The listing route already exists and is unchanged.
- **Every merge uploads a TestFlight build.** There is no staged ship: an already-joined device is not stuck
  behind anything. It keeps its intact, correct ledger until it next leaves or switches.
