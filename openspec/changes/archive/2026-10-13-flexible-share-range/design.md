# Design

## Context

See proposal.md for the why. The current mechanics this change has to move:

- **The range is clamped in two places.** `JoinEvent` (via `clampToFloor`/`clampToCeiling`, `model/Cutoff.kt`)
  and `ReconfigureEvent` force the chosen `minPhotoDate`/`maxPhotoDate` into `[startsAt, endsAt]`. Every
  consumer (the upload cycle's `SelectionPolicy`, the manifest, the status total, the join-screen count) reads
  the persisted range and never the event window, so the clamps are the only place the event window enters what
  is shared.
- **The calendar's bounds are one value.** `presentation/range/RangeBounds` carries `earliest`, `ceiling` and a
  `LatestUntil` (the longest end for a start). The create screen uses `lastingAtMost(30 days)`; join and
  reconfigure use `within(start, end)`. The gestures and wheels read only `RangeBounds`.
- **The presets live in presentation.** `RangeChoice` (`WHOLE_EVENT`/`FROM_NOW`/`CUSTOM`, in `:ui:state`),
  `RangeResolution` and `RangeForm` resolve a preset into `from`/`until`. `nowAvailable` hides From now outside
  the window. The UI (`AppShareRangeRow` + `RangePickerDialog`) draws the presets as chips inside the dialog.
- **Settling is tied to the event's end.** `DeviceManifestProducer.produce(settled = …)` publishes `final` once
  "the event's range has ended" since the last discovery, and the api stores `final && ended`
  (`api/src/routes/v2.ts`, `applyPublish`). The membership state follows each publish both ways
  (`sharing`↔`settled`, `db.ts`). The receive-side position report (`EventCompletion`) is gated on the event's
  end as well.
- **Received-photo recognition reads the event window.** `ReceivedPhotoAdoption.adoptOrFail` reads the whole
  union, then `MarkedPhotoLookup.markedIn(startsAt, endsAt)`. Its selection pre-check `beforeSelectionPublished`
  calls `markedAmong(…, startsAt, endsAt, …)` over the new snapshot, with no network.
- **The server does not check capture dates.** No api route compares an asset's `creationDate` with the event's
  dates, so photos outside the event's dates need no api change to upload, list or download.

## Goals / Non-Goals

**Goals:**

- One range rule, stated once in `:domain:model` and used by the calendar, the join, the reconfigure and the
  settle: at most 30 days, overlapping `[startsAt, endsAt]`.
- No existing membership changes meaning. Every stored range lies inside its event, so it satisfies the new rule
  as it is.
- Older app versions in the same event keep working without an update.

**Non-Goals:**

- No change to the event's own range, its open-until date or the deletion rules (`event-lifetime`). Only *which
  member is done when* moves.
- No change to "nothing is uploaded before the event's start". Photos from before the start wait for it.
- The capture range still never leaves the device. The api learns only `final`, as today.

## Decisions

### D1. One rule in the model; refuse, never clamp

Replace `clampToFloor`/`clampToCeiling` with one pure check in `model/Cutoff.kt`, for example
`shareRangeAllowed(from, until, startsAt, endsAt)`: `from ≤ until`, `until − from ≤ 30 days` (the event limit,
the same constant the create screen uses), `until ≥ startsAt` and `from ≤ endsAt`. `JoinEvent` and
`ReconfigureEvent` call it. A range that fails is refused: the join answers a failure and the reconfigure applies
nothing. Both log at Error, so it reaches crash reporting.

*Alternatives:* clamping to the nearest valid range (unpredictable, and it hides bugs), or falling back to the
event's dates (silent). Rejected in the interview. In a shipped build no path can produce an invalid range (the
calendar greys it out, the presets are valid by construction, and invite-link range hints are `Ignored`), so a
refusal can only mean a bug or a rig caller's mistake. Both should be loud. The rig's `autoJoin` hint path
therefore gets a refusal it can assert on.

### D2. `RangeBounds.aroundEvent(start, end, longest)`: the calendar enforces D1 by greying

