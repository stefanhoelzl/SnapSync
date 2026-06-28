## Context

Reconciliation seeds the upload ledger with already-stored resources so a re-joined / reinstalled device
doesn't re-upload them. Historically it ran in the **app** (the enable sites — permission grant, QR
provision — are app events), used ledger-emptiness as the "needs join" signal plus an in-memory
"settled-this-process" flag, and narrated itself through `EventStatus` (`Joining`/`Joined`/`JoinFailed`)
which the status screen reduced to `UiState.Joining`/`JoinFailed`. After Change 2 the status screen reads
the completeness listing directly, so the seed no longer feeds the UI — it is purely a producer-side
dedup optimization. The ledger is also meant to be extension-private after Change 2, which the app's
`resetTo` seeding still violates.

## Goals / Non-Goals

**Goals:**
- Run reconciliation in the extension; the app constructs no ledger type.
- Replace the app-model join signal (ledger-emptiness + process flag) with a persisted `joinedEventId`
  marker that works in the extension's short-lived process.
- Delete the join-status UX (`EventStatus`, `UiState.Joining`/`JoinFailed`).

**Non-Goals:**
- The listing/manifest/status mechanics (Changes 1–2).
- Any change to *what* gets seeded (still: resources of complete assets from the listing).

## Decisions

### D1 — `joinedEventId` marker, not ledger-emptiness, is the join signal
The extension persists the last `eventId` it reconciled. On a cycle it reconciles iff the configured
`eventId` ≠ the marker; on success it sets the marker. This fixes the failure modes of the app-model
signal in a short-lived process: a **fresh** join that seeds **zero** rows still sets the marker (no
re-seed loop on an empty/large-library join), an **event switch** is a marker mismatch (reset + re-seed),
and a **reinstall** is an absent marker. *Alternative rejected:* ledger-emptiness — it never clears on a
zero-row join, and the in-memory "settled this process" flag doesn't survive the extension's per-cycle
process.

### D2 — The extension defers uploads until the seed succeeds
On a cycle where it must reconcile, the extension fetches the listing and seeds **before** creating any
upload jobs. If the fetch fails, it creates no jobs that cycle and retries on the next — invisible to the
user (status comes from the app's own LIST). *Alternative rejected:* the old `JoinFailed` screen +
"re-scan to retry" — there's nothing for the user to act on once status is listing-derived.

### D3 — Delete the join-status UX
`EventStatus` and `UiState.Joining`/`JoinFailed` are removed. During reconciliation the status screen
shows real listing counts (often 0→N as the seed/uploads land), which is more honest than a spinner.
Permission gating and the snapshot reduction are untouched.

### D4 — Event-switch and leave reset the ledger in the extension
The app no longer calls `resetTo`. Event switch is a marker mismatch the extension handles (reset its
ledger, re-seed for the new event). Leave clears config + cursor + disables the producer; the extension,
seeing the config absent/changed on its next cycle, resets its private ledger. The "disable producer
before reset" discipline is preserved trivially — the reset now happens inside the producer's own process.

## Risks / Trade-offs

- **Seed timing is OS-scheduled** → the dedup seed lands whenever the extension next runs, not
  synchronously on join. Mitigation: harmless — status is already correct from the app's LIST, and the
  worst case before the seed is a bounded re-upload of already-stored bytes (idempotent), which the seed
  then prevents going forward.
- **A fresh join with an empty library never writes a ledger row** → with the marker, it still sets
  "joined" and does not re-LIST every cycle (the prior ledger-emptiness signal would have).
- **One extra LIST in the extension at join** → only when the marker mismatches (reinstall / switch),
  not per cycle; after seeding the marker matches and no LIST occurs.
- **Cross-cutting UI deletion** → removing `EventStatus` touches presentation, the status screen, invite
  gating, and leave. Mitigation: these are mechanical removals of a now-dead state; the joined-layer
  predicate simply loses two states it used to exclude.

## Migration Plan

Apply after Change 2 is archived. No data migration. Rollback restores the app-run join + `EventStatus`.

## Open Questions

- None blocking. If a visible "joining…" affordance is ever wanted back, it can be re-derived cheaply from
  "config present + listing still empty" without reintroducing a cross-process `EventStatus`.
