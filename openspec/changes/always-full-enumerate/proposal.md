## Why

The upload walk resumes from a persisted change-token cursor. Its own KDoc calls that persistence *"an
efficiency optimization only: a cold start with no stored token re-enumerates the whole library, which
the ledger makes harmless"*. Yet the optimization carries a durable App-Group key pinned by
`RuntimeIdentityTest`, a port plus its iOS store and fake, a clear effect threaded through three callers
(the re-join reconciler, `ReconfigureEvent`, `ResetDeviceState`), and a whole absence protocol. That
protocol exists only because the change feed reports a deletion once, as an event: `removedAssetIds` feeds
`markAbsent`, and `markPresent` undoes it, with an ordering rule between the two.

That protocol also leaves two holes. When the feed misses a deletion because its token expired, the photo
stays in the device manifest for the event's remaining life. The spec says so outright: *"there SHALL be no
reconcile backstop"*. And the resolve-failure path in `enqueue` selects rows **by key** but marks them
**by asset**, so under a partial grant a de-selected Live Photo's `COMPLETED` primary row is marked absent
together with its still-`DISCOVERED` paired video. That breaks `limited-photo-access`'s own rule that
*"deselection is not withdrawal"*. Today the damage can be undone, because a mark is reversible. Nothing
records the mismatch as a defect.

This is phase 1 of a seven-phase simplification of the upload path. Removing the cursor comes first because
later phases delete state that exists only to serve an incremental walk.

## What Changes

- **The discovery cursor is removed.** `DiscoveryStore`, `IosDiscoveryStore`, the in-memory fake, token
  archiving in `IosDiscovery`, `Discovery.nextToken` / `removedAssetIds`, the `sinceToken` parameter,
  `AppPorts.clearDiscoveryCursor`, and the cursor-clear step in `UploadReconciler`, `ReconfigureEvent` and
  `ResetDeviceState` all go. **Every upload walk is a full enumeration**, still narrowed at the platform
  fetch by the membership's capture range (capability `photo-selection-policy`).
- **Deletion becomes a presence diff over an authoritative walk.** After a walk, the cycle deletes the
  ledger rows of assets the walk did not return, but only when **both** conditions hold:
  - **The walk is authoritative.** `Discovery.fullEnumeration` is true, which means full grant and a
    readable library. A selection snapshot deletes nothing, and neither does an unreadable walk. The flag
    **survives** for exactly this purpose; today it has no production reader.
  - **The row is inside the walk's window.** The membership's policy admits the row by the same
    `admittedAssetIds` derivation the manifest and enqueue already use. The ledger is device-global and the
    walk is event-bounded, so an out-of-window row is no evidence either way. A bare row (empty
    `creationDate`) is never admitted, so it is never deleted.

  The diff never deletes a `REQUESTED` row: a live platform job owns it until its terminal write lands.
- **The resolve-failure path deletes by key, not by asset.** A ledger key that no longer resolves to a
  resource loses exactly that row. This removes the select-by-key/mutate-by-asset mismatch rather than
  gating around it.
- **The absence mark is no longer written, and existing marks are cleared once.** `markAbsent` and
  `markPresent` are removed from the ledger seam. The `absent` column **stays** in the schema, unwritten;
  phase 2 drops it together with `attempt` and `eventId`, so the downgrade boundary is crossed once. An
  idempotent sweep clears marks written by earlier builds. Four reads filter `absent = 0` (`selectNeedingJob`,
  `selectManifestRows`, `aggregates`, `selectPending`), so without the sweep a previously marked row could
  never be reached again.
- **A full walk reads resources only for assets the ledger does not yet fully know.** Today a walk reads
  resources for every admitted asset it returns (one synchronous PhotoKit round-trip each). Under an
  incremental walk that set was small; under a full walk it is the member's whole in-window library, on
  every cycle. An admitted asset whose rows all exist and are enriched is skipped. To keep the skip safe,
  the cycle records an asset's resources atomically, so a process death cannot leave the asset partly
  recorded and never re-read.
- **BREAKING (behavior):** a photo deleted and then restored within iOS's 30-day recovery window now
  re-uploads under the same key. The row that suppressed the re-upload is gone. The backend re-stores the
  role idempotently and wakes nobody, because it asks whether the write completes an asset before recording
  it (`api/src/db.ts`, `eventsCompletedBy`). This is the accepted cost of storing presence rather than
  remembering absence.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `sync-ledger`: `markAbsent`/`markPresent` are replaced by a writer-only key-scoped delete. Deletion is a
  presence diff over an authoritative, in-window walk. The ledger is still never pruned **by the selection
  policy**. The absence mark is swept once and no longer written. Cursor rationale is removed from the
  requested-state-reset, lifecycle and work-source requirements.
