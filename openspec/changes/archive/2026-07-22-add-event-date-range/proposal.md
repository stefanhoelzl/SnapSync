## Why

Today an event carries only a start (`startsAt`); its end is a coarse, server-fixed `startsAt + 30d`
storage backstop, and a member's contribution is bounded only from below (a single capture-date
cutoff). Hosts of a real short-lived event — a weekend wedding, a week-long trip — cannot say when the
event actually *ends*, and guests cannot bound the *tail* of what they share. This change lets the host
pick the event's **date range** at creation, and lets each guest pick their own **sub-range within it**
at join, so "what belongs to this event" is a window the host declares and every member sees before they
commit.

## What Changes

- **BREAKING (spec decision reversal):** `photo-selection-policy` gains a **capture-date upper bound**.
  The `2026-07-21-align-specs-with-mission` decision made the cutoff "a lower bound only … none is
  planned," on the premise that `endsAt` was a coarse 30-day backstop. This change makes `endsAt`
  **creator-chosen and precise** — the host's declared event window — which changes that premise: a photo
  taken after the host's chosen end is not a late event photo, it is a non-event photo, and the excluded
  window is **visible on the join surface before confirm** (never silent). admit-on-doubt is preserved for
  every photo inside the window (`≤ endsAt` still always admitted).
- The host picks an event **`[startsAt, endsAt]` range** at creation (required; pre-fills `[now, now+1d]`;
  only rule is `startsAt < endsAt` — no duration cap). `endsAt` becomes the event's window ceiling **and**
  its existing server lifetime, in one value.
- `POST /events` **accepts a client `endsAt`** (validated canonical, `startsAt < endsAt`); absent →
  legacy `startsAt + 30d` fallback, so old clients keep working. `endsAt` is now **creator-supplied at
  mint** rather than stamped from a fixed global duration.
- At join, the single cutoff choice becomes a **`[from, until]` range** clamped to the event window
  (`from ≥ startsAt`, `until ≤ endsAt`), defaulting to the **full window**. Presets: **From** {Event
  start · Now · Custom}, **Until** {Event end · Custom}. "Now" is offered only while
  `startsAt ≤ now ≤ endsAt`.
- Reconfigure lets a joined member edit **both** bounds (each re-clamped to the window). Narrowing
  re-excludes photos from the manifest/union exactly as raising the cutoff floor already does.
- A new **dual-handle datetime range picker** (tap start day → tap end day, two time wheels; invalid
  days/times greyed at join, unconstrained at create); a live **duration hint** on the create screen.
- The status screen shows an **"Event ended"** marker (prefixing the regular one-line health) when
  `now > endsAt`; sync still runs during the backend grace window.
- Existing memberships are **backfilled** on reconcile: fetch `GET /events`, persist `endsAt` and
  `maxPhotoDate = endsAt` (legacy events are capped at their 30-day mark — accepted).
- Dev/test surfaces: `SNAPSYNC_CREATE_EVENT` JSON gains `endsAt`; the `event-link` payload gains an
  optional `maxPhotoDate` override.

## Capabilities

### New Capabilities
<!-- none — this change extends existing capabilities -->

### Modified Capabilities
- `photo-selection-policy`: cutoff becomes a capture-date **range**; add an inclusive upper bound and
  reverse the "lower bound only, none planned" decision on the reframed `endsAt` premise.
- `event-limits`: `endsAt` is **creator-supplied at mint** (validated), not stamped from a fixed global
  duration; grace/expiry semantics unchanged. Note duration as the additive future paid-tier gate.
- `event-creation`: `POST /events` accepts and validates a client `endsAt` (fallback to `+30d` when
  absent).
- `event-creation-ui`: the create flow picks a `[start, end]` range with the range picker and shows a
  live duration hint; the minted `endsAt` flows to the auto-join.
- `join-event`: the join surface's cutoff row becomes a range row (From/Until presets + Custom);
  `JoinEvent` clamps `until ≤ endsAt` (new `clampToCeiling`) alongside the existing floor clamp.
- `reconfigure-membership`: a joined member edits both range bounds in place, each clamped to the event
  window.
- `event-link`: the link payload accepts an optional `maxPhotoDate` dev/test override (mirroring
  `minPhotoDate`), clamped on the far side.
- `event-rejoin-reconciliation`: reconcile backfills `endsAt` and `maxPhotoDate` for memberships stored
  before this change.
- `sync-status-screen`: render an "Event ended" marker on the joined health line when `now > endsAt`.
- `design-system`: a dual-handle datetime **range** field/dialog primitive (extends the single-datetime
  picker).
- `ios-app-shell`: the `SNAPSYNC_CREATE_EVENT` dev trigger JSON gains an optional `endsAt`.

## Impact

- **Domain (`:domain`)**: `EventConfig` (+`endsAt`, +`maxPhotoDate`), `Contribution.Since` (+upper
  bound), `Cutoff` (+`clampToCeiling`), `EventDetails.Found`/`JoinLoad.Found` (+`endsAt`), `JoinEvent`,
  `ReconfigureEvent`, `CreateEvent`, the `UploadCycle` selection filter (`cutoff ≤ creationDate ≤
  ceiling`), the device-manifest producer and status total (same seam), `EventLinkPayload`.
- **Adapters**: `HttpEventCreation` (send `endsAt`), `HttpEventDirectory` (parse `endsAt`), the iOS
  PhotoKit fetch predicate (optional upper narrowing; the `UploadCycle` filter stays authoritative).
- **UI**: `AppDateTimeField` (range picker), create/join/reconfigure screens, `StatusContainerHost`
  reduction (ended marker via existing `nowTick`), `CutoffFormatter` (range + duration formatting;
  humanized-duration via a KMP library, not hand-rolled).
- **Backend (Deno)**: `validators.ts` (`validateEndsAt`), `app.ts` `POST /events` mint path.
- **Dev/harness**: `SNAPSYNC_CREATE_EVENT`, `ForgeStatusHost` (+`EVENT_END`), forge/world harness create
  flow, **marketing screenshot refresh** (the create screen changes).
- **Rollout**: backend deploys on merge (fast) before any client that sends `endsAt` reaches a user via
  TestFlight (slow) — dependency direction safe by the existing pipeline; no gating needed.
- **Tests**: `commonTest` for `clampToCeiling`, the range filter, `Contribution` upper bound; integration
  tests for the join range and manifest re-exclusion; backend tests for `endsAt` validation and fallback.
