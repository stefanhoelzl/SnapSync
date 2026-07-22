## Context

An event today has one host-set date (`startsAt`) and a coarse, server-fixed lifetime
(`endsAt = startsAt + 30d`, capability `event-limits`). A member's contribution is bounded from below by
a single capture-date cutoff (`photo-selection-policy`), clamped to `max(chosen, startsAt)`. There is no
capture-date upper bound anywhere, and `2026-07-21-align-specs-with-mission` made that a deliberate
decision — "lower bound only … none is planned" — reasoning that a second end-bound mechanism "would add
a new silent way for a real event photo to fail."

This change gives the **host** a real `[startsAt, endsAt]` window at creation and gives each **guest** a
`[from, until]` sub-range within it at join. It therefore touches the backend mint path, the selection
policy, the join/create/reconfigure surfaces, the datetime picker, the status reduction, and the
existing-member reconcile — a genuinely cross-cutting change whose riskiest move is reversing a decision
made the same day. The reversal's justification is the core of this document.

Constraints that shape it: canonical `…Z` second-precision cutoff strings compared lexicographically; one
selection policy applied at one place, gating byte upload + device manifest + status total together;
`:domain` zone rules (model ← ports ← feature ← flow ← compose); `commonTest` for logic;
`:app:*` wiring-only; the App-Group config file as the config of record; single active membership as the
current contract.

## Goals / Non-Goals

**Goals:**
- Host declares the event's date window at creation; it is one value that serves as both the capture-date
  ceiling and the (already-existing) server lifetime.
- Guest picks a `[from, until]` sub-range within the event window at join, defaulting to the full window,
  visible before confirm; editable later via reconfigure.
- Preserve admit-on-doubt for every photo *inside* the window; add no *silent* exclusion.
- Keep the backend change minimal and old-client-safe; keep the mechanism paid-events-ready.

**Non-Goals:**
- No client-side lifecycle enforcement — grace/expiry stay entirely server-owned; the "Event ended" line
  is informational, sync continues in grace.
- No duration cap and no payment gate (creator-chosen duration is uncapped and free now; pricing is an
  additive future at the `event-creation` attach point).
- The guest's range bounds **uploads only** — downloads remain the full event union.
- No retraction/deletion of already-uploaded photos on narrowing (mirrors the existing floor).
- Not building concurrent multi-event membership or Android; not deepening the single-membership
  assumption.

## Decisions

### D1 — Reverse "lower bound only" because the object of the rule changed (not the reasoning)
`photo-selection-policy` gains an inclusive capture-date upper bound (`cutoff ≤ creationDate ≤ ceiling`).
The `2026-07-21` decision assumed `endsAt` was a **coarse 30-day storage backstop**: a ceiling there would
drop real event photos, silently. This change makes `endsAt` **creator-chosen and precise** — the host's
declared statement of when the event happened. Under that premise a post-`endsAt` photo is a *non-event*
photo, and the window (both bounds) is **shown on the join surface before confirm**, so exclusion is
neither coarse nor silent. admit-on-doubt is untouched for everything `≤ endsAt`. *Alternative considered
and rejected:* keep the ceiling opt-in only (default `until` = "no ceiling"), preserving the letter of the
old decision. Rejected because the host's window should be authoritative by default and because
"full event range" is the clearer mental model; the reframed premise makes the default ceiling defensible.

### D2 — One value: `endsAt` is both the capture ceiling and the server lifetime
The host picks a single end. It is stamped as the marker's `endsAt` (driving grace/expiry, unchanged) and
is the default upper bound of every guest's range. No second field, no divergence between "how long photos
count" and "how long the event lives." *Alternative:* decouple a capture window from a fixed 30-day
lifetime. Rejected: two ends to reason about, and the mission wants short events to actually be short.

### D3 — No conflict with `event-limits` grace
Grace lets *late-transmitted* in-window photos (`capture ≤ endsAt`) still land; the ceiling only excludes
*captures after* the window, which grace never promised. So the two coexist without contradiction — the
`event-limits` grace requirement stands as-is.

### D4 — Backend: `endsAt` client-supplied at mint, optional, `+30d` fallback
`POST /events` validates a present `endsAt` (canonical shape, real instant, `startsAt < endsAt`, **no upper
cap**) and stamps it; absent → the legacy `startsAt + eventDurationSeconds`. Enforcement is unchanged —
`event-limits` already reads only the marker's own `endsAt`. Old clients that send only `startsAt` keep
working. *Alternative:* require `endsAt` (400 if absent). Rejected — needlessly breaks any un-updated
client. Deployment order is safe by construction: the backend deploys on merge (fast), the client via
TestFlight/App Store (slow), so the backend accepts `endsAt` before any client sends it.

### D5 — Data model: mirror the floor with a ceiling, thread one seam
`EventConfig` gains `endsAt` (event window) and `maxPhotoDate` (guest ceiling), symmetric with `startsAt`
/`minPhotoDate`. `Cutoff` gains `clampToCeiling(chosen, endsAt) = min(...)` mirroring `clampToFloor`; both
clamps live in the single `JoinEvent` choke point (so hostile-link values are always bounded).
`Contribution.Since` gains the upper bound; because the manifest and the status total read the same
`Contribution`, the ceiling applies uniformly to upload + manifest + N with no third code path. The
`UploadCycle` filter is authoritative; the iOS PhotoKit fetch predicate may narrow the upper bound too but
is only an optimization.

