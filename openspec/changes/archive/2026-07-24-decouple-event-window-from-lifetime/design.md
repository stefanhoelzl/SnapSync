## Context

`endsAt` is one value serving two purposes. `changes/archive/2026-07-22-add-event-date-range` decided
this deliberately (its `D2`, "One value: `endsAt` is both the capture ceiling and the server lifetime"),
reasoning that two ends would be two things to reason about. The consequence is that the event dies
`endsAt + 1d` (`event-limits` grace), so the storage window is the photo window plus a day.

That coupling is what forces every failure this change removes: a `410` for a guest who scans late, a
lost photo whose OS-scheduled upload had not drained, and a collection window for a two-day event that
is three days long. It is also why duration was left explicitly uncapped — with `endsAt` as the
lifetime, capping duration would have capped the event itself — leaving an unbounded storage commitment
on the single `snap-sync-dev` zone that holds real users' photos.

Constraints that shape the design: canonical `…Z` second-precision instants compared lexicographically;
a write-once marker with no rewrite path and no compare-and-set in the storage backend; `:domain` zone
rules (`model/` ← `ports/` ← `feature/` ← `flow/` ← `compose/`), flows coordinating and features
deciding; the App-Group config file as the membership record of record; single active membership; the
backend deploys on merge while the client ships via TestFlight.

## Goals / Non-Goals

**Goals:**
- Separate the capture window from the storage lifetime, so a late guest can still join and contribute
  and a slow upload still lands.
- Bound both: window ≤ 30 days, storage life ≤ 30 days.
- Reclaim an emptied event early, without making that a promise anyone can build on.
- Give a device a safe, bounded way to notice its event is gone and return to the unjoined state.
- Keep enforcement reading per-event stamped values, so a constant change never reaches a live event.

**Non-Goals:**
- No change to `photo-selection-policy`, the capacity rule, the device manifest, or the sweep's asset
  phase.
- No client-side lifecycle enforcement beyond the guarded self-leave; grace/expiry stay server-owned.
- No warning push, no "closing soon" notification, no retention UI beyond the join gate.
- Not building concurrent multi-event membership, paid events, or Android.

## Decisions

### D1 — Reverse `add-event-date-range`'s `D2`: one value, two questions

`endsAt` becomes **only** the capture-date ceiling. The lifetime becomes its own stamped field. The
earlier decision's premise was that a second end means "two ends to reason about"; what it actually
produced was one end answering a question it was never asked — *how long do we keep the bytes?* — with
the answer to a different one — *when did the party stop?* The two have no reason to coincide, and the
mission's "short-lived event" argument (the reason `D2` gave for fusing them) is preserved by capping
the **window** at 30 days rather than by killing the event when the window closes.

*Alternative considered:* keep the fusion and merely lengthen the grace period. Rejected — grace is
defined as *joins closed, members still sync*, so lengthening it still refuses the late guest, which is
the case this change exists to serve.

### D2 — Stamp a lifetime **duration**, not a delete-by instant

The marker gains `lifetimeSeconds`. The delete-by is derived at read time as
`anchor(marker) + lifetimeSeconds`.

This splits the two properties that matter. The **value** is per-event and immutable, so changing the
constant later cannot move a live event's death date — the same reason `capacity` is stamped despite
being a global today (`event-limits`, "a configuration change does not reach existing events"). The
**anchor formula** stays in the shared lifecycle module, so it can be retuned without rewriting a single
metadata object.

This is a deliberate, narrow exception to `event-limits`' rule that enforcement reads only the marker's
own stamped fields: an anchor-policy change in code *does* reach live events. That is the intent, and
the spec says so rather than leaving a future reader to discover it.

*Alternative considered:* stamp an absolute `deletesAt`. Rejected — it freezes the anchor policy into
every marker ever written, so any correction to it needs a migration over storage. *Alternative
considered:* read the lifetime from live config with no marker field at all. Rejected — shortening the
constant would then delete events out from under active members with no warning.

### D3 — Anchor at `max(createdAt, startsAt)`

Two hazards bracket the naive `startsAt + 30d`:

- **Back-dating** — a host creating an event for a trip five weeks past would stamp a delete-by already
  in the past, and the event would be swept the same night, before anyone scans the QR.
- **Create-early** — a host creating three weeks before the trip, anchored on `createdAt`, gets an event
  that dies nine days in.

`max(createdAt, startsAt)` handles both. It also keeps the storage invariant honest in the sense that
matters: uploads are capture-date-gated to `≥ startsAt`, so no *byte* can exist before the anchor. Only
the marker JSON — a few hundred bytes — can outlive 30 days, and only for an event created early.

*Implementation trap:* `createdAt` is not in canonical cutoff form, so the anchor must parse both values
to epoch ms. Every other date comparison in this codebase is a lexicographic string compare, and doing
that here silently produces the wrong anchor.

