## Why

The "in progress" caption shows `total − completed` — every photo not yet fully stored. That is just
the inverse of the "n of N synced" hero (redundant), and it is **mislabeled**: it reads as "actively
uploading" when it really means "remaining". It cannot distinguish photos the OS is transferring
**right now** from photos the background extension has not even **discovered** yet. Storage truth
alone cannot tell those apart — a single-resource photo flips `0 → complete` atomically, so a
"partially present" signal is near-zero in practice.

The true in-flight set already lives in the extension's ledger: `aggregates().pending` is
**asset-counted** ("photos with any non-`COMPLETED` resource") = jobs created but not yet done. This
change has the app **read that count, read-only**, from the App-Group ledger (the driver runs in
**WAL** mode — one cross-process writer plus concurrent readers) and surface it as the in-flight
caption — a genuinely different, more useful number (e.g. "5 of 7 synced · 2 in progress", with the
remaining photos awaiting discovery).

It **partially reverses** the archived `ledger-free-status`: the app reads the ledger again — but
**narrowly**: read-only, the in-flight **count only**, and **display-only**. It does **not** drive
classification (state stays storage-truth: `synced` vs `total`), and the extension remains the **sole
writer** (no `LedgerWriter` is constructed in the app).

## What Changes

- **ADD `InFlightSource` seam** (`:domain:status`): `inFlight: StateFlow<Int>` + `suspend fun
  refresh()`, whose value is the ledger's asset-counted in-flight (`aggregates().pending`). The iOS
  implementation reads `iosLedgerBackend()` **read-only** (only ever `aggregates()`); a settable fake
  exists for tests and the desktop harness.
- **`SyncProgress.pending` is re-sourced** from the in-flight count, **clamped to remaining**:
  `pending = min(inFlight, max(0, total − completed))`. The clamp absorbs the ledger's transient
  over-count (a finished-but-not-yet-acked job still reads `REQUESTED`), so the caption is never
  greater than the remaining count. `pending` stays **display-only** — classification still ignores it.
- **`ListingSyncStatusSource`** gains a fourth combined input (the in-flight count); it now reads the
  ledger **only** for that number, never for completeness or classification.
- **Liveness:** `InFlightSource.refresh()` runs on **foreground entry**, alongside the existing
  completed-source refresh. There is no cross-process change notification (the ledger's `changes`
  flow is in-process), so the number is a foreground snapshot, not a live ticker.
- **App composition root** constructs the read-only ledger access and the `InFlightSource`, and
  refreshes it on foreground; the **desktop harness** can forge the in-flight number.
- Caption wording (`"{n} in progress"`, auto-hidden at 0) is unchanged.

## Impact

- Spec `sync-status`: **MODIFIED** *Listing-backed source* and *SyncProgress contract*; **ADDED**
  *InFlightSource seam*.
- `docs/design.md §2.4`: status classification + synced count remain storage-truth; the in-flight
  caption is now a **read-only ledger peek** (display-only). Records the narrow reversal of
  `ledger-free-status`.
- No backend, extension, or engine change. The app re-opens the App-Group ledger read-only (WAL).
- Stacks on `dedup-files-device-manifests` (the own-device storage-truth status it builds on).
