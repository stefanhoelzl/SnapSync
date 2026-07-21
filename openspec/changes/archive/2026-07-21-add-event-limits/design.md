# Design: Add Event Limits

## Context

An event is a write-once marker (`events/<id>/metadata.json`, shape
`{eventId, name, createdAt, startsAt}`) plus per-device manifests under `events/<id>/devices/`.
Membership is per-device: `PUT /events/:id/devices/:deviceId` both enrolls a device (register-only
empty manifest at join) and updates its manifest thereafter — one route, one choke point. Active
vs departed membership is already resolved server-side (`resolveMembership`: `<id>.json` vs
`<id>.left.json`, last-write-wins), and the leave route already owns a reap cascade (last active
member leaves → event objects GC'd, freed devices' config docs reference-checked and removed).
Silent APNs fan-out to all active members exists (`POST /events/:id/notify`).

Nothing bounds an event: no capacity, no lifetime, no closed/ended/expired state anywhere in code
or specs. The backend has no scheduler, and the storage backend (Bunny) has no compare-and-set.

This change was designed through an extended interview (2026-07-21); the decisions below record
its outcomes and the alternatives that were explicitly considered and rejected.

## Goals / Non-Goals

**Goals:**

- Every event created after this ships is bounded in size (devices) and lifetime (wall-clock),
  enforced entirely server-side.
- Late uploads of photos taken during the event still land: a grace window keeps full sync open
  for existing members after the event ends.
- An expired event disappears completely — storage reclaimed, members notified — with no
  scheduler, riding on lazy reap.
- The marker schema is forward-compatible with creator-chosen limits (a follow-up change swaps
  only the mint-time value source).

**Non-Goals:**

- No client change of any kind. Guest-facing "full"/"ended" UI, `EventDetails` variants, the
  status-screen ended state, and a visible (alert) deletion push are the follow-up change.
- No client-side capture-date upper bound in the selection policy (photos taken after `endsAt`
  can still upload during grace from phase-1 clients — accepted 1-day overshoot).
- No person identity: the cap counts devices, not people.
- No strict cap under concurrency, and no scheduled sweep.

## Decisions

### D1: Limits are stamped on the marker at mint, enforced from the marker

`POST /events` resolves `endsAt = startsAt + EVENT_DURATION` and `capacity = EVENT_CAPACITY`
from backend config and writes them into the marker. Every check afterwards reads the marker's
own fields.

- *Alternative — enforce from live globals each request*: rejected; changing the global would
  retroactively change live events, and the follow-up (creator-chosen values) would then need a
  schema migration. Stamping makes phase 2 a value-source swap with zero schema change.
- The marker stays write-once: both new fields are derivable at mint and never rewritten.
- `endsAt` is stored in the same canonical cutoff form as `startsAt`
  (`yyyy-MM-dd'T'HH:mm:ss'Z'`), so the expiry comparison stays a lexicographic string compare
  against a canonically-formatted "now", exactly like every other timestamp comparison in the
  system. `capacity` is a positive integer.

### D2: Capacity counts device ids ever enrolled; creator counts; overshoot accepted

The cap compares `capacity` against the count of distinct device ids under `devices/` — active
`.json` **and** departed `.left.json`. Leaving never frees a slot (prevents churn-to-evade); a
device id already present in either form passes freely (rejoin reuses its slot; manifest updates
are never capacity-checked). The creator's auto-join is device #1.

- *Alternative — count active devices only*: rejected; leave-then-rejoin churn would let an
  unbounded number of distinct devices pass through a "full" event.
- *Alternative — strict cap*: rejected; Bunny has no lock/CAS, so strictness needs external
  coordination machinery. Rare overshoot under simultaneous joins is harmless at these scales.
- *Alternative — creator exempt*: rejected; needs an owner field the marker doesn't have.

### D3: Lifetime = live → grace → gone; grace closes joining, not sync

- `now ≤ endsAt`: live. Joins allowed (under cap), full sync.
- `endsAt < now ≤ endsAt + EVENT_GRACE`: grace. A never-seen device id on the manifest `PUT`
  gets `410 Gone`; known devices keep **full** sync (manifest writes, uploads, union reads,
  notify). Rationale: iOS schedules uploads on its own cadence, so a photo taken in-window can
  upload late; a hard stop at `endsAt` silently drops it, and a silently missing event photo is
  the failure mode this app treats as worst. Grace exists exactly for those stragglers.
- `now > endsAt + EVENT_GRACE`: expired — see D4.
- *Alternative — hard stop at `endsAt`, no grace*: initially chosen, then reversed in review for
  the straggler reason above.
- *Alternative — grace admits only photos taken ≤ `endsAt`*: the backend cannot see capture
  dates (manifests list assets, objects are opaque bytes), so server-side enforcement would mean
  a manifest wire-format change plus client cooperation. Deferred to the follow-up as a
  client-side selection-policy upper bound.

### D4: Expiry is deletion — lazy reap on first touch, silent push first, no tombstone

