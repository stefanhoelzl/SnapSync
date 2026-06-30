# Design — clear REQUESTED on disable

## The trap

```
app cold launch / provision / leave
        │
        ▼  setUploadJobExtensionEnabled(false)        ← OS deletes the job config
   ┌─────────────────────────────────────────────┐
   │  ALL pending OS upload jobs are wiped         │
   └─────────────────────────────────────────────┘
        │   ledger still holds them as REQUESTED
        ▼   next extension cycle (same event → no reconcile)
   fetch(retry): 0   fetch(acknowledge): 0   discovered 0
        │   engine: REQUESTED ⇒ AlreadyUploaded ⇒ never re-create
        ▼
   "N pending" forever — those photos never upload
```

A `REQUESTED` row is a *hope* ("a job was answered"). When the OS loses the job, nothing positively
signals it: `fetchJobsWithAction` only surfaces jobs the OS wants *retried* or *acknowledged*, never a
"this one vanished," and the engine only re-issues `FAILED`/absent keys. So the hope is orphaned.

## The fix

Pair the clear with the wipe: **whenever the app disables the extension, clear `REQUESTED`.**

```
disableExtension() {
    setUploadJobExtensionEnabled(false)   // OS wipes all in-flight jobs
    ledger.clearRequested()               // drop the now-orphaned REQUESTED rows
}
```

- Used at **both** disable sites — the `disable→enable` re-register and `LeaveEvent`'s disable lambda —
  so they cannot diverge.
- `COMPLETED` rows stay (stored files keep their dedup); `FAILED` rows stay (the engine retries them).
  Only `REQUESTED` — the stuck state — is cleared.
- The next discovery sees the cleared keys as absent → re-creates the not-yet-stored jobs. Clearing
  **all** `REQUESTED` is correct because a disable wipes **all** in-flight jobs, so there is nothing
  genuinely in flight left to double-upload.

## Decisions

| Decision | Choice | Why |
| --- | --- | --- |
| Who clears | **App, directly** | The app owns the disable and already opens the `LedgerBackend` (for the in-flight read). A `clearRequested()` is a **reset-family** op, like the existing app-side `clear()` — not a per-key record write — so it does not breach the single-record-writer invariant. The disable/clear runs when the extension isn't actively writing, so cross-process write overlap is rare and benign (idempotent). |
| What to clear | **`REQUESTED` only** | It is the only stuck state. `FAILED` already yields `Work` (self-heals); `COMPLETED` is real storage truth (dedup). |
| Recovery shape | **Local delete, not a reconcile** | `clearRequested()` is offline-safe (no `GET /files/device`); re-creation falls out of the normal next discovery. Simpler and more robust than signalling a `resetTo`. |
| Trigger | **Every disable** | Makes both the necessary re-registers (install/re-sign with a backup mid-flight) and the routine ones self-healing, without needing to detect *which* disable wiped something. |
| Cold-launch gating | **Deferred** | Stopping the routine re-register avoids the re-upload churn but is an efficiency concern; clear-on-disable already restores correctness. |

## Capability placement

`clearRequested()` sits beside `clear()`/`resetTo()` on `LedgerBackend` — the **app-side reset family**,
distinct from the **writer-only** prunes (`deleteByAssetId`/`retainAssets`). It is callable through
`LedgerBackend` without a `LedgerWriter`, so `:app:ios` constructs no writer (the hard rule holds). The
ledger op carries the testable logic (run under `LedgerBackendContract` on JVM + native); the app side
is one-line wiring into the disable path.
