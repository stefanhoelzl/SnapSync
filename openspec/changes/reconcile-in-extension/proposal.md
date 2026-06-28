## Why

After `ledger-free-status` (Change 2), the app no longer **reads** the ledger — but it still **writes**
it: rejoin reconciliation runs in the app and seeds the ledger via `resetTo`, and the whole join-status
ceremony (`Joining`/`Joined`/`JoinFailed`) exists to narrate that app-run seed. With status now read from
the completeness listing, the seed is a **pure producer-side dedup optimization** (avoid re-uploading
bytes already in storage) and has no UI role. So it should live where the producer lives — the extension —
and the join ceremony can be deleted: on (re)join the app simply LISTs and shows real counts. This makes
the ledger **fully** extension-private (the app touches no ledger type) and removes a cross-cutting UI
state machine.

This is **Change 3 of the storage redesign** and depends on Change 2.

## What Changes

- **Reconciliation moves into the extension.** On its own cycle the extension self-seeds when the
  configured `eventId` differs from a small persisted **`joinedEventId` marker**: it fetches the
  completeness listing, seeds `COMPLETED` for each complete asset's resources, clears the discovery
  cursor, and sets the marker. The marker (not ledger-emptiness, which fails in the short-lived extension
  process) is the join signal: a fresh join with zero seeded rows still sets it, so there is no re-seed
  loop. If the listing fetch fails, the extension uploads nothing that cycle and retries on a later one.
- **The join-status UX is removed.** `EventStatus` (`Idle`/`Joining`/`Joined`/`JoinFailed`) and the
  `UiState.Joining`/`UiState.JoinFailed` reductions are deleted; on (re)join the status screen shows real
  listing-derived counts immediately (no spinner, no failure screen — a failed seed just retries silently).
- **BREAKING (removal):** the app no longer runs the join, disables/enables the extension around it, or
  constructs `EventStatusSource`; the app constructs **no ledger type at all** — event switch and leave
  reset the ledger **in the extension** when it observes the config change.
- The setup gate keys off **event-config presence** (already the case), which is independent of the seed.

## Capabilities

### New Capabilities
<!-- none — this change relocates and removes behavior across existing capabilities -->

### Modified Capabilities
- `event-rejoin-reconciliation`: the seed runs **in the extension**, gated by a persisted `joinedEventId`
  marker (was: app-run, gated by ledger-emptiness + an in-memory process flag); event-switch reset and
  the "defer uploads until seeded" discipline move into the extension; the `EventStatus`/join-status
  requirements are removed.
- `sync-status-screen`: the `EventStatus → UiState.Joining/JoinFailed` reduction (and those UI states) are
  removed; the screen reduces permission + the `SyncStatus` snapshot only.
- `ios-app-shell`: the composition root no longer constructs `EventStatusSource` or runs the join with the
  extension disabled; enabling the extension is unconditional on grant (the extension self-reconciles); the
  app constructs no ledger type.
- `event-invite-qr`: the joined-layer predicate drops the now-nonexistent `joining`/`join-failed` states.
- `leave-event`: leave clears config + cursor + disables the producer; the **ledger reset** and the
  `EventStatus → Idle` step are removed — the extension resets its private ledger when it next sees the
  config absent/changed.

## Impact

- **Code:** `app/ios/photokit-extension` (self-reconcile gate + `joinedEventId` marker + event-switch/leave
  reset + defer-until-seeded; the extension gains a one-time join LIST via an `EventFilesSource`);
  `:domain:status`/`:domain:presentation` (delete `EventStatus`, `UiState.Joining`/`JoinFailed`);
  `:app:ios` (drop `EventStatusSource` construction and the app-run join/seed and the disable-around-join);
  `:capability` reconciliation/`LeaveEvent` (relocate the reset).
- **Removed:** `EventStatusSource`/`EventStatus`, `UiState.Joining`/`UiState.JoinFailed`, the app's
  `resetTo` seeding and ledger construction.
- **Unchanged:** the completeness listing + manifest behavior (Change 1), the listing-backed status source
  (Change 2), `gallery-status`.