The shared marker-read helper that every event-scoped route already calls gains the expiry check.
On the first request past `endsAt + EVENT_GRACE` it: (1) resolves active members and fans out the
existing silent push (best-effort, same machinery as notify), (2) runs the existing leave-cascade
deletion over the whole event — manifests, photo objects, reference-checked device config docs —
and (3) deletes the marker itself. The triggering request and everything after get `404`,
indistinguishable from an event that never existed.

- *Alternative — tombstone (keep the marker, serve `410 "ended"`)*: chosen early in the
  interview for the host-facing story, then deliberately reversed: full deletion was preferred,
  with the member-facing signal moved to the deletion push. Accepted consequence: a guest
  scanning an expired event's QR gets the generic invalid-link error, not "ended".
- *Alternative — scheduled sweep (cron)*: rejected for now; members' periodic background sync
  touches the event soon after grace in practice, so reap + push are prompt without new infra. A
  truly untouched event lingers as storage only. Revisit if unreaped storage ever matters.
- Push-before-delete ordering: after deletion the membership is gone, so the member list must be
  read first. The push is best-effort — a failed fan-out must not block the reap.
- The photo-**byte** route stays ungated, as today: byte keys are device-partitioned and
  event-independent, so an expired event cannot be "written to" through it, and the reap's
  existing reference-checked GC (from the leave cascade) is what reclaims bytes no surviving
  event references.
- The gate needs to tell a known device from a new one, which takes a `devices/` LIST — so the
  manifest `PUT`'s upstream budget becomes marker `GET` + devices LIST + object `PUT` (the
  listing doubles as the capacity count; it is the same small directory listing the union read
  already performs).

### D5: Status codes — one axis each

`409 Conflict` = capacity (new device over cap). `410 Gone` = time (new device during grace).
`404` = absent (never existed, or expired-and-reaped). Keeping the axes on distinct codes lets
the follow-up client map "full" and "ended" to distinct UI states without a body contract.

- *Alternative — `403` for full*: rejected; `403` is overloaded with attestation failures.
- *Alternative — `404` for ended (no distinct code)*: rejected; the follow-up UI needs to tell
  "full" from "ended" at the enrollment choke point.

### D6: Legacy markers are expired

A marker missing `endsAt`/`capacity` is treated as already past grace → reaped (with push) on
next access. Pre-release posture: deliberate wipe, no read-time synthesis of limits.

- *Alternative — grandfather as unlimited*: rejected by the owner; no legacy events are worth
  preserving, and a synthesis path (like the existing `startsAt` synthesis) would keep dead code
  alive indefinitely.

### D7: Constants live in backend config as source constants, carried on `Config`

`EVENT_CAPACITY` (10), `EVENT_DURATION` (30 days), `EVENT_GRACE` (1 day) join the existing
config module as **source constants** carried on the runtime `Config` object — NOT env vars.
The config module's documented law (capability `backend-deployment`, learned from a two-week
outage) is that the environment is never consulted for a non-secret: CI can ship code but not
config, so a value split across source and platform env rots. Every existing non-secret
(including the attestation TTL) follows this. Testability — the original motive for
env-override — is served the same way the attest TTLs are tested: tests construct a `Config`
with shortened windows and hand it to the app factory. (This supersedes the interview's
"env-overridable" phrasing, which predated reading the config module's contract.)

## Risks / Trade-offs

- [Phase-1 clients retry pointless `409`/`410`] → Accepted: both fall into existing generic
  failure paths; the follow-up change wires terminal handling. No retry storm risk: join is
  user-initiated, and upload cycles are OS-cadenced.
- [Members of a reaped event see silent, unexplained sync failure] → Accepted for phase 1; the
  silent push plus the follow-up's ended-state UI make it explicable later. Photos already saved
  on devices are untouched.
- [Lazy reap can fire the push late, or never for an untouched event] → Accepted: the likely
  toucher is a member's own background sync, which is exactly who the push is for. Cost of a
  never-touched event is storage only; a sweep can be added later without contract change.
- [Deploy retroactively kills all pre-existing events (D6)] → Deliberate; deploy note required.
  Operator should expect silent pushes to any live members on first touch.
- [Capacity overshoot under concurrent joins] → Accepted (D2); bounded by the number of
  simultaneous joiners, not unbounded.
- [In-window photo still pending after grace is dropped] → Accepted: grace shrinks the window to
  uploads more than a day late; beyond that, lifecycle simplicity wins.
- [`PUT` gains a listing round-trip (capacity count) per enrollment] → Enrollment is rare
  (once per device per event) and the same listing machinery already serves union reads; the
  known-device fast path (id present in either form) skips nothing it wasn't already reading.

## Migration Plan

1. Deploy backend. New events immediately carry `endsAt`/`capacity`; old events reap on next
   access (expected: one wave of silent pushes as members' sync touches them).
2. No client release required or included.
3. Rollback = redeploy previous backend: new markers' extra fields are ignored by old code
   (additive JSON), so rollback is clean. Events reaped while the new code ran stay gone —
   acceptable pre-release.

## Open Questions

None blocking. The exact env var names should follow the existing config module's naming
convention at implementation time.
