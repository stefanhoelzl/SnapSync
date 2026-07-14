## Why

An event has no notion of *when it began*. The only temporal fact on the marker is `createdAt` — the
instant the row was written to bunny — and the join gate seeds each joiner's capture-date cutoff from
it. That conflates two different things, and it breaks the ordinary case: a host who creates the event
the morning after the wedding hands every guest a cutoff of "this morning", so nobody's wedding photos
are in scope. The host has no way to say "the event started at 18:00 yesterday", and the guest has no
way to know when it did.

Giving the event a **start date** makes that sayable once, by the host, at creation — and it becomes
the thing joiners default to instead of an implementation detail of when a JSON blob was PUT.

## What Changes

- **The event marker gains `startsAt`** — chosen by the host at creation, defaulting to now. Required
  on `POST /events` in the canonical cutoff form `yyyy-MM-dd'T'HH:mm:ss'Z'`; unbounded in both
  directions (an event may start in the future). Immutable — there is no route to change it.
- **`startsAt` is a floor, not just a default.** A membership's effective cutoff is
  `max(chosen, startsAt)`, clamped **at join time** and persisted as `minPhotoDate`. A photo taken
  before the event started is never uploaded, whatever the member chose.
  - Consequence: **nothing syncs before the event starts** — not as a new runtime gate, but as a
    theorem. A photo cannot have a capture date in the future, so while `minPhotoDate >= startsAt >
    now` no photo can qualify. `UploadCycle` is untouched.
- **The create screen gains a start row** below the name field — `Starts 14 Jul 2026, 18:00` with an
  edit affordance — defaulting to now, frozen at screen open so the label never lies about what is
  sent.
- **BREAKING (UI): the join screen's free date picker is replaced by a two-preset selector** —
  `Now` | `Event start`, defaulting to `Event start`, with the resulting instant shown as a label.
  Pre-start, `Now` is disabled (it would clamp to the same value). Members lose the ability to pick an
  arbitrary cutoff, and can no longer choose one earlier than the host's start.
- **The status screen gains a not-started state** — `◷ Starts 14 Jul, 18:00` in the status-line slot
  below the QR, shown while `startsAt > now`. Precedence: `NeedsAccess > NotStarted > Syncing >
  InSync`.
- **`EventConfig` gains `startsAt`** — a required, non-null `String`, defaulted **at decode** to
  `minPhotoDate` so configs persisted before this change keep decoding. No forced re-join.
- **The date/time picker is reworked** into a single dialog (calendar + `[HH]:[MM]`; tapping the time
  swaps the calendar area for the dial), replacing today's two-step date→Next→time→OK flow.

## Capabilities

### New Capabilities

None. The start date extends existing capabilities rather than introducing one — it is a field on the
event marker with consequences in creation, joining, and cutoff selection.

### Modified Capabilities

- `event-creation`: the marker gains `startsAt`; `POST /events` requires it in canonical form and
  rejects any other shape; `GET /events/:eventId` synthesizes `startsAt = createdAt` for markers
  written before this change.
- `event-creation-ui`: `EventCreator.create` takes a start date; the create screen gains the start row
  and its default-at-open rule.
- `join-event`: the cutoff surface becomes the two-preset selector; the join use-case clamps the
  chosen cutoff to `max(chosen, startsAt)` and persists `startsAt` alongside it.
- `photo-date-cutoff`: **the safety spine is rewritten.** The cutoff is no longer purely a per-device
  choice — the event now supplies both a default and a floor. What survives is the invariant that
  matters: the floor can only ever **narrow** a membership's scope, never widen it, and the member
  still chooses freely above it. `EventConfig` gains `startsAt` with its decode default.
- `sync-status-screen`: the reduction gains a not-started health value and its precedence rule; the
  status line renders it below the QR. (`:domain:status` is **not** touched — `SyncHealth` lives in
  `:domain:presentation`, so the projection stays a clock-free ledger read.)
- `design-system`: a new start-date row component, a new cutoff selector, the reworked date/time
  picker, and the new status-line variant.
- `harness-world-model`: the world's event marker and mini-edge carry `startsAt`.
- `desktop-test-harness`: a forge preset for the not-started status.
- `full-stack-harness`: the world inspector's create-event control supplies a start date.

## Impact

**Backend** (`backend/src/app.ts`, `backend/src/validators.ts`): `EventMarker` type, `POST /events`
body validation, `GET /events/:eventId` legacy synthesis. New canonical-instant validator.

**Kotlin**: `:capability:event-creation-ui` (`EventCreator`, `CreateEvent`, `HttpEventCreationClient`,
`EventMetadataSource`), `:capability:join` (`EventDetails`, `HttpEventDetailsSource`, `JoinEvent`),
`:capability:config` (`EventConfig`, `Cutoff`), `:domain:presentation` (`StatusContainerHost`,
`JoinPhase`, `UiState`, the foreground tick), `:domain:status` (the not-started state),
`:domain:ui` (create + join screens), `:domain:ui:components` (four component changes), `:test:world`,
`:app:desktop`, `:app:desktop:ui`, `:app:ios` (composition-root wiring only).

**Explicitly untouched**: `:capability:upload` / `UploadCycle` and both upload tiers. The floor is
enforced once, at join, so no `startsAt` reaches the upload path — the one place a bug would mean
uploading a stranger's whole camera roll.

**No migration.** Old configs decode (`startsAt` defaults to `minPhotoDate`); old event markers are
patched at read (`startsAt = createdAt`). Existing members keep their event, their QR, and their
cutoff.
