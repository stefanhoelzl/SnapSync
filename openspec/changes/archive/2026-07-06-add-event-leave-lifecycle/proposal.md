## Why

Leaving an event is **local-only** today: the device forgets the event, but its per-event manifest,
its device-global byte partition, and its push-token config persist on the backend forever — an
event's storage can never be reclaimed. This change turns leave into an **event-lifecycle**
operation: a departing device is removed from the event (its already-shared photos preserved so the
remaining members can still download them), and when the **last** device leaves, the event is
deleted and its now-unreferenced bytes are garbage-collected.

> **Depends on** `restructure-storage-url-layout` landing first. Every path below uses the
> post-reorg layout (`events/<E>/devices/<D>.json`, `files/devices/<D>/…`, `devices/<D>.json`).

## What Changes

- **New leave endpoint. BREAKING (new device→backend call).** `DELETE /events/<E>/devices/<D>` —
  the device notifies the backend when it leaves. Gated on event-marker existence; idempotent
  (at-least-once safe).
- **Leave preserves contributions; only the last leaver deletes anything.** The handler **renames**
  the device's active manifest `events/<E>/devices/<D>.json` → `…/<D>.left.json`. A departed device's
  photos stay in the event's union (remaining members keep downloading them). No bytes are deleted on
  a non-final leave.
- **Last-device-leaves reaps the event + GCs orphaned bytes.** When no **active** manifest remains
  under `events/<E>/devices/`, the handler deletes the whole `events/<E>/` tree, then for each freed
  device — **only if that device appears in no surviving event** (active or departed) — deletes its
  `files/devices/<D>/*` byte partition and its `devices/<D>.json` config. This is a true
  reference-count: a device's shared bytes survive as long as any event still references them.
- **Membership is last-write-wins by object write time.** When both `<D>.json` and `<D>.left.json`
  exist (a partially-applied leave or rejoin), the **newer** wins — no separate marker, no ambiguity.
  The union, notify fan-out, and reap check all read the timestamp already present in every Bunny LIST.
- **Rejoin supersedes a prior leave.** Writing a fresh `<D>.json` (the extension's manifest PUT)
  makes the device active again; the stale `<D>.left.json` loses the LWW comparison (and is deleted
  as cleanup). Re-scanning an event a device left re-joins it.
- **The union now serves departed contributions.** `GET /events/<E>/files` reads **both** active and
  departed manifests (LWW-deduped) — departed devices' photos remain downloadable until the event dies.
- **Notify targets active members only.** `POST /events/<E>/notify` fans out to devices whose LWW
  state is **active**, skipping departed ones.
- **Leaving becomes destructive-on-last, wired to two triggers.** The device-side `LeaveEvent` use-case
  gains a best-effort backend-notify step (fired from the main app), invoked by (a) the explicit Leave
  button and (b) **switch** — provisioning a different event while joined now leaves the previous event
  first. Local teardown still completes regardless of the backend call (best-effort, no rollback).
- **`:capability:rejoin` is renamed to `:capability:membership`** (it now hosts join-reconcile + leave
  + the device-file listing seam + the new leave-notifier).
- **Abandoned events leak, by accepted design.** If every device vanishes without a clean leave
  (uninstall, permanent offline), the event is never reaped — there is **no periodic reaper** in this
  change. Every partial failure and race resolves toward an orphan, never toward deleting in-use data.

**Explicitly out of scope (separate changes):** a leave-confirm dialog on switch; any periodic
backend reaper for the abandon-leak.

## Capabilities

### New Capabilities
- `event-leave-endpoint`: the `DELETE /events/<E>/devices/<D>` route and its cascade — rename to
  `.left.json`, last-active-member reap of the `events/<E>/` tree, and reference-checked garbage
  collection of freed devices' byte partitions + config. Leak-safe (partials → orphans) and
  corruption-free given main-region read-after-write LIST consistency.

### Modified Capabilities
- `leave-event`: no longer local-only — the use-case adds a best-effort `LeaveNotifier` step and is
  invoked on both explicit Leave and switch. (Supersedes the "Leave is local-only / touches no
  storage" requirement.)
- `deeplink-config`: provisioning a **different** event while already joined fires a best-effort
  leave of the previous event before re-provisioning.
- `device-manifest`: introduces the `<D>.left.json` **departed** manifest and defines active-vs-departed
  membership as last-write-wins over the two siblings' write times.
- `bunny-list-endpoint`: the event-wide union reads both active and departed manifests (LWW-deduped),
  so departed contributions remain downloadable.
- `event-notify-endpoint`: the member fan-out targets LWW-**active** manifests only, excluding departed.
- `device-config-endpoint`: the config document gains a deletion path — it is removed when its device
  becomes fully orphaned during the leave cascade's GC.
- `harness-world-model`: the `:test:world` MiniEdge answers `DELETE /events/<E>/devices/<D>` with the
  full cascade (rename, reap, reference-checked GC) and applies LWW membership to its union/notify reads.

## Impact

- **Backend (`backend/src/app.ts`, Deno/TS):** a new `DELETE` route + cascade helpers (rename, reap,
  GC); `BunnyEntry` gains `LastChanged` (currently discarded at app.ts:126); union + notify apply LWW
  membership; a per-object delete loop (Bunny has no batch delete). `backend/README.md` (leave/GC
  contract + the reap's dependence on the existing main-region read-after-write invariant).
  `backend/test/**`.
- **Device (Kotlin):** `:capability:membership` (renamed from `:capability:rejoin`) — new
  `LeaveNotifier` / `HttpLeaveNotifier` (mirrors `DeviceFilesSource`) and the `LeaveEvent`
  best-effort notify step; `:capability:config` / `deeplink-config` switch path fires leave;
  `:app:ios` composition root wiring (Leave button + switch both call the notifier). Module rename
  ripples through `build.gradle.kts` files, imports, and `settings.gradle.kts`.
- **Test harness:** `:test:world` MiniEdge (DELETE + cascade + LWW), `:test:integration`
  (leave → last-device reap → GC outcomes: manifests renamed, event deleted, bytes/config swept).
- **APIs (BREAKING):** a new device→backend `DELETE`; app and backend ship together (device is the
  only client, pre-release).
- **Docs/specs:** `docs/design.md` §3 (storage lifecycle) and §3.2 (leave semantics) rewritten from
  "local-only" to the lifecycle model, incl. the leak-safety + corruption-freedom risk notes; the 1
  new + 7 modified capability specs above; incidental module-name prose in `event-rejoin-reconciliation`.
- **Not touched:** upload/download crypto, the presigned-URL model, event-marker semantics, the
  deviceId-as-capability trust model, `photo-download` (union grows but its requirements are unchanged).
