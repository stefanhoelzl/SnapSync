## Context

The joined screen (`JoinedLayer` in `:ui:screens`, reduced by `StatusContainerHost` in `:domain:presentation`)
shows today: a grey "SNAPSYNC" label, the event name with a rename pencil, a "Share this event" eyebrow over the
QR card ("Let someone else scan this to join"), one status line (`AppStatusLine`: ✓ In sync / ↑↓ arrows /
"Starts <date>, <time>" / the access and cannot-verify lines, with an "Event ended" marker above it), and the
⚙ ⇪ ⏻ row. The design was settled in an interview with HTML mockups (13 states, light and dark); this document
records the choices and the ones rejected.

Everything the new screen shows is already in the reducer's hands:
- **shared**: `SyncProgress.synced` / `SyncProgress.total` — `total` is the live gallery count already filtered by
  the selection policy (range + origin exclusions), `synced` is clamped to it.
- **received**: `DownloadProgress.downloaded` / `DownloadProgress.total` — `total` is the download projection's
  `stillArriving`, which excludes `UNIMPORTABLE` rows (`receiving-photos` D8).
- **the range**: `EventConfig.startsAt` / `endsAt`; **now**: `nowTick`, which already re-emits every minute while
  either boundary lies ahead and stops by itself after the end.
- **the direction**: the membership's share / receive switches on `EventConfig`.

## Goals / Non-Goals

**Goals:**
- A member can tell at a glance that they have joined, what the QR is for, how long the event lasts, and how
  many photos went out and came in.
- The counts and the arrows can never disagree: both derive from the same two pairs of numbers.
- Time is said in one place.

**Non-Goals:**
- No change to what is counted, uploaded or downloaded; no new source, store, timer or backend call.
- No host-vs-guest distinction (the app does not record who created an event, and must not start to for this).
- No change to the setup, join, create or settings screens.

## Decisions

### D1: Counts come back, derived from the arrows' own numbers
`2026-07-04-redesign-event-ux` removed "n of N images synced" because it measured a *library backup*. The
numbers now measure *the event*: `total` has since become policy-filtered (range, origin exclusions, limited
selection — `photo-sharing`) and the received side exists. `syncHealth` already derives each arrow from
exactly these pairs (`synced < total`, `downloaded < total`), so rendering the pairs cannot contradict the
arrows or "In sync".
*Rejected:* counts replacing the status line (mockup A) — the member preferred keeping the line word for word
and adding a quiet line under it; counts only while syncing (C) — hides the finished totals, the part people
enjoy; progress bars (D) — more room than the screen has under the QR hero.

### D2: One `counts` value, present only under In sync / Syncing
`Layer.Joined` gains `counts: SyncCounts?`, set by the reducer iff `health` is `InSync` or `Syncing`, `null`
otherwise (not started, no access, unverified, loading). The rule sits in the reducer beside the health
precedence, so the UI has no visibility logic and the harness/integration tests read it off `UiState`.
`SyncCounts` holds two `DirectionCount`s — `Off`, or `Progress(done, total)` — rendered as `n/N shared` while
`done < total` and `N shared` when complete.
*Rejected:* last-known numbers in every state — shows stale or zero numbers beside a warning (no access
collapses the live `total` to 0, which is exactly why `permission-on-status-screen` D3 hid them).

### D3: "Off" only when the direction is off AND has no work
A direction renders `Off` ("Not sharing" / "Not receiving") only when the membership switched it off **and**
its total is 0. If the device is nevertheless working in a switched-off direction, its numbers show. This
mirrors the comment above `syncHealth`: a display must never mask a mismatch between the direction contract
and what the system does — the download-only membership that uploaded a camera roll while reading "In sync"
is the precedent.