Add a third factory beside `lastingAtMost` and `within`. It sets `earliest = start − 30 days` and
`ceiling = end + 30 days`, sets `longest = from + 30 days` (the create screen's `LatestUntil`), and adds an
**earliest end for a start**, `max(from, start)`, which `within` never needed. The gestures (`pickDay`,
`dragEndTo`, `placeEnd`, `keepValidTime`) already ask the bounds what a start and an end may be, so the overlap
rule enters through that one new question. The calendar view adds "event day" marking from `start..end`
(drawn under the range highlight, with a spoken `calendar_event_day` label).

*Alternative:* a free pick with Save disabled and a reason line. Rejected (interview): the create screen greys,
and one calendar should behave one way.

### D3. Presets on the screen; the dialog becomes calendar-only

- `RangeChoice.WHOLE_EVENT` is renamed `EVENT_START`. It is the wire name of a serialized `:ui:state` enum, and
  `:ui:state` crosses the control channel, so the rig client and the desktop mirror are rebuilt in the same
  change. No persisted value carries it.
- `AppShareRangeRow` becomes a chip row (event start · from now, only when `nowAvailable` · custom), followed by
  the resolved range label and the detail line (count only, with no preset prefix). Tapping custom calls the
  existing `onOpen`. Tapping the custom chip while it is already selected reopens the calendar on the current
  range.
- `RangePickerDialog` loses its `presets` parameter; the join and reconfigure callers stop passing chips.
- `RangeView` gains `reachesOutside: Outside?` (`BEFORE`/`AFTER`/`BOTH`), computed in presentation from
  `from/until` versus `windowStart/windowEnd`. The screen maps it to `share_outside_*`, so the screen stays
  decision-free.
- From now stays **hidden** outside the window. This contradicts the current `app-experience` text ("shown
  disabled, not hidden"), which the code never followed (`fromNowOffered` omits the chip). The delta replaces
  that requirement, which settles the drift.

### D4. Settle at the membership's own end

The settle condition moves from "the event's range has ended" to "the membership's `maxPhotoDate` has passed".
It is computed where `produce(settled = …)` is decided, from the persisted config, and still requires a
discovery since that moment, as today. Because the state follows each publish both ways, a later widening that
moves `maxPhotoDate` into the future simply publishes `final = false` again.

On the api, `applyPublish` stores `final` as declared (dropping `&& ended`). This is safe for older builds:
they declare `final` only after the event's end, which the guard allowed anyway. "Finished" still requires the
open-until date to have passed (the store's predicate), so an early `settled` finishes nothing before then.

The **receive** side (`EventCompletion`, the position report) stays gated on the event's end. A member's
receiving does not depend on their own range, and every other member's photos may still arrive until the
event's end.

*Alternative:* settle at `max(own end, event end)`. Rejected by the user. A strictly early settle lets a
share-only member who left early be done without coming back (the `event-lifetime` scenario).

### D5. Recognition over the union's actual dates; the pre-check by mark alone

- **Full pass.** `adoptOrFail` already holds the open foreign refs with their `creationDate`. It searches the
  library from `min(creationDate)` to `max(creationDate)` of those refs, widened to at least the event's own
  range (`receivedSearchWindow`; `MarkedPhotoLookup.markedIn(from, until)` takes instants instead of
  `EventStart`/`EventEnd`). It costs nothing extra and needs no rule about how far a member may reach. It is never
  narrower than the event's range, because a received photo's library date can drift from the union's (Android
  fills it from EXIF), and the event's range was the margin the pass relied on before.
- **Selection pre-check.** `beforeSelectionPublished` stops filtering by date: any SnapSync-marked primary the
  download store does not know withdraws recognition. Its own imports are known, so they never trigger it. A
  marked photo from another event triggers one full pass that adopts nothing. That is rare and harmless.

*Alternatives (interview):* always pausing until the union is read (a whole-union read per selection change,
and every own import is one); a ±30-day window (a second copy of the range rule to keep in sync); caching the
union's span (state that goes stale as members join).

### D6. Copy

The English and German strings are as drafted in the interview (`share_outside_*`, `range_event_start`,
`share_detail_*` without the preset, `access_range_body`, `create_window_note`, `calendar_event_day`, and the
dropped `share_range_whole_event`/`share_range_from_now`). The German custom chip is "Anpassen", so the three
chips fit one row. Outside the app: the privacy policy (`site/src/pages/index.astro`) and the store listing
(`metadata/listing/en-US.json`) change, and pillar P3 stays. `openspec/config.yaml` MISSION and the CLAUDE.md
overview are restated.

### D7. The start gate becomes explicit

"Nothing is uploaded before the event's start" was a theorem of the clamp: `minPhotoDate ≥ startsAt > now` admits no
photo (`Cutoff.kt`). Once a member's floor may lie before the start, that theorem is gone, and pre-start photos
would upload into an event that has not begun. The membership's policy derivation (`selectionRulesFor(config, …)`,
the one door both uploaders, the status total and the album gather take) gains `now`: while `now < startsAt`, its
floor is `max(minPhotoDate, startsAt)`, which restores the theorem exactly as today; from the start on, it is the
member's own `minPhotoDate`. The join-screen count uses the named-parameter door with the raw range, because it
promises what will be shared once the event runs. `now` comes from `ConfigService`'s clock, beside `hasEnded`.

*Alternative:* a separate "not started" branch in the upload cycle. Rejected: the policy is the one place that
decides what is shared, and the manifest, the status total and the album must agree with the upload (capability
`photo-sharing`, "What the member is told is shared is exactly what is shared").

## Risks / Trade-offs

- **An older build's reinstall misses out-of-window received photos.** An old client recognises marked photos
  only inside the event window, so after a reinstall it may re-share a photo another member dated before the
  start. → Accept until those members update; the same gap class `receiving-photos` already accepts for unmarked
  photos. Raising `MIN_APP_VERSION` is not worth it for this.
- **A late-ending member holds the event unfinished.** A guest's range may end up to 30 days after the event, so
  the photos stay on the server longer. → Bounded by the existing deletions: 3 days after the last join or photo,
  and 30 days after the open-until date. Since a range is at most 30 days and overlaps the event, its end never
  passes `end + 30 days`, so the retention cap is unaffected.
- **A member could pull in a wide range by mistake.** → The calendar shows the event's days, the outside-reach
  line names it before Join, the live count shows the number, and narrowing later withdraws the photos.
- **Status lines after the end.** "Waiting for N members" counts a late-ending member as still sharing until
  their own end. This is honest, but can read as stuck. → No change; it says who is still sharing.
- **The serialized enum rename** (`WHOLE_EVENT` → `EVENT_START`) breaks an old rig client against a new host. →
  Both come from the same commit; the rig is never mixed across builds.

## Migration Plan

No data migration: stored ranges already satisfy the new rule. The api change (accept `final` before the end)
ships in the same PR. The api is deployed on merge and is backward compatible with every app build. The app's
new settle timing only takes effect for builds that carry it. Rollback is a revert of both; any `settled` state
written early then just flips back on the next publish.
