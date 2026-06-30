## Why

Disabling the upload extension (`setUploadJobExtensionEnabled(false)`) deletes the system's
`AssetResourceUploadJobConfiguration`, which **wipes all in-flight OS upload jobs**. But the ledger
keeps those resources as `REQUESTED`. Three facts make that permanent:

- the engine treats `REQUESTED` as "in flight" and **never re-issues it** (only `FAILED`/absent yields
  `Work`);
- there is **no PhotoKit API to enumerate live jobs**, so the extension cannot detect that a
  `REQUESTED` row's job no longer exists;
- a same-event cycle does **not** reconcile (only a marker-mismatch `resetTo` does).

So every photo that was mid-upload when the app disabled the extension is **permanently abandoned** —
stuck `REQUESTED`, never re-created, never uploaded. Because the app re-registers (disable→enable) on
routine cold launches, a user simply reopening the app during a backup can strand photos forever. This
was reproduced on device: after a disable, the log shows `7 pending · discovered 0` looping with zero
bytes ever transferred.

## What Changes

- **ADD `LedgerBackend.clearRequested()`** — an **app-side reset-family** bulk delete of every
  `REQUESTED` row, in the same family as `clear()`/`resetTo()` (not a writer-only prune). It keeps
  `COMPLETED` rows (so cross-event dedup survives) and `FAILED` rows (the engine already retries
  those). It emits exactly one `changes` signal; on the SQLDelight backend it is a `DELETE … WHERE
  state = 'REQUESTED'`. Covered by the shared `LedgerBackendContract` (JVM **and** native sim).
- **Clear `REQUESTED` immediately after every extension disable**, and **reset the discovery cursor**
  so the next cycle does a full re-enumeration (clearing `REQUESTED` only makes the keys *absent*; a
  settled cursor would never re-surface them). The app folds both into a single `disableExtension()`
  helper used by **both** disable sites — the re-register's disable half and `LeaveEvent`'s disable
  lambda — so the paths cannot diverge. It reuses the `LedgerBackend` the app already opens (for the
  in-flight read).

Effect: every disable wipes the OS jobs **and** clears the now-orphaned `REQUESTED`, so the next
discovery re-creates exactly the not-yet-stored jobs. A re-register **self-heals** instead of
orphaning; clearing all `REQUESTED` is correct precisely because a disable wipes *all* in-flight jobs
(nothing genuinely in flight remains to double-upload).

## Impact

- Spec `sync-ledger`: **ADDED** the `clearRequested` reset-family operation (and its SQLDelight
  contract). Spec `ios-background-upload`: **ADDED** "disabling the extension clears orphaned
  `REQUESTED` rows."
- No engine-decision change: the engine still treats `REQUESTED` as in-flight; we just stop leaving
  dead `REQUESTED` rows behind a disable.
- **Out of scope (deferred):** gating the re-enable so routine cold launches skip the disable→enable
  entirely — an efficiency win (avoid needlessly re-uploading the not-yet-stored set), not a
  correctness fix, since clear-on-disable already makes every re-register self-healing.
- Stacks on `status-inflight-from-ledger` (reuses the app-side `LedgerBackend` it introduced).