### D4 — The tail is `30d − window`, and is zero at the maximum

`window ≤ 30d` and `storage life ≤ 30d` together determine the post-window tail. No formula gives every
event both a full 30-day window and a tail. A weekend event gets ~28 days to finish transferring; a host
who deliberately picks the full 30-day window gets none, and their last day's photos can die in the
upload queue.

Accepted rather than papered over. *Alternatives considered:* cap the window at 23 days to guarantee a
uniform 7-day tail (rejected — "max 30 days" would mean 23 to the host, which reads as arbitrary); stamp
`deletesAt = endsAt + 7d` and let lifetime reach 37 days (rejected — breaks the ≤30d storage invariant).

The absent-`endsAt` fallback stays at `startsAt + windowMax`, i.e. the maximum window and therefore a
zero tail. Harmless for exactly the clients that hit it: a build old enough not to send `endsAt` also
predates the capture ceiling and never enforces `endsAt` locally.

### D5 — Emptiness is opportunistic reclamation; the deadline is the guarantee

The sweep also deletes an event that has been joined at least once (`devices/` has at least one child)
and now has zero **active** manifests. Departed `.left.json` siblings still exist and still pin their
bytes; they simply stop keeping the event alive.

The "ever-enrolled ≥ 1" clause is load-bearing, not defensive: `CreateEvent` mints and then routes the
creator through the **same** join gate a scanned QR uses, non-auto-confirmed, so `POST /events` *always*
produces a zero-device event — and mint-only `SNAPSYNC_CREATE_EVENT` never joins at all. Without the
clause the sweep would delete a fresh mint before the host confirms.

Emptiness is explicitly **not a promise**. `LeaveEvent` clears local config as its second awaited step
and dispatches the backend `DELETE` fire-and-forget on the app-lifetime scope, best-effort, never
retried. A leave that never reaches storage leaves `<id>.json` active, so the event does not empty. The
mechanism that produces the accepted abandon-leak is the same one that prevents premature emptiness
deletion. Specs and user-facing copy must therefore lead with the deadline and treat emptiness as a
bonus.

*Alternative considered:* a grace period after emptying, so an accidental last-leave can rejoin.
Rejected — leaving is an explicit act, and a post-empty timer is in any case dominated by the deadline
(an event can only empty at some `X ≥ createdAt`, so `X + 30d ≥ anchor + 30d` always).

### D6 — The lifecycle gate collapses entirely

`classifyEvent`'s `live`/`grace` split, the `410` on late enrollment, and `EVENT_GRACE_SECONDS` are
deleted. What remains is a validity check: a marker missing or carrying an unparseable required field is
`gone` (404 to the gate, swept by the sweep), everything else is served.

Joins are open for the whole life of an event, bounded only by capacity. This is the point of the
change — a guest who joins after `endsAt` contributes normally, because their photos were *captured*
in-window and are still in their library. Only the "Now" preset is meaningless there, and the range
picker already suppresses it outside `[startsAt, endsAt]`.

Removing `410` is client-invisible: no Kotlin source keys on it anywhere.

### D7 — The sweep stops notifying

Notify-before-delete is removed, along with the admin-key authorization on the notify route, the
`ADMIN_NOTIFY_KEY` secret, and the sweep's only dependency on the Edge Script.

Three independent reasons:

1. **The channel cannot carry the message.** `event-notify-endpoint` forbids any caller-supplied
   payload; a notify means exactly "something changed, go sync". Deletion is the negation of freshness.
2. **It arrives after what it announces.** The sweep must notify before deleting (once the marker is
   gone the route 404s), but the deletes follow within milliseconds while APNs delivery and a background
   wake take orders of magnitude longer. The device essentially always wakes to an already-deleted
   event, then burns a scarce wake running a full cycle against it — producing `Error`-severity lines
   that `crash-reporting` ships to Bugsink.
3. **Acting on it would be in the wrong place.** `SilentPush` and `DownloadBackstop` both promise
   "nothing mints, clears, or leaves" precisely because a background wake may land pre-first-unlock and
   read an unreadable config as *absent*. The self-leave belongs where the device is unlocked and config
   was freshly re-read — see D8.

Cost: none for correctness. Discovery latency is identical either way (the next foreground fetch), since
`SilentPush` does not fetch event details and `DownloadBackstop` never touches the network.

### D8 — Self-leave requires two witnesses, one of them offline

A device tears down its own membership only when **both** hold:

1. the event-details fetch resolves to a definitive absence (`EventDetails.NotFound` — the port already
   separates this from `Failed`, which absorbs offline, transport, non-404 status, and parse), **and**
2. the device's own persisted delete-by has passed.

