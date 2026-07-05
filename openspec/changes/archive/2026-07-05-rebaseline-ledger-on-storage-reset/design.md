## Context

The extension's `ExtensionReconciler` runs before each upload cycle and, on a `joinedEventId` marker
mismatch (a switch / reinstall / fresh provision), `resetTo`s the ledger to exactly the device's
stored files (one `COMPLETED` row per listed filename) and clears the discovery cursor, so the producer
re-uploads anything storage is missing. This already self-heals a **partial** deletion on re-join.

A guard short-circuits the **fully empty** case: when the per-device listing returns empty *and* the
ledger still holds `COMPLETED` rows, the reconciliation defers (leaves ledger/cursor/marker untouched),
on the theory that the empty listing is a transient "just-uploaded object not yet listed." After a real
storage reset the listing is genuinely empty while the ledger is full of `COMPLETED` rows, so the guard
defers **every** cycle — the ledger never resets, the marker never advances, nothing re-uploads, and
the device hangs in "Syncing" forever. Status reads storage truth (`sync-status`), so it correctly
shows the shortfall while the producer refuses to act.

## Goals / Non-Goals

**Goals:**
- A storage reset (full) or partial object deletion, followed by a re-join, self-heals: the device
  re-uploads exactly what storage is missing.
- Keep the ledger from ever being wiped on an *unreliable* signal (a failed/timed-out listing).
- Remove a guard — and a spec rationale — grounded in a premise proven false.

**Non-Goals:**
- Detecting storage drift with **no** re-join and **no** library change (no per-cycle listing; the
  extension can't observe storage otherwise). Explicitly out of scope, per the accepted tradeoff.
- Any change to status derivation, the upload path, the manifest, or download.
- Deleting objects from storage (the backup remains one-way / additive).

## Decisions

### D1 — On a confirmed-successful listing, re-baseline; only a failed fetch defers

Replace the "empty listing → defer" guard with: a **successful** `files.list()` result (empty or
partial) is authoritative → `resetTo` the ledger to exactly those files + clear the cursor. A
**failed/timed-out** fetch still defers (unchanged). Full and partial deletion collapse to one rule:
*seed what's stored, re-enumerate, upload the difference.*

**Why a successful empty listing cannot be a lie** — three system contracts chain to make
`COMPLETED ⟹ present in a successful listing`:

```
  upload job 2xx ──► bunny-upload-endpoint: "NEVER 2xx for an unconfirmed/partial upload"
                     ⟹ COMPLETED ledger row ⟹ bytes are in bunny
                          │
                          │  bunny native LIST is read-after-write consistent
                          │  (measured against the live zone: 15/15 first-list hits, 0 lag;
                          │   deletes reflected on the first list too)
                          ▼
  reconciler LIST ──► bunny-list-endpoint: "no partial list" — any failure/timeout ⟹ 502
                     files.list() = Result.success ONLY on backend 2xx
                     ⟹ a 2xx [] is genuinely empty, never a masked failure
```

So a successful empty listing while the ledger holds `COMPLETED` rows means the objects were **deleted**
(a reset), not delayed. The retained fetch-failure/timeout defer covers the *only* untrustworthy case,
because the backend expresses every real failure as `502` (→ `Result.failure`), never as an empty array.
The guard defended against a state the contracts preclude; removing it is safe **and** removes the
permanent-hang failure mode.

**Alternative considered — bounded retry (defer empty for K cycles, then reset).** Rejected: it adds
cross-cycle persisted state to hedge a transient we could not reproduce and the contracts forbid, while
the worst case of removing the guard outright is a single idempotent redundant re-upload — never a hang.

### D2 — Scope repair to re-join, not per-cycle

Detection stays at the existing marker-mismatch trigger; no per-cycle listing is added. The recovery
surface is already two-handled: a **new event**, or a **leave + re-join of the same event** (leave
clears the marker, so the re-join is a mismatch). Both re-baseline. A same-event drift with no re-join
and no library change is the accepted, documented gap.

## Risks / Trade-offs

- **A genuine reset re-uploads the whole library** → that is the correct, intended response to "storage
  is empty"; it is not a false positive. Idempotent (last-write-wins), bounded by the upload tier.
- **The accepted gap** (same-event, no-leave, no-library-change drift) is not auto-detected → mitigated
  by the two operator repair handles; a future change could add a cheap app-driven nudge (the app
  already reads storage-truth for status) without the rejected per-cycle listing.
- **Behavior flip is observable to any test/spec asserting the old defer** → the `ReconcilerTest` case
  and the spec scenario are updated in lockstep; the fresh-device and fetch-failure/timeout cases are
  untouched.

## Migration Plan

Pure client behavior change in the extension; no data migration, no backend change, no deployment
coupling. A device already stuck from a prior reset self-heals on its next re-join once the new build
runs. Rollback is reverting the reconciler edit (the old defer returns).

## Open Questions

- Is the accepted same-event-no-reprovision gap reachable in real usage, or does every storage mutation
  already pair with a re-provision? If unreachable, "repair on re-join" is complete, not a compromise —
  and no follow-up nudge is warranted.