### D6 — Join range: default full window, presets per handle, constrained picker
Default `[from, until] = [startsAt, endsAt]` (narrow, never widen — admits on doubt). From presets:
Event start · Now · Custom (today's set); Until presets: Event end · Custom. "Now" is offered only while
`startsAt ≤ now ≤ endsAt` (before start and after end it would fall outside the window). The picker greys
days/times outside `[startsAt, endsAt]` and blocks `end < start`, so an invalid pick is unreachable in the
UI; the `JoinEvent` clamps remain as defense. No rolling/ongoing "Now" ceiling is needed — ongoing upload
is simply `until = Event end` (a real future instant).

### D7 — Reconfigure edits both bounds; narrowing re-excludes (no retraction)
A joined member edits `from` and `until` in place, each re-clamped to the window. Widening re-enqueues
newly-eligible photos on the next cycle; narrowing removes them from the policy and thus the device
manifest/event union — exactly what raising the floor already does (no monotonicity guard exists today).
Already-downloaded copies elsewhere are not deleted; the orphaned object is reclaimed by the sweep. No
active retract/delete.

### D8 — Existing members: reconcile-time backfill to `= event end`
On reconcile, if a stored `EventConfig` lacks the new fields, fetch `GET /events`, persist `endsAt` and
`maxPhotoDate = endsAt`. Legacy events (server-fixed `+30d`) are thereby capped at their 30-day mark —
accepted: for a short-lived-event product a post-30-day photo is almost certainly not an event photo. Until
backfilled (e.g. `GET` unavailable), an absent `maxPhotoDate` is treated as unbounded so nothing is
silently dropped mid-upgrade; a `GET` 404 (event already gone) skips backfill.

### D9 — Status: "Event ended" marker, informational
When `now > endsAt`, the joined health line is prefixed "Event ended · <regular status>" — computed from
the now-stored `endsAt` and the existing `nowTick`, symmetric with the existing not-started line. Sync
continues (grace). This is a reduction in `StatusContainerHost` + rendering in `sync-status-screen`; it
respects the "one line, no numbers" contract.

### D10 — Picker & formatting: extend the custom widget, use a library for durations
The hand-drawn single-datetime dialog is extended to a dual-handle range calendar (tap start day → tap end
day, range highlight, two time wheels; same-day = tap twice; tapping days preserves the current wheel
times). Range display uses a compact adaptive format (`14 Jul, 18:00–23:00` / `14–21 Jul 2026` /
`14 Jul 18:00 – 21 Jul 23:00`). The create-screen duration hint ("Event lasts 5 days") uses an existing
KMP-compatible humanized-duration library rather than a hand-rolled formatter.

## Risks / Trade-offs

- **[Reversing a same-day decision looks reckless to a future reader]** → D1 is written into the
  `photo-selection-policy` delta and this design as an explicit premise-change, citing both decision
  records, so `git blame` on the reversed paragraph lands on a justification, not a contradiction.
- **[Legacy events get a ceiling the host never chose]** → Accepted (D8); bounded impact (post-30-day
  captures on a short-lived event), and unbounded-until-backfilled avoids a mid-upgrade silent drop.
- **[Capture ceiling reintroduces a way for an event photo to silently fail]** → Bounded to captures
  *outside* the host-declared, member-visible window; every in-window photo still admits on doubt.
- **[No duration cap on a shared storage zone]** → Accepted per interview; the single `snap-sync-dev` zone
  could hold a long-lived event, but the sweep still reclaims after `endsAt + grace`, and duration is the
  intended future paid lever, not an operational bug.
- **[Range picker is new offscreen-testable UI]** → Cover the constrained selection + clamp behavior in
  the harness-driver path and screen tests; refresh marketing screenshots (create screen changed).

## Migration Plan

1. Backend first (auto on merge to `main`): `validateEndsAt` + `POST /events` accepts `endsAt` with `+30d`
   fallback. Verified live before any client sends `endsAt`.
2. Client (`:domain` model/adapters → UI → dev/harness): new fields default-decode so pre-existing
   `EventConfig`s parse; reconcile backfill (D8) upgrades them.
3. Refresh marketing screenshots after the create screen lands.
- **Rollback:** the client change is behavior-additive and reversible; a reverted client simply stops
  sending `endsAt` (backend falls back to `+30d`) and stops applying a ceiling. No destructive data
  migration — backfill only writes new fields onto configs.

## Open Questions

- Exact humanized-duration library choice (must compile on `jvm` + `iosArm64`/`iosSimulatorArm64` in
  `commonMain`) — resolved at implementation, not a spec concern.
- Whether `design-system` needs a requirement-level delta for the range primitive or only implementation —
  decided in the specs phase (leaning: light delta for the new field's contract).
