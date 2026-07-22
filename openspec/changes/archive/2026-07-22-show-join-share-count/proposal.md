## Why

The join surface is where a member configures what they contribute, yet it says nothing about how many of their own photos the chosen cutoff will actually share. The mission names the exact fear this leaves unanswered — a default inherited from the app's one-way-backup origins turns "back up everything of mine" into "upload a guest's whole camera roll to a stranger's event." A live count answers "how many photos from my gallery will be shared?" at the moment the cutoff is chosen, turning the cutoff from an abstract date into a visible consequence.

## What Changes

- The join **Ready** surface gains a live row under the "Share my photos" toggle: `XX photos from your gallery will be shared`, recomputed (debounced) whenever the cutoff choice changes (Now / Event start / Custom), with a `counting…` state while it recomputes.
- The count means **photos that will be shared to this event** — the own-gallery in-scope set (taken since the cutoff, passing the selection policy) — computed **purely locally, no network call**. Chosen over "bytes that will transfer" so it is consistent, robust, and matches the mission's framing ("the photos you share").
- Zero-state copy: `0 photos from your gallery will be shared` plus `New photos you take will be shared as you go`, so a legitimate `0` (cutoff = Now, or no matches) does not read as broken.
- The count is computed via a **count-only path that needs no per-asset PhotoKit resource read** (the ~110 ms/asset XPC is only for upload keys, which a count does not need): a cutoff-bounded fetch over cheap `PHAsset` properties, the same origin exclusions the policy applies, minus denylisted-album members (one album read, cached once per surface). Under `LIMITED` it re-filters the already-held selection snapshot (no fresh read — honouring the limited-access autonomous-read prohibition); under `DENIED`/unresolved permission the row is omitted.
- The same count row appears on the **switch-event** and **reconfigure** surfaces (which reuse the cutoff/share controls).
- **BREAKING (behavior fix, prerequisite):** lowering the cutoff on **reconfigure** currently backfills newly-in-scope older photos on iOS ≥26.1 but silently does **not** on iOS 18–26.0 (the URLSession tier keeps a forward-only discovery cursor). The reconfigure path is changed to invalidate the discovery cursor when the cutoff moves earlier, so **both** tiers re-enumerate at the new cutoff — making the count truthful everywhere and fixing a latent tier inconsistency.
- The selection policy's one-universe invariant is extended: the same policy that gates the byte upload, the device manifest, and the status total `N` also gates this join-time preview count.

## Capabilities

### New Capabilities
- `join-share-count`: the pre-commit read-model that computes, for a candidate cutoff and direction, how many own-gallery photos would be shared to the event — cheaply (no per-asset resource walk), via the selection policy, branched by permission grant (GRANTED fetch / LIMITED snapshot / omitted otherwise).

### Modified Capabilities
- `join-event`: the Ready surface renders the live count row (copy, zero-state, `counting…` state) under the Share toggle, and the same row appears on the switch-event surface.
- `reconfigure-membership`: the reconfigure surface renders the same count row; and lowering the cutoff invalidates the discovery cursor so newly-in-scope older photos are re-enumerated and shared on both upload tiers.
- `photo-selection-policy`: the one-policy-one-universe requirement now also names the join-time shareable-count preview.

## Impact

- **UI:** `:ui:screens` (`ReadyLayout`, `ReconfigureScreen`, `SwitchDialog` — the count row), `:ui:presentation` (`StatusContainerHost`, `UiState.JoinPhase.Ready` gains a count field, a new injected suspend query for the candidate count — same pattern as `loadJoinDetails`). Deliberately reverses `UiState`'s "no counts are carried" stance for the join *decision* surface only (the Joined *health* surface stays count-free).
- **Domain:** `:domain` `feature/` (a shareable-count use-case; `ReconfigureEvent` gains cursor-invalidation on cutoff-lowering), `ports/` (a count query seam; a discovery-cursor-clear seam reachable from membership), `compose/` wiring.
- **Adapters:** `:adapter:ios:ext-safe` (a count-only enumerator path skipping `assetResourcesForAsset`; the discovery cursor store), `:adapter:ios:app-only` (LIMITED snapshot filtering by candidate cutoff). Honours `limited-photo-access` rule ① (no autonomous read under LIMITED) — no delta there.
- **Tiers touched by the fix:** `ios-url-session-upload` (the tier whose forward-only cursor caused the silent no-backfill); `ios-photokit-upload` already backfills incidentally.
- **Tests:** `:test:integration` (the count equals the actual upload set; reconfigure-lowering backfills on both tiers), `commonTest` for the count-computation logic.
- No new third-party dependency; no backend/API change (the count is local-only).
