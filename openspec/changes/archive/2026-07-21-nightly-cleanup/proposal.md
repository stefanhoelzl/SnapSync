## Why

Storage reclamation today is **eager and lazy**: an expired event is reaped only when a request
happens to touch it, and an event tree is torn down the moment its **last active member leaves**.
The consequences are the two the specs already name as accepted debt — an event nobody touches
"lingers as storage until touched" (`event-limits`), and "an event whose devices all vanish
without a clean leave is never reclaimed… that abandon-leak is accepted" (`event-leave-endpoint`)
— plus a product wart: **the last member leaving destroys a shared album out from under everyone
with the link.** A single scheduled sweep replaces all of it and makes leaving non-destructive.

## What Changes

- **NEW: a nightly cleanup sweep.** A scheduled GitHub Actions job reclaims, in two ordered
  phases, (1) **stale events** — every event past `endsAt + grace` — and (2) **stale assets** —
  every byte/device-record no surviving event still needs. It runs **outside** the edge script:
  Bunny Edge Scripting has no scheduler and caps a request at **50 subrequests / 30 s CPU**, so a
  whole-storage sweep cannot run there — the job talks to Bunny storage **directly** from an
  Ubuntu runner, importing the edge script's own lifecycle/storage modules so the logic cannot
  drift.
- **BREAKING (server behavior): leaving no longer deletes the event.** `DELETE
  …/devices/:deviceId` becomes **rename-to-`.left.json`, always succeed** — no last-active-member
  reap, no leave-time garbage collection. The event persists until it expires, so anyone with the
  link can still join/download; its bytes are reclaimed by the sweep after it's gone.
- **BREAKING (server behavior): the lazy expiry reap is removed.** The event lifecycle collapses
  from three states to **two** — `live` (`now ≤ endsAt`) and `grace` (`now > endsAt`: members
  keep syncing, joins closed `410`, **until the sweep deletes it**). Deletion by the sweep *is*
  expiry; there is no longer an "expired → 404/reap on touch" state.
- **Asset GC unified into one predicate:** a byte is collected iff it is **unreferenced** by any
  surviving manifest **and** its upload timestamp is `< min(startsAt)` over the device's active
  events (`min` over no active events = `+∞`). A device in no surviving event loses all its bytes,
  its config, and its attestation record. `gcDeviceIfUnreferenced` is deleted — the sweep is the
  sole byte/record collector. No wall-clock age fudge: live uploads are always `≥` their event's
  start `≥` the floor.
- **Notify reachable by the sweep:** `POST /events/:eventId/notify` gains an **`ADMIN_NOTIFY_KEY`**
  authorization path (alongside the device token) so the sweep — which cannot hold the APNs key —
  can trigger the members' silent "event is gone" push through the edge before deleting.
- **Client: register push on join.** Push registration fires on **join** in addition to launch —
  closing the warm-rejoin window a device would otherwise hit after the sweep collects its config.
  (Attest needs no change: the bearer token stays valid and self-heals at renewal.)
- **Docs:** correct the stale `ZONE = snap-sync` in `backend/README.md` (code uses `snap-sync-dev`).

## Capabilities

### New Capabilities

- `scheduled-cleanup`: the nightly sweep — its runner and direct-storage posture, the two-phase
  ordering (events then assets), the stale-event deletion rule (past `endsAt + grace`, incl. legacy
  markers, notify-before-delete), the unified stale-asset predicate, dry-run + best-effort +
  summary semantics, and the shared-module / main-region-read invariants inherited from the edge.

### Modified Capabilities

- `event-limits`: lifecycle collapses three states → two (`live`/`grace`); the **Expiry reap on
  first touch** requirement is removed; grace now runs open-ended (until swept) and the `+ grace`
  buffer governs only the sweep's delete timing.
- `event-leave-endpoint`: the **Last-active-member reap**, **Reference-checked garbage collection
  of freed devices**, and **Reap correctness depends on main-region reads** requirements are
  removed; leave becomes rename-only and always succeeds.
- `event-notify-endpoint`: notify accepts an `ADMIN_NOTIFY_KEY` bearer as an alternative to a device
  token.
- `push-registration`: registration timing gains **join** alongside launch and rotation.
- `backend-deployment`: the edge env gains a third managed secret (`ADMIN_NOTIFY_KEY`), and a **new,
  non-deploy** scheduled workflow holds the storage-zone key in GH secrets — a deliberate,
  scoped exception to "CI holds only the script-scoped deploy key."

## Impact

- **Backend (`backend/src/app.ts`, `config.ts`):** remove the two reap paths + `gcDeviceIfUnreferenced`;
  collapse `classifyEvent` to two states; add the `ADMIN_NOTIFY_KEY` notify auth; **extract** shared
  lifecycle/storage helpers into importable modules; add `ADMIN_NOTIFY_KEY` to the fail-closed secret read.
- **New sweep (`backend/`):** a Deno script (event + asset phases, `--dry-run`) importing the shared
  modules, plus `.github/workflows/*.yml` (nightly schedule + `workflow_dispatch`).
- **Client (`:domain` `feature/push` / `feature/membership` join flow, `:app:ios` wiring):** trigger
  `PushRegistration` on join.
- **Secrets/ops (manual):** `BUNNY_STORAGE_ACCESS_KEY` → GH secrets; `ADMIN_NOTIFY_KEY` → GH secrets + Bunny
  edge env. Validate `--dry-run` on the `snap-sync-dev` zone first.
- **Tests:** rewrite `LeaveCascadeWorldTest` and the `event-limits` / `event-leave-endpoint` backend
  tests that assert the removed reaps; new `commonTest`/`deno test` coverage for the sweep predicates.
- **Deploy timelines diverge:** backend + sweep go live on merge; `register-on-join` reaches users
  only at the next App Store release (safe — config/attest deletion already happens today via the
  leave cascade, and clients already self-heal).
