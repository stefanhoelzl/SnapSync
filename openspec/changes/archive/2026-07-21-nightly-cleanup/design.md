## Context

The backend is a single Bunny Edge Scripting bundle (Deno + Hono) fronting one Bunny storage zone;
there is **no database**. An event is a JSON marker; membership is per-event manifest objects;
photo bytes are device-partitioned blobs referenced by manifests. Lifecycle is a pure function of
the marker's `endsAt` + a config grace period, recomputed on every read (`event-limits`).

Cleanup today is eager and lazy, and both mechanisms have known holes the specs already accept:

- **Lazy expiry reap** (`event-limits`): an expired event is deleted only when a request touches it;
  an untouched event lingers as storage forever.
- **Last-active-member reap** (`event-leave-endpoint`): the event tree is deleted the instant its
  last active member leaves — destroying a shared album out from under everyone with the link — and
  a device that vanishes *without* a clean leave is never reclaimed (the "abandon-leak").
- Byte GC is reference-counted (`gcDeviceIfUnreferenced`) and only runs inside those two reaps.

This change replaces all of it with **one nightly sweep** and makes leaving non-destructive.

Two platform facts constrain *where* the sweep can run (verified against Bunny's docs, 2026-07):
① Edge Scripting has **no scheduler** — scripts run only per HTTP request. ② A request is capped at
**50 subrequests and 30 s CPU**. A whole-storage sweep issues thousands of storage calls, so it
**cannot** run inside the edge — not even chunked behind an endpoint without an external driver loop.

## Goals / Non-Goals

**Goals:**
- Reclaim expired events and orphaned assets on a schedule, with no dependence on member traffic.
- Make leaving non-destructive: an event survives until it expires, so it stays rejoinable.
- One garbage-collection mechanism, one place, no arbitrary age constants.
- Keep the sweep's storage-layout and lifecycle logic *identical* to the edge's (no drift).
- Never delete a live user's in-flight upload.

**Non-Goals:**
- Real-time / on-touch reclamation (deletion latency up to one nightly cycle is accepted).
- A proactive push for events nobody touches beyond the existing best-effort silent push.
- Reclaiming bytes orphaned *inside* a still-active device sooner than the device's memberships lapse
  (e.g. on-device deletions while the device stays joined) — acceptable, and closable later.
- Changing capacity, duration, or grace *values* (grace stays 1 day; it now delays the sweep's delete).

## Decisions

### D1 — Run the sweep from GitHub Actions against storage directly (not an edge endpoint)
Forced by the two edge limits above. A scheduled GH Actions Deno job has no subrequest/CPU cap and can
page through all of storage. **Trade:** it needs `BUNNY_STORAGE_ACCESS_KEY` (the photo-zone delete key)
in GH secrets — a deliberate, scoped exception to backend-deployment's "CI holds only the deploy key."
*Alternatives rejected:* (a) an in-edge `POST /admin/sweep` — dies on the 50-subrequest cap; (b)
`Deno.cron` in the edge — Bunny has no scheduler, so it never fires.

### D2 — Share code, don't reimplement
The sweep imports the edge script's own storage helpers, key builders, `classifyEvent`,
`resolveMembership`, and config constants (extracted into importable modules). This neutralizes D1's
only real risk — that a second copy of the layout/lifecycle rules drifts from the edge. *Alternative
rejected:* a standalone sweep script — faster to write, but the rules would live in two places.

### D3 — Lifecycle collapses to two states; deletion *is* expiry
`live` (`now ≤ endsAt`) → `grace` (`now > endsAt`: members sync, joins `410`) → **deleted by the sweep**.
The old "expired → 404/reap on touch" state is gone; between `endsAt` and the sweep an event simply
stays in grace and keeps serving members. The `+ grace` buffer (1 day) now governs only *when the
sweep deletes* (`now > endsAt + grace`), giving an effective grace of `grace_seconds` rounded up to the
next nightly run. Legacy markers (missing `endsAt`) are treated as grace by the gate and deleted by the
sweep. *Alternative rejected:* keep three states and defer only the physical delete — smaller change,
but keeps a state we no longer need.

### D4 — Leave becomes rename-only, always succeeds
`DELETE …/devices/:deviceId` renames the active manifest to `.left.json` and returns success,
regardless of remaining membership. No reap, no leave-time GC. The event lives until expiry; its bytes
are reclaimed by the sweep afterward. This removes three `event-leave-endpoint` requirements
(last-member reap, freed-device GC, reap main-region-read correctness) — the reap's concerns move to
the sweep.

### D5 — Unified asset predicate keyed on upload time and event membership (no age constant)
A byte is collected iff **(a)** it is unreferenced by any surviving manifest (`.json` *or* `.left.json`)
**and (b)** its storage `DateCreated` (upload time) `< min(startsAt)` over the device's active events,
where `min` over the empty set = `+∞`. Rationale:

```
   floor(device) = min( startsAt of each surviving event the device is active in )   [∅ → +∞]

   a live upload for ANY active event was uploaded at/after that event's join,
   which is ≥ that event's startsAt ≥ floor(device)          ⟹ never below the floor ⟹ safe
   a byte from a switched-away / expired event was uploaded before the new event started
                                                             ⟹ below the floor  ⟹ collectable
   device in no surviving event: floor = +∞                  ⟹ every unreferenced byte collectable
```

Crucially, the manifest is written from **discovery, not upload completion** — a key enters the
manifest when its upload *job is created*, before the bytes land (verified in `UploadCycle`). So the
reference generally *precedes* the bytes; the only unreferenced-recent bytes are cap-truncated
deferred-manifest cycles, and those are uploaded *during* an active event, hence `≥` the floor. That
is why **no wall-clock age fudge is needed**. Device config (`devices/<id>.json`) and attestation
(`devices/<id>.attest.json`) — which carry no event date — are collected under the `+∞` case only:
device in no surviving event. *Alternative rejected:* a fixed age threshold (48h/7d) — an arbitrary
constant that either risks a throttled multi-day drain or lingers dead bytes needlessly.

### D6 — Two phases, events before assets
The event phase deletes expired events (markers + manifests) first; the asset phase then computes the
referenced set and the per-device floor over **surviving** events. Ordering matters: a device whose
only event was just deleted correctly falls into the `+∞` case and is fully collected.

### D7 — Notify reuses the edge via an `ADMIN_NOTIFY_KEY`
The sweep can't hold the APNs key, so member notification stays on the edge: before deleting an
expiring event, the sweep calls `POST /events/:eventId/notify` authorized by a new **notify-only**
`ADMIN_NOTIFY_KEY` bearer (an alternative to the device token). Best-effort — a failed notify never blocks the
delete. Sequencing is notify-*then*-delete (once the marker is gone, notify would 404; in grace the
event still serves, so notify works right up to the delete). *Alternative rejected:* a bespoke new edge
endpoint — reusing the tested notify route is simpler and narrower.

### D8 — Register push on join (client)
The sweep collecting `devices/<id>.json` opens a warm-rejoin window: leave → config swept → rejoin
*without an app restart* → the OS doesn't redeliver the token, so the backend can't push the device
until its next cold launch. Firing `PushRegistration` on **join** (in addition to launch/rotation)
closes it. Not on foreground (too frequent). Attest needs no equivalent: the bearer token is a pure
HMAC check that reads no storage, so it stays valid, and the renew path already self-heals a missing
`attest.json`.

### D9 — Dry-run + best-effort + summary
The sweep supports `--dry-run` (list candidates, delete nothing) for first validation and audits. Real
runs delete best-effort per object (log-and-continue, idempotent — deletes are already `404`-tolerant),
print a summary (events swept, bytes/records collected, bytes retained-by-floor), and exit non-zero only
on systemic/auth failure. Reads hit the storage **main region** (the same read-after-write invariant the
old reap relied on) so a concurrent rejoin is visible.

## Risks / Trade-offs

- **Storage delete key in GH secrets** → a leak lets an attacker wipe all photos. Mitigation: dedicated
  secret, scoped to the one cleanup workflow; validate via `--dry-run` on `snap-sync-dev` first.
- **Removing both eager reaps before the sweep's first run** → up to one nightly cycle where new
  expirations aren't yet deleted. Mitigation: harmless — they linger exactly as untouched-expired events
  did before; the first sweep clears the backlog (incl. the historical abandon-leak).
- **Sweep over-deletes due to a logic bug** → data loss. Mitigation: shared modules (no drift), the
  membership+floor predicate has no arbitrary constants, `--dry-run` validation, and `commonTest`/`deno
  test` coverage of both predicates.
- **Bytes orphaned inside a still-active serial event-joiner** linger until the device fully lapses.
  Accepted (Non-Goal); closable later with a per-byte capture-date index.
- **`register-on-join` reaches users only at App Store release** → old clients keep the warm-rejoin
  window. Mitigation: it self-heals on cold launch today; config/attest deletion is already what the
  leave cascade does, so no regression — `register-on-join` is a pure enhancement.

## Migration Plan

1. Land the backend changes (shared-module extraction, two-state lifecycle, rename-only leave, remove
   `gcDeviceIfUnreferenced`, `ADMIN_NOTIFY_KEY` notify auth) + the sweep script + workflow in one change.
2. Set secrets **before** the first scheduled run: `BUNNY_STORAGE_ACCESS_KEY` (GH), `ADMIN_NOTIFY_KEY` (GH +
   Bunny edge env). Correct `backend/README.md`'s zone.
3. Validate `--dry-run` against `snap-sync-dev`; inspect the summary; then enable the nightly schedule.
4. `register-on-join` merges with the rest and ships to users on the normal iOS release train.
5. **Rollback:** disable the workflow (schedule off). The two-state lifecycle and rename-only leave are
   backward-compatible with the old data; reintroducing an eager reap is a separate change if ever needed.

## Open Questions

None outstanding — the interview resolved runtime (GH Actions/direct storage), lifecycle (two-state),
leave (rename-only), the asset predicate (membership + upload-time floor), config/attest handling, the
notify `ADMIN_NOTIFY_KEY`, and `register-on-join`. Remaining choices (exact cron hour, summary format) are
implementation details.
