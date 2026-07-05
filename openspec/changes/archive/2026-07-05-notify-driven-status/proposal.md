## Why

The status screen only refreshes on foreground entry, so while the app is open the sync state is
frozen: uploads that start, progress, and finish in the extension are invisible until the user
backgrounds and reopens. This is the documented **known trade-off** of `ledger-free-status` ("if the
app is backgrounded when an asset's last resource lands … the completed count goes stale until the
next foreground re-LIST"). We want the status line to move live — the arrow to pulse while work is in
flight, and to settle to "In sync" the moment a backup drains — without polling.

The upstream constraint that makes this cheap: **storage is never reset while an event is active**, so
the extension's private ledger cannot over-count during an active event — every `COMPLETED` maps to a
durable object, and the only ledger↔storage divergence point (re)join is already reconciled by
`event-rejoin-reconciliation`. Under that invariant the ledger *is* the single source of sync truth,
and status can read it directly — locally, with no network — instead of re-deriving completeness from
a parallel storage LIST.

## What Changes

- **Extension → app liveness ding.** The upload extension posts a **cross-process Darwin notification
  at the end of every `process()` run** (unconditionally, at the composition-root level — the
  `LedgerBackend` still posts nothing). The main app observes it **foreground-only** and re-reads
  status. The **app-driven tier** (iOS 18–26), whose pump runs in-process, triggers the same refresh
  **in-process** after each pump cycle (no Darwin needed).
- **Upload status is sourced from the ledger.** `SyncProgress.completed` is re-sourced from the
  ledger's asset-counted `aggregates().completed` (read in the same round-trip as `pending`), and
  **drives classification**. `total` stays the gallery count. This ends the current split
  (pending-from-ledger, completed-from-storage) — both counts now come from one place, the extension's
  ledger, read read-only cross-process.
- **BREAKING (removal):** the app-side upload-completeness LIST is removed from the status path —
  `CompletedAssetsSource` and its `GET /devices/<id>/files` expected×present join no longer feed
  upload status. This is a **deliberate, documented reversal** of `ledger-free-status`'s
  upload-completeness decision, justified by the no-deletion invariant + single-source-of-truth; the
  safety rationale that motivated storage-truth classification is retired by the invariant.
- **Status-line copy split.** The single `"Syncing…"` label splits by activity:
  `"Synchronization pending…"` when work remains but nothing is in flight (any arrow static) and
  `"Synchronization ongoing…"` when work is in flight (any arrow pulsing). `"In sync"` (settled) is
  unchanged.
- **Status-line colors.** The static arrow renders **gray**, the pulsing arrow renders **primary**
  (keeping the fade), and the existing LED **green → primary** — the app unifies on the brand accent.
- **Downloads are unchanged.** Foreign-object completeness still comes from the download reconcile,
  driven by the existing remote APNs silent push (`notify-driven-download`) + foreground. This change
  is upload-side only.

## Capabilities

### New Capabilities
<!-- none — all changes modify existing capabilities -->

### Modified Capabilities
- `sync-status`: `SyncProgress.completed` is re-sourced from the ledger's asset-counted
  `aggregates().completed` and drives classification; the listing-backed `CompletedAssetsSource` /
  per-device LIST is dropped from the status path; the injected ledger read yields **both** completed
  and pending in one call; liveness gains the extension Darwin ding and the app-driven in-process
  refresh alongside foreground entry.
- `sync-status-screen`: the `Syncing` status-line label splits into "Synchronization pending…" (static)
  and "Synchronization ongoing…" (pulsing); the arrow shown/pulse derivation is otherwise unchanged.
- `design-system`: the status-line arrow skin maps static → gray and pulsing → primary; the LED
  indicator green is remapped to primary.
- `ios-photokit-upload`: the extension posts the cross-process Darwin liveness notification after each
  `cycle.run()`.
- `ios-url-session-upload`: the app-driven pump triggers the in-process status refresh after each pump
  cycle.
- `ios-app-shell`: the app registers a foreground-only Darwin observer that re-reads the ledger
  aggregates and re-emits status.

## Impact

- **Modules:** `:domain:status` (re-source `completed`; merge the aggregate read into one injected
  `suspend` read; drop the `CompletedAssetsSource` input — `:domain:status` stays engine-free at
  compile time via the injected read); `:domain:ui:components` (label split + arrow/LED colors);
  `:capability:upload` / `:app:ios:photokit-extension` (extension posts the ding after the cycle);
  `:app:ios:url-session-upload` (pump → in-process refresh); `:app:ios` (Darwin observer wiring +
  supply the `aggregates()` read for completed).
- **Removed from the status path:** the own-device upload-completeness LIST (`OwnDeviceCompletedAssetsSource`
  / `GET /devices/<id>/files`) and its expected×present join — a partial revert of `ledger-free-status`
  + `dedup-files-device-manifests` for the upload direction only (the download-side listing and the
  manifest infrastructure are untouched).
- **Docs:** `docs/design.md §2.3` ("No ledger Darwin notification") and `§2.4` ("classification stays
  storage-truth") are rewritten around the invariant — a cross-process Darwin liveness ding now exists
  and classification reads the ledger, safe under no-deletion-during-active-event + join reconciliation.
- **Load-bearing premise, documented explicitly:** storage is never reset/pruned while an event is
  active; the ledger cannot over-count during an active event; (re)join is the sole divergence point
  and is reconciled by `event-rejoin-reconciliation`.
- **Safety trade-off accepted:** "In sync" now trusts the ledger rather than verifying against the
  destination store on every read; a silent partial storage loss (excluded by the invariant) would not
  be caught until the next (re)join reconciliation.