- `ios-photokit-upload`: "In-extension discovery via persistent change token" and "Persisted change-token
  cursor" are replaced by full-enumeration discovery. The deletion requirement changes from a change-feed
  mark to a presence diff. Cursor mentions are removed from registration, cap-aware creation, re-provision
  and the demote requirement.
- `ios-url-session-upload`: the tier's walk is a full enumeration. Cursor language is removed from the
  silent-push, lifecycle, unreadable-membership and ledger top-up requirements.
- `limited-photo-access`: a selection-scoped discovery reports no authoritative enumeration, so it drives
  no deletion. The "preserve the walk cursor" clause goes because there is no cursor to preserve.
- `device-manifest`: deletion-awareness comes from the presence diff, not an absence mark. The "no
  reconcile backstop" statement is inverted. The backfill is no longer a precondition of a cursor advance.
- `reconfigure-membership`: lowering the cutoff still re-shares newly in-scope photos on the next cycle.
  The mechanism named is now "every walk is a full enumeration at the current cutoff", not "invalidate the
  cursor".
- `upload-state-reconciliation`: the re-join reset-and-seed no longer clears a cursor. Every
  "ledger, cursor and marker untouched" clause loses the cursor.
- `device-state-reset`: the reset voids the ledger, config and downloads; there is no cursor to clear.
- `architecture-guards`: `discovery.changeToken` leaves the pinned runtime identity inventory and the
  data-protection rationale.
- `harness-world-model`: the world's token-delta discovery feed becomes a full-enumeration feed, with an
  operator lever for an unreadable walk replacing "expire token".
- `full-stack-harness`: the "Expire change token" action is removed.
- `upload-lifecycle`, `event-link`, `leave-event`, `device-identity`, `gallery-status`,
  `photo-selection-policy`, `module-architecture`, `upload-completion-notify`: requirement text that names the discovery cursor or
  `DiscoveryStore` (as untouched state, as a precondition, or as an example) is restated without it.
  Behavior does not change in these capabilities; the text would otherwise describe state that no longer
  exists.

## Impact

- **Code:** about 25 Kotlin files. `:domain` (`ports/` `DiscoveryStore` + `UploadDiscovery` + `LedgerStore`;
  `feature/upload` `UploadCycle`, `SelectionScopedDiscovery`, `LedgerWriter`, `Reconciler`;
  `feature/membership` `ReconfigureEvent`, `ResetDeviceState`; `compose/` `UploadCore`, `SnapSyncApp`),
  `:adapter:ios:ext-safe` (`IosDiscovery`, `IosDiscoveryStore`, `IosLedgerStore`'s token key),
  `:adapter:generic:app` (`SqlDelightLedgerStore`, `Ledger.sq`), `:adapter:generic:fake`, `:app:ios` and
  `:app:ios:extension` roots, `:test:world`, `:test:integration`, and `:test:architecture`
  (`RuntimeIdentityTest`, `CompositionSeamTest`). Generated `architecture/` diagrams change with the port
  set.
- **Schema:** none. No `.sqm` is added. The mark sweep is an idempotent per-cycle `UPDATE` beside the
  existing `backfillEventId` sweep, so a revert is a Kotlin-only revert.
- **Runtime identity:** the App-Group `NSUserDefaults` key `discovery.changeToken` is no longer read or
  written. A stale value is left in place. A reverted build reads it and resumes, or falls back to a full
  enumeration if the token has expired, which is harmless by the same ledger argument as today.
- **Performance:** a full enumeration plus facts costs 7–388 ms on the SE2 (measured in
  `archive/2026-08-27-fix-cap-truncation-loop` and `archive/2026-09-09-bound-enqueue-to-free-slots`). Resource
  reads were **not** part of those figures. The skip above keeps them to assets the ledger does not yet
  know. The older-device-under-import case (SNAPSYNC-16, iPhone11,2 / iOS 18.7.9) remains unmeasured.
- **Out of scope:** the other six phases. The upload lifecycle, the arm, the `joinedEventId` marker, the
  enqueue bound and the `absent` column's removal are untouched.