**Why the second witness is not paranoia.** A systemic false 404 is a shape this repo has already
shipped: `config.ts` records that a deployed configuration once named a storage zone that does not
exist. A missing zone means every marker read misses, and `app.ts` maps a 404 marker read to a 404
event (only a *non*-404 read failure becomes 502). Every foregrounding device would then destroy its
membership — unrecoverably, because the config is the only record of the join and the QR is on someone's
fridge. That configuration error survived two weeks with CI green throughout, so hysteresis of N
observations would not have helped.

**Why the second witness is exact rather than heuristic.** Emptiness deletion requires every enrolled
device to hold a winning `.left.json`. A device that departed has no config — `LeaveEvent` clears it
before notifying the backend. Therefore *a device that still holds a membership can only ever be looking
at a deadline deletion*, and comparing against its own deadline is sound, not approximate.

The asymmetry falls the safe way. Every error mode is "held on too long", never "destroyed wrongly": a
download-only member that never enrolled can outlive an emptiness sweep and keeps a phantom membership
until its deadline (bounded, self-healing), and a legacy config with no stored delete-by simply never
self-leaves until the reconcile backfill lands — mirroring `add-event-date-range`'s `D8`, where an
absent `maxPhotoDate` is treated as unbounded so nothing is silently dropped mid-upgrade.

This makes the invariant sharper rather than weaker, and it stays literally true in all three background
flows: **no fetch outcome is ever destructive**; the foreground clears a membership only when the
device's own persisted deadline has passed *and* the backend independently reports absence.

*Alternative considered:* act on the first `NotFound` unconditionally. Rejected once the delete-by
became a served field for the join gate (D10), which dropped self-verification's marginal cost to a
single config field plus a backfill that rides in an existing save.

### D9 — The fold is one rule, renamed for its actual need

`EventName` already owns more than its name: the event-name refresh *and* the `endsAt`/`maxPhotoDate`
window backfill. It gains the delete-by backfill and the absence verdict, which are the same job —
**reconcile the persisted membership against freshly fetched event details, and say what happened** — so
it is renamed to `MembershipRefresh` accordingly and answers a sealed `RefreshOutcome` (refreshed /
inconclusive / absent).

**The teardown lives in the feature, not in a `when` in the flow.** This design originally had
`Foreground` and `Provision` switch on the outcome and fire a compose-built leave effect, by analogy with
`EventName.fetchNeed(name) → TitleNeed`. The **flow transcriber refused it**: its closed grammar admits a
`when` only at statement level, and this one necessarily sits inside an escaping `scope.launch` (the fetch
is network I/O) inside the `activeEventId()?.let` guard, where only a plain call is transcribable. An
untranscribable flow fails generation, which is a gating build failure (specs `architecture-diagrams` /
`module-architecture`) — and the transcriber's own stated remedy is *"sink the rule into a feature."*

So `MembershipRefresh` performs the teardown itself, by holding `LeaveEvent` — a **sibling within
`feature/membership`**, so this is not a feature-blindness breach — and the flows are back to a
straight-line feature call. The constraint produced the better design: with the consequence attached to
the verdict, "both triggers route the answer identically" holds **by construction** rather than by two
call sites agreeing, which is exactly the asymmetry D9 set out to prevent.

The deadline comparison itself is a pure lexicographic compare on canonical instants and is pushed into
`model/`, beside `clampToFloor`/`clampToCeiling`, where it is testable in isolation.

`compose/`'s `fetchEventDetails` effect is widened from `suspend (String) -> JoinLoad.Found?` to carry
the sealed result. That `as?` cast is the deliberate blindness this change removes; the guard in D8 is
what replaces it.

*Alternatives considered:* a separate `EventAbsence` feature beside `EventName` (rejected — splits one
job across two features and encodes their required call order in the flow); putting the guard inside
`LeaveEvent` so no caller can bypass it (rejected — it would make teardown depend on the
directory-fetch vocabulary, so leaving would know *how* absence was determined).

### D10 — Retention is stated once, on the join gate, server-owned

`GET /events/<id>` serves the derived delete-by. The join gate shows it, plus a static line stating the
30-day ceiling. Nothing else changes: the create screen, the status screen, and the `"Event ended ·"`
line are untouched, so no marketing screenshot refresh is needed.

The join gate is the right and only surface because **the host passes through it too** — `CreateEvent`
routes the minted event into the same gate a scanned QR uses. One server-served value reaches both
audiences, so the 30-day constant never exists client-side and cannot drift.

*Alternative considered:* a live delete-by preview on the create screen, computed client-side while the
host moves the picker (which would have made the window/tail trade of D4 visible at the moment of
choice). Rejected — the create screen is pre-mint, so the value could only be client-derived, which
duplicates a server-owned policy and would need a `:test:architecture` guard to pin the constant against
`api/src/config.ts` (the pattern used for `linkDomain`). The cost is that the host sees the deadline one
screen later, after minting.