### D4: Timing is reduced in presentation, from the existing minute tick
`Layer.Joined` gains `timing: EventTiming` — `Upcoming(remaining)`, `Running(remaining)` or `Ended` — computed
from `startsAt`, `endsAt` and `nowTick` (the range itself is read off `Layer.Joined.membership`, which already
carries it). `remaining` is carried as one whole unit (`Days(n)`, `Hours(n)`,
`Minutes(n)`, floored; under a minute reads as "less than a minute"), so the UI only formats words. No new
timer: `nowTick` already ticks each minute until both boundaries pass, which is exactly the lifetime of a
changing phrase; after the end "ended" never changes. `Layer.Joined.ended` becomes a derived `timing == Ended`, so its readers are unchanged.
Days never become weeks (events are at most 30 days, and the member chose "ends in 2 days" style).
*Rejected:* times always in the range (A/B) and "ends today at 22:00" (C) — the member chose a days-only
range with an hours countdown on the last day (D).

### D5: The range is formatted by the design system, in the device zone
`appDateRangeLabel` in `:ui:components` (beside the other date labels — a screen never re-derives the app's
date format) renders `startsAt`/`endsAt` after `CutoffFormatter.toLocal` (the device zone read once per
process): `Sun 12 – Tue 14 Jul` (weekdays, as the approved mockup shows them), `Tue 30 Jun – Thu 2 Jul` across
months, the year only across years, and `Today 18:00 – 23:00` / `Sat 18 Jul 18:00 – 23:00` when both fall on
one local day. An end at exactly 00:00 is shown as the previous day, since it closes that day (an event
ending "Tue 00:00" lasts through Monday). A legacy membership with no stored end shows `From Sun 12 Jul`.
It is a separate label from the create/join surfaces' `appRangeLabel` (`14–21 Jul 2026`), which states a
chosen capture range with its times and year; the joined screen states the event's days at a glance.

### D6: Not-started loses its date; the ended marker leaves the status line
`SyncHealth.NotStarted` keeps its precedence rung but drops `startsAt`; `AppSyncStatus.NotStarted` renders
"Sharing starts with the event". `AppStatusLine` loses its `ended` / `endedDetail` parameters; the dates line
says "ended", and the waiting note ("waiting for 2 of 5 members") renders under the counts — it is only ever
shown under "In sync", where the counts are present too.

### D7: Heading: joined statement and dates under the name; name clamped to two lines
Order: grey "SNAPSYNC" · the name (large, bold, rename pencil, **at most two lines, then an ellipsis**) · a green
"You've joined this event" · the grey dates line. The joined statement is identical for host and guest.
*Rejected:* a "You're in" pill (disliked); "JOINED EVENT" in the eyebrow; app name as the title; a tinted band.

### D8: Invite copy
Eyebrow "INVITE OTHERS"; caption "Others join by scanning this with their camera". The caption still addresses
the member holding the phone and names no audience the reader could be ("guests" was ruled out earlier for
exactly that reason — the comment in `JoinedLayer` records it); "others" satisfies that.

### D9: Layout unchanged in order
The QR stays the hero between heading and status. *Rejected:* event card first with the invite below (A), and
the QR behind an "Invite others" button (C).

## Risks / Trade-offs

- [The `ui` detekt tier's `LongMethod`/`LongParameterList` ceilings may be hit by `JoinedLayer`] → extract the
  heading block and the counts line as their own composables with bundled parameters; raising a ceiling needs a
  stated forcing proof in the PR (`docs/architecture.md`).
- [The shared total is a live gallery count and can move while watched — a photo taken or deleted] → accepted;
  it is the same `N` the arrow uses, and the move is true.
- [Unimportable photos silently leave the received total] → same as the arrow today (`receiving-photos` D8);
  the loss reaches the operator via crash reporting.
- [Floored countdown reads "1 day" at 47 hours] → accepted; switching to hours below a day keeps the last day
  precise, which is where precision matters.
- [`UiState` is the rig's wire type] → the new fields are `@Serializable` model types; `:test:control` and the
  desktop mirror compile against them in the same build.

## Migration Plan

UI and presentation only; nothing persisted changes. Ships in a normal PR; rollback is a revert.

## Open Questions

(none)
