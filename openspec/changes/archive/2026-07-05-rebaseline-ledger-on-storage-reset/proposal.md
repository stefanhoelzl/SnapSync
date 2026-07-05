## Why

When the backend storage is wiped (the `reset-storage` operator tool) or an object subset is deleted,
a device's extension ledger still records those resources as `COMPLETED`. The producer trusts the
ledger and never re-uploads them, so the device is **permanently stuck "Syncing"** — status reads
storage truth (empty/short) while the ledger says "done." Joining a new event *should* self-heal this
(the re-join `resetTo` seeds the ledger to exactly the stored files, so the missing ones re-upload),
but a guard **defers forever** whenever the listing comes back empty while the ledger holds `COMPLETED`
rows — exactly the storage-reset case.

That guard was added to survive a supposed "just-uploaded object not yet listed" transient. That
premise is false: measured 15/15 with zero lag, bunny native Storage LIST is read-after-write
consistent, and the backend's own contracts already make a *successful* empty listing authoritative
(a failed list is `502`, never a masked empty; a `2xx` upload confirms the bytes are stored). So the
guard defends against a state the system precludes, and converts a real reset into a permanent hang.

## What Changes

- **Flip the empty-listing behavior at re-join.** On a **confirmed-successful** per-device listing
  (empty *or* partial), the reconciliation SHALL `resetTo` the ledger to exactly the stored files and
  clear the discovery cursor — re-uploading whatever storage is missing. This makes full and partial
  deletion the same case. **BREAKING** (behavior): an empty listing against a ledger that holds
  `COMPLETED` rows now **re-baselines** (clear-and-seed to empty → re-upload everything) instead of
  deferring.
- **Keep the fetch-failure / timeout defer unchanged.** The only legitimate "don't trust this" case —
  an upstream error or timeout — still defers (the backend surfaces those as `502` → `Result.failure`),
  so the ledger is only ever reset on an authoritative listing.
- **Remove the now-disproven rationale** (the "read-your-writes lag" guard) from the code comment and
  the spec.
- No new dedup risk: the re-join already re-uploads idempotently (last-write-wins), and the wider
  recovery surface (a new event **or** a leave + re-join of the same event, both of which clear/mismatch
  the marker) is unchanged.

## Capabilities

### New Capabilities
<!-- none -->

### Modified Capabilities
- `event-rejoin-reconciliation`: on a confirmed-successful empty per-device listing, the reconciliation
  re-baselines the ledger to storage truth (re-uploading everything) instead of deferring; the
  transient-empty guard and its "read-your-writes lag" rationale are removed. Partial-listing and
  fetch-failure/timeout behavior are unchanged.

## Impact

- **Code:** `capability/rejoin/.../Reconciler.kt` — remove the empty-listing defer guard; update the
  stale comment. No new types or dependencies.
- **Tests:** rewrite the `ReconcilerTest` case that asserts "empty listing defers" into "empty listing
  re-baselines and re-uploads"; the fresh-device zero-row and fetch-failure/timeout cases are unchanged.
  A `:test:world` scenario may be added to exercise reset → new-event → re-upload end-to-end.
- **Behavior:** a storage reset (or partial deletion) followed by a re-join now self-heals — the device
  re-uploads exactly what storage is missing — instead of hanging in "Syncing." No change to a joined
  device with consistent storage (still no per-cycle listing cost).
- **Scope note:** drift that occurs with **no** re-join and **no** library change is still not
  auto-detected (accepted — the extension can't observe storage without a listing, and per-cycle
  listing was explicitly out of scope). The operator repairs it by joining a new event or leaving and
  re-joining.