The wording leads with the ceiling in both the gate line and the privacy policy, because per D5 the
emptiness clause is not a promise: *deleted within 30 days of the event's start; often sooner, once
everyone has left*.

### D11 — Capacity is unchanged; the paid boundary is device count only

Capacity stays 10, counted as distinct device ids **ever enrolled** (active ∪ departed), with leaving
freeing no slot. The interaction with D5 is benign: if enough devices leave for slots to matter, the
event is empty and gone anyway.

The 30-day window and 30-day lifetime are fixed for everyone, permanently. The only future paid lever is
device count — which is already per-event and stamped, so raising it needs no enforcement change. This
narrows `event-limits`' current framing, where duration was the lever precisely because it was uncapped.

### D12 — Constants and legacy markers

`EVENT_DURATION_SECONDS` splits into `EVENT_WINDOW_MAX_SECONDS` (validates `endsAt - startsAt`, and is
the absent-`endsAt` fallback) and `EVENT_LIFETIME_SECONDS` (stamped). Same value today; they answer
different questions and only one is ever stamped, so collapsing them would make a future divergence a
silent two-place behavior change. `EVENT_GRACE_SECONDS` is deleted.

A marker carrying `startsAt` but no `lifetimeSeconds` derives its deadline from the configured constant
at read time — one lifecycle path, no second rule kept alive. A marker missing `startsAt` stays `gone`
and is swept, which is today's behavior.

## Risks / Trade-offs

- **[A systemic backend 404 wipes memberships]** → D8's second witness: a device's own clock cannot be
  moved by a misconfigured backend, so a zone-wide fault degrades to "every device shrugs" instead of
  "every device self-destructs".
- **[Reversing a decision from an adjacent change reads as churn]** → D1 states the premise change
  explicitly and cites `add-event-date-range`'s `D2`, so `git blame` on the reversed paragraph lands on
  a justification rather than a contradiction.
- **[A maximum-length window has no upload tail]** → D4, accepted and documented; the join gate states
  the retention deadline so the consequence is visible before anyone commits.
- **[A cohesive group kills its own previous event]** → Single active membership means joining B leaves
  A, so a group migrating together empties the old event and it dies that night, up to ~4 weeks early.
  Accepted: every member already holds the photos in their own library — that is the product. The one
  real loss is the no-app **web** audience, who are not devices and cannot keep an event alive.
  `site/src/pages/join.astro` already branches on a `404` marker and renders *"Invalid or expired
  link"*, so it degrades truthfully with no change.
- **[Emptiness read as a promise by a future reader]** → D5 names it a non-promise and gives the
  mechanism (fire-and-forget leave notify); the specs state the deadline as the only guarantee.
- **[Removing a deliberate blindness in `compose/`]** → The `as?` that flattened `NotFound` into `null`
  existed to make fetch outcomes non-destructive. It is replaced by a strictly stronger property (D8),
  not merely deleted, and the three background flows keep the original invariant verbatim.
- **[Removing a required secret]** → `readConfig` throws on missing secrets, so *adding* one is the
  dangerous direction; removing `ADMIN_NOTIFY_KEY` is safe in either deploy order. It can be deleted
  from the Edge Script and GitHub after the code stops reading it.
- **[Anchor uses a non-canonical `createdAt`]** → D3 names the trap; both values are parsed to epoch ms
  and the anchor is unit-tested against a back-dated and a create-early marker.

## Migration Plan

1. **Backend first** (deploys on merge): marker gains `lifetimeSeconds`; window-cap validation; details
   serves the delete-by; `410`/grace removed; sweep predicate becomes deadline-or-empty and stops
   notifying. Old-client-safe — the absent-`endsAt` fallback is kept and nothing keys on `410`.
2. **Retire the secret** once no code reads it: remove `ADMIN_NOTIFY_KEY` from the Edge Script
   environment and the GitHub Actions secret.
3. **Client** (`:domain` model → feature/flow/compose → UI): `EventConfig.deletesAt`, the renamed fold
   returning a sealed outcome, the widened `compose/` effect, the guarded self-leave, the join-gate copy.
   Inert against an un-migrated backend: with no served delete-by, the self-leave never fires.
4. **Site**: privacy policy retention statement.
- **Rollback:** the client change is reversible — a reverted client stops self-leaving and stops showing
  the retention line. The backend change is reversible in code; markers already carrying
  `lifetimeSeconds` are simply ignored by a reverted classifier. No destructive data migration: nothing
  rewrites an existing marker, and the client backfill only adds a field.

## Open Questions

- The renamed fold's exact identifier (`MembershipRefresh` vs another need-named form) and the sealed
  outcome's arm names — resolved at implementation, not a spec concern.
- Whether the join gate's retention line is design-system copy or screen-local — decided in the specs
  phase.
