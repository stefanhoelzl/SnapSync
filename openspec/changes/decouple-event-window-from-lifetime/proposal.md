## Why

An event's `endsAt` currently does two unrelated jobs: it is the host's declared capture-date ceiling
**and** the server-stamped lifetime whose expiry ends the event (`event-limits`, decision `D2` of
`changes/archive/2026-07-22-add-event-date-range`). Coupling them means the event dies one grace day
after the window closes — so a guest who scans the QR three days late gets `410` and nothing, a member
whose OS has not yet drained its upload queue loses those photos, and a two-day wedding gives everyone
three days to collect a year's worth of memories. The photo window and the storage window are different
questions and the product needs different answers to them.

Decoupling them also removes the reason duration was left deliberately uncapped, which today puts an
unbounded storage commitment on the single `snap-sync-dev` zone that holds real users' photos.

## What Changes

- **BREAKING (backend contract):** `endsAt` becomes **only** the upload capture window. The lifecycle
  gate collapses — there is no `live`/`grace` split, no `410` on late enrollment, and no time gate on
  joining at all. An event is joinable, under capacity, for as long as it exists. (No client keys on
  `410`, so this is invisible to every shipped build.)
- **The window is capped at 30 days.** `POST /events` rejects `endsAt - startsAt > 30d` with `400`.
- **Lifetime becomes a stamped duration.** The marker carries `lifetimeSeconds`; the sweep deletes at
  `max(createdAt, startsAt) + lifetimeSeconds`. The *value* is per-event and immutable (a later
  constant change cannot move a live event's death date); the *anchor formula* stays in shared code and
  can be retuned with no metadata migration.
- **An emptied event is reclaimed early.** The nightly sweep also deletes an event that has been joined
  at least once and now has zero active manifests. This is **opportunistic**, not a promise — a leave
  whose backend `DELETE` never lands leaves a manifest active — so the stamped deadline remains the only
  guarantee.
- **The sweep stops notifying.** Notify-before-delete is removed: the channel is a freshness signal with
  a semantic-free payload, it is dispatched milliseconds before the deletes it announces, and acting on
  it would put a destructive branch in a background wake. This retires the `ADMIN_NOTIFY_KEY` secret,
  the notify route's admin-key authorization, and the sweep's only dependency on the Edge Script.
- **A device self-leaves when its event is confirmed gone**, in the **foreground only**, and only when
  **two independent witnesses agree**: the backend reports the event absent **and** the device's own
  persisted deadline has passed. A backend reporting absence alone is disbelieved.
- **`GET /events/<id>` serves the derived delete-by**, shown on the join gate (which the host also
  passes through after minting), so the retention date is server-owned and never duplicated client-side.
- **The published privacy policy is corrected.** "When an event ends, its photos are deleted" becomes
  false under this change; the retention statement leads with the 30-day ceiling.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `event-limits`: `endsAt` is the capture window only, capped at 30 days; lifetime becomes a stamped
  `lifetimeSeconds` anchored at `max(createdAt, startsAt)`; the `live`/`grace` lifecycle and the `410`
  enrollment refusal are removed; capacity is unchanged.
- `scheduled-cleanup`: the event-phase predicate becomes deadline-or-empty; the notify-before-delete
  requirement is removed; the sweep's config drops the admin key.
- `event-notify-endpoint`: admin-key authorization is removed — a valid device token is the only
  accepted credential.
- `event-creation`: `POST /events` validates the 30-day window cap and stamps `lifetimeSeconds`;
  `GET /events/<id>` serves the derived delete-by instant.
- `join-event`: the details fold gains a sealed outcome (refreshed / inconclusive / absent) and the rule
  is renamed for its actual need; the join gate states the 30-day retention ceiling and shows the
  event's delete-by date.
- `leave-event`: a membership is torn down without user action when the event is confirmed gone,
  foreground only, guarded by the device's own persisted deadline.
- `event-rejoin-reconciliation`: the reconcile backfill also fills the persisted delete-by for a
  membership stored before it existed.
- `backend-deployment`: `ADMIN_NOTIFY_KEY` is no longer a required secret.
- `sync-status-screen`: the "Event ended" marker moves out of the status line onto its own line above it —
  inline, the two unrelated facts read as one sentence and wrap mid-phrase on a phone.

**Prose-only corrections** (no requirement changes, so no delta spec): `event-leave-endpoint`'s Purpose
still describes a last-active-member reap and a "no periodic reaper" stance that the code and
`scheduled-cleanup` already contradict; `web-site` owns the privacy policy page whose retention
statement is rewritten, but no requirement governs its copy.

## Impact

**Backend (`api/`)** — `config.ts` (`EVENT_DURATION_SECONDS` splits into `EVENT_WINDOW_MAX_SECONDS` and
`EVENT_LIFETIME_SECONDS`; `EVENT_GRACE_SECONDS` and the admin key are deleted), `lifecycle.ts`
(`classifyEvent` collapses to a validity check; `eventIsStale` becomes deadline-or-empty), `validators.ts`
(window cap), `storage.ts` (marker gains `lifetimeSeconds`), `app.ts` (the `410` branch and the notify
route's admin auth), `sweep.ts` (predicate, membership input, no notify).

**Client (`:domain`)** — `model/` (`EventConfig.deletesAt`, a pure `confirmedGone` predicate beside the
`Cutoff` clamps, `JoinLoad.Found` gains the delete-by), `feature/membership` (`EventName` renamed and
extended to return a sealed fold outcome), `flow/` (`Foreground` and `Provision` route the absent
outcome to `LeaveEvent`), `compose/` (the deliberately lossy `fetchEventDetails` effect is widened to
carry the sealed result).

**UI** — the join gate gains a retention line and the delete-by date. The joined layer's "Event ended"
marker moves onto its own line above the status. The create screen is untouched; the marketing screenshots
capture `create` · `joining` · `in_sync`, none of which render an ended event, so no refresh is required.

**Site** — `site/src/pages/index.astro` privacy copy. `join.astro` needs nothing: its existing
*"Invalid or expired link"* already covers a reaped event, and it already branches on a `404` marker.

**Infrastructure** — the `ADMIN_NOTIFY_KEY` GitHub Actions secret and its Edge Script environment
variable are retired. The sweep becomes a storage-only client.

**Deployment order** — the backend deploys on merge; the client ships via TestFlight. The backend
change is old-client-safe (the absent-`endsAt` fallback is kept, and no client keys on `410`), and the
client change is inert against an un-migrated backend (an absent delete-by means the self-leave never
fires).

**Not touched** — `photo-selection-policy`, `device-manifest`, the capacity rule, the sweep's asset
phase, and the "nothing mints, clears, or leaves" invariant in all three background flows.
