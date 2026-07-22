## 1. Backend (deploys first — must accept `endsAt` before any client sends it)

- [x] 1.1 Add `validateEndsAt(raw, startsAt)` to `backend/src/validators.ts` — canonical `…Z` shape, real round-tripping instant (mirror `validateStartsAt`), and strictly `startsAt < endsAt`; no upper-duration cap.
- [x] 1.2 In `backend/src/app.ts` `POST /events`, read an optional body `endsAt`: when present, validate via 1.1 and stamp it as the marker's `endsAt`; when absent, keep the legacy `canonicalPlusSeconds(startsAt, eventDurationSeconds)` fallback. Keep `capacity` server-resolved and continue to IGNORE a client-supplied `capacity`.
- [x] 1.3 Return `400` for a present-but-invalid `endsAt` (bad shape / not an instant / `startsAt >= endsAt`); leave the response body shape (`{eventId,name,createdAt,startsAt,endsAt,capacity}`) unchanged.
- [x] 1.4 Backend tests: `endsAt` present-and-valid is stamped verbatim; absent falls back to `+30d`; `startsAt >= endsAt` and malformed `endsAt` are `400`; a client `capacity` is still ignored. Confirm `event-limits` lifecycle (grace/expiry) is unaffected (reads the marker's own `endsAt`).

## 2. Domain — model (`:domain` `model/`)

- [x] 2.1 `EventConfig`: add `endsAt: String` (event window ceiling / lifetime) and `maxPhotoDate: String` (member upper bound), each defaulting so pre-existing serialized configs still decode (absent `maxPhotoDate` = unbounded per D8).
- [x] 2.2 `Cutoff.kt`: add `clampToCeiling(chosen: String, endsAt: String): String = minOf(chosen, endsAt)` mirroring `clampToFloor`; unit-test lexicographic correctness on canonical `…Z`.
- [x] 2.3 `Contribution.Since`: add an upper-bound field (the ceiling); update `Contribution.of(...)` and both `compose/` call sites (`UploadCore.kt`, `SnapSyncApp.kt`) to thread it. `Contribution.None` unchanged.
- [x] 2.4 `EventDetails.Found` and `JoinLoad.Found`: add `endsAt: String` (required/non-invented on a successful details load).
- [x] 2.5 `EventLinkPayload`: add optional `maxPhotoDate: String? = null` (dev/test ceiling override), `encodeDefaults` still off; strict decoder keeps rejecting unknown keys.

## 3. Domain — ports (`:domain` `ports/`)

- [x] 3.1 `EventCreation.create(name, startsAt, endsAt): CreateOutcome` — widen the port signature; `CreateOutcome.Created` unchanged.
- [x] 3.2 Confirm the `EventDirectory` port surfaces `endsAt` on the found result (2.4) for the join gate to read.

## 4. Domain — features (`:domain` `feature/`)

- [x] 4.1 `JoinEvent.join(...)`: accept `endsAt` and the chosen upper bound; persist `minPhotoDate = clampToFloor(chosen, startsAt)` (unchanged) AND `maxPhotoDate = clampToCeiling(chosen, endsAt)`; store `endsAt`. Both clamps in this single choke point so hostile-link values are always bounded.
- [x] 4.2 `CreateEvent.create(name, startsAt, endsAt)`: pass `endsAt` through to `EventCreation.create`; route the minted event into the same join gate (unchanged flow), which now offers the range.
- [x] 4.3 `ReconfigureEvent`: allow editing BOTH bounds in place; re-clamp `from` to the floor and `until` to the ceiling; reconstruct both pre-fills from the persisted values (D7).
- [x] 4.4 `UploadCycle` selection filter: change `creationDate >= cutoff` to the inclusive range `from <= creationDate <= until` (upper inclusive, lexicographic), reading both bounds from the `Contribution`. Keep this filter authoritative over the platform fetch.
- [x] 4.5 Verify the device-manifest producer and the own-device status total consume the same `Contribution`, so the ceiling applies to upload + manifest + N with no third code path.
- [x] 4.6 `event-rejoin-reconciliation` backfill: on reconcile, a membership missing `endsAt`/`maxPhotoDate` fetches `GET /events` and persists `endsAt` and `maxPhotoDate = endsAt`; `GET` unavailable → leave unbounded (retry later, no silent drop); `GET` 404 → skip.

## 5. Adapters

- [x] 5.1 `HttpEventCreation`: send `endsAt` in the `POST /events` body (`{name, startsAt, endsAt}`); parse `endsAt` from the `201` (already returned).
- [x] 5.2 `HttpEventDirectory`: parse `endsAt` from the marker into `EventDetails.Found`.
- [x] 5.3 iOS PhotoKit fetch predicate (`fetchOptionsSince`): optionally narrow the upper bound too (optimization only — `UploadCycle` filter stays authoritative). No new autonomous `PHAsset` read under `LIMITED`.

## 6. Presentation (`:ui:presentation`)

- [x] 6.1 `CutoffFormatter`: add a compact-adaptive range formatter (same-day `14 Jul, 18:00–23:00`; multi-day whole-day `14–21 Jul 2026`; mixed `14 Jul 18:00 – 21 Jul 23:00`) and a humanized-duration helper backed by a KMP-compatible library (compiles on `jvm` + `iosArm64`/`iosSimulatorArm64` in `commonMain` — select the library here).
- [x] 6.2 `UserCommands`: widen `create`, `commitJoin`, and `reconfigure` to carry the end / upper-bound values; update `StatusContainerHost` entry points (`onCreateEvent`, `onConfirmJoin`, `onReconfigure`).
- [x] 6.3 `StatusContainerHost` reduction: derive the join `Ready`/`Committing` range default (full window) and gate "Now" to `startsAt <= now <= endsAt`; add the "Event ended" marker when `now > endsAt` using the existing `nowTick` + stored `endsAt`.
- [x] 6.4 `UiState`/`JoinPhase`: carry `endsAt` alongside `startsAt` where the range row and ended marker need it.

## 7. UI components (`:ui:components` — `design-system`)

- [x] 7.1 Extend `AppDateTimeField` into a dual-handle datetime range picker: single calendar with tap-start → tap-end (range highlight; third tap resets; same-day = tap twice), two time wheels (From/Until), tapping a day span preserves the current wheel times, `[min,max]` window greying, `end < start` blocked.
- [x] 7.2 Turn the event start-date row into an event date-**range** section (opens the range picker; shows the live duration hint).
- [x] 7.3 Turn the cutoff-preset selector into a range preset selector: From {Event start · Now · Custom}, Until {Event end · Custom}; "Now" disabled unless the present is in the window.
- [x] 7.4 Add content descriptions/tags on the new controls so the offscreen harness-driver can select them.

## 8. UI screens (`:ui:screens`)

- [x] 8.1 Create screen: event date-range section + duration hint; Create enabled when name set and `start < end`; pre-fill `[now, now+1d]` frozen at first composition.
- [x] 8.2 Join screen: From/Until range row defaulting to the full event window; reconfigure edits both bounds.
- [x] 8.3 Joined status line: render the "Event ended" marker prefixing the regular health line (informational; sync continues).

## 9. Dev / test surfaces & harness

- [x] 9.1 `ios-app-shell`: `SNAPSYNC_CREATE_EVENT` JSON accepts an optional `endsAt` passed through to `POST /events` (absent → backend fallback).
- [x] 9.2 `event-link`: decode/apply the optional `maxPhotoDate` dev override (clamped to `endsAt` in `JoinEvent`).
- [x] 9.3 `ForgeStatusHost`: add an `EVENT_END` constant; forge `create`/`joining`/`in_sync` render the range.
- [x] 9.4 Full-stack world harness + forge harness: create-with-range path exercised via the offscreen harness-driver.

## 10. Spec prose the deltas cannot carry (direct edits — archive gate #1/#2)

- [x] 10.1 Rewrite `openspec/specs/photo-selection-policy/spec.md` **Purpose**: reverse the "lower bound only … none is planned" paragraph to describe the capture-date **range**, on the reframed premise (creator-chosen precise `endsAt`; window visible before confirm; admit-on-doubt intact `≤ endsAt`); cite `changes/archive/<this>` as the decision record.
- [x] 10.2 Touch `openspec/specs/event-limits/spec.md` **Purpose** so its "future free/paid" prose notes duration is now creator-supplied at mint (uncapped/free), with pricing as the additive future gate.
- [x] 10.3 Update any mission/context prose asserting "no capture-date end bound exists, by decision" (e.g. `openspec/config.yaml` context and `CLAUDE.md`) to reflect the new window.

## 11. Tests

- [x] 11.1 `commonTest`: `clampToCeiling`, the inclusive range filter (`from <= creationDate <= until`, boundary-inclusive), `Contribution.Since` upper-bound threading.
- [x] 11.2 `commonTest`: `JoinEvent` clamps both bounds (floor + ceiling) from a hostile-link value; `ReconfigureEvent` both-bound edit and re-clamp.
- [x] 11.3 `:test:integration`: join with a narrowed range → correct `UiState` and world outcomes (objects landed within range; out-of-range excluded); narrowing at reconfigure re-excludes from the manifest/union; reconcile backfill seeds `endsAt`/`maxPhotoDate`.
- [x] 11.4 `CutoffFormatter` range + duration formatting tests (same-day / multi-day / mixed; humanized durations).
- [x] 11.5 Architecture guards still pass (`./gradlew :test:architecture` — zone/purity/shell gates) and `compileIosMainKotlinMetadata` (iOS proxy) is green.

## 12. Final gates & artifacts

- [x] 12.1 `./gradlew build` green (all targets + JVM/Compose-offscreen tests).
- [x] 12.2 `./gradlew architectureDiagrams` and commit the regenerated `architecture/` (diagrams check is required).
- [ ] 12.3 Refresh marketing screenshots (create screen changed): dispatch `screenshots.yml`, eyeball, commit `screenshots/`.
- [x] 12.4 `npx --yes @fission-ai/openspec@1.5.0 validate add-event-date-range --strict` green; run the three archive gates (placeholder-Purpose, delta-completeness per touched module, dead-types) before archiving.
