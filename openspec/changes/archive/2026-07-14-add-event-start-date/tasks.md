## 1. Resolve the open question first

The whole `EventConfig` migration hinges on whether the serialization plugin honours a cross-parameter
default. Settle it before building on it (design.md, Decision 5).

- [x] 1.1 Write a `commonTest` decode test in `:capability:config`: a legacy `EventConfig` JSON blob
      carrying `eventId`/`name`/`minPhotoDate`/`direction`/`saveToAlbum` and **no** `startsAt`, against a
      class declaring `val startsAt: String = minPhotoDate` after `minPhotoDate`. Assert it decodes and
      `startsAt == minPhotoDate`.
      **ANSWERED: it works.** The serialization plugin honours a cross-parameter default in its
      synthetic constructor. No surrogate needed.
- [x] 1.2 ~~If 1.1 fails: implement the surrogate~~ — **not needed**, 1.1 passed.
- [x] 1.3 Assert the sibling invariant still holds: a config with **no** `minPhotoDate` still fails to
      decode (its no-default rule is untouched by this change). Existing test still green: an absent
      `minPhotoDate` throws even though a later field now references it as its default.

## 2. Backend (`backend/`)

- [x] 2.1 Add `validateStartsAt(raw: unknown): string | null` to `src/validators.ts` — accepts only the
      canonical `yyyy-MM-ddTHH:mm:ssZ` shape (regex + a real parse), rejects absent / non-string / empty
      / fractional-seconds / offset-bearing. Unit-test it in `test/validators.test.ts` alongside
      `validateEventName`.
- [x] 2.2 Add `startsAt: string` to the `EventMarker` type in `src/app.ts`.
- [x] 2.3 `POST /events`: read and validate `startsAt`, 400 (no upstream write) on failure, and include
      it in the marker PUT body and the 201 response. No bounds — past and future both accepted.
- [x] 2.4 `GET /events/:eventId`: synthesize `startsAt = createdAt` when the stored marker lacks it. Do
      **not** rewrite the stored object.
- [x] 2.5 Update `test/app.test.ts`: `MARKER_BODY` gains `startsAt`; the create test asserts the stored
      marker is byte-identical to the echoed body (it already does — keep that assertion honest); add
      cases for missing/non-canonical/empty `startsAt` → 400 with zero upstream calls, a future
      `startsAt` → 201, and the legacy-marker read synthesizing `startsAt` from `createdAt`.

## 3. Config + cutoff (`:capability:config`)

- [x] 3.1 Add `startsAt` to `EventConfig` per the shape 1.1/1.2 settled on, with KDoc explaining **why**
      it defaults (unlike `minPhotoDate`): `EventConfig` is the only holder of the `eventId`, the invite
      QR derives from it, so a decode failure strands the member outside their own event.
- [x] 3.2 Add a `commonTest`-tested clamp helper to `Cutoff.kt`: `max(chosen, startsAt)` over two
      canonical strings. Lexicographic compare is correct **because** of the format invariant — assert
      that (it is the same property the cutoff filter relies on).

## 4. Wire types (`:capability:event-creation-ui`, `:capability:join`)

- [x] 4.1 `EventCreator.create(name)` → `create(name: String, startsAt: String)`; thread through
      `CreateEvent`, `NoOpEventCreator`.
- [x] 4.2 `HttpEventCreationClient`: send `{"name":…,"startsAt":…}` verbatim; parse `startsAt` from the
      201. Update `HttpEventCreationClientTest`'s exact-body assertion.
- [x] 4.3 `EventDetails.Found(name, createdAt)` → `Found(name, startsAt)`. `HttpEventDetailsSource`: a
      200 with no parseable `startsAt` → `Failed` (retryable), **never** a defaulted floor. Update
      `HttpEventDetailsSourceTest`.
- [x] 4.4 `HttpEventMetadataSource`'s DTO gains `startsAt` (cosmetic fetch — no behavior change).

## 5. The clamp (`:capability:join`)

- [x] 5.1 `JoinEvent.join(...)` takes the event's `startsAt`, persists `minPhotoDate = max(chosen,
      startsAt)` **and** `startsAt` into `EventConfig`. The clamp lives here — not in the UI — so every
      entry path (interactive confirm, switch, retry, `autoJoin` deeplink override) is covered by one
      site.
- [x] 5.2 `commonTest`: chosen below the floor clamps up; chosen above is unchanged; an `autoJoin`
      deeplink `minPhotoDate` below the floor clamps up (the hostile-QR case — design.md Decision 2b).

## 6. Presentation (`:domain:presentation`)

- [x] 6.1 `JoinPhase.Ready(name, defaultCutoff)` → carry the event's `startsAt`. Delete
      `cutoffOrNow`/the `createdAt` seed and its now-fallback — `startsAt` is always present.
- [x] 6.2 Add `SyncHealth.NotStarted(startsAt)` to `UiState.kt`. Reduce it in `StatusContainerHost` with
      precedence `NeedsAccess > NotStarted > Loading > InSync/Syncing`.
- [x] 6.3 Add the 1-minute foreground tick that re-evaluates `startsAt > now`, running **only** while
      not-started and stopping itself once the start passes. `:domain:status` stays clock-free.
- [x] 6.4 `onCreateEvent(name)` → `onCreateEvent(name, startsAt: LocalDateTime)`, converting the local
      pick via the existing `CutoffFormatter` (one origin of "now" in the app).
- [x] 6.5 `commonTest` in `StatusContainerHostTest`: future `startsAt` → `NotStarted`; permission absent
      outranks it; the tick retires it when the clock advances past the start; a past `startsAt` reduces
      from the snapshot exactly as before.

## 7. Design system (`:domain:ui:components`)

- [x] 7.1 Rework `AppDateTimeField` into the single-dialog picker: calendar + hour/minute readout;
      tapping the readout swaps the calendar area for the M3 dial; no keyboard entry. Confirm what
      Compose MP 1.11.1's Material 3 actually exposes (`TimePickerDialog` / display-mode toggle) before
      hand-wiring the swap.
- [x] 7.2 New `App*` event start-date row: readable label + edit affordance, non-null value, semantic
      params only (no `Modifier`, no M3 in the signature).
- [x] 7.3 New `App*` cutoff-preset selector: sealed `Now`/`EventStart` value, a `nowAvailable` flag, an
      `enabled` flag, and the resulting instant rendered as a label.
- [x] 7.4 `AppSyncStatus` gains `NotStarted(startsAt)`: clock indicator, "Starts &lt;date&gt;, &lt;time&gt;" in the
      device's local zone, flat and not tappable. Label + formatting owned by the component.
- [x] 7.5 ~~`jvmTest` for each component in isolation~~ — **not done as written, deliberately.**
      `:domain:ui:components` has **no test source set at all**; every existing `App*` component is
      covered through `:domain:ui`'s screen tests, and standing up a Compose test source set just for
      this change is scope the project has not taken. The new components are covered the same way the
      old ones are — through `StatusScreenTest` / `JoinScreenTest`: the two-preset selector and its
      disabled `Now`, the start row and its edit affordance, the one-dialog picker and its
      calendar↔dial swap, and the not-started clock line.

## 8. Screens (`:domain:ui`)

- [x] 8.1 `CreateEventScreen`: add the start row beneath the name field. Default to **now, frozen at
      first composition** (`remember { cutoff.nowLocal() }`) — not re-derived at submit. Pass the chosen
      start to `onCreateEvent`.
- [x] 8.2 `JoinScreen`: replace the `AppDateTimeField` + "Only from now" row with the preset selector.
      Default **Event start**; disable **Now** when `startsAt > now`; keep the whole row disabled under
      `Direction.DownloadOnly`.
- [x] 8.3 `JoinedLayer`: render `NotStarted` through the existing `SyncHealth → AppSyncStatus` mapping —
      it lands in the status-line slot below the QR with no new layout.
- [x] 8.4 Update `StatusScreenTest` (create screen: the start row, its frozen default, the value crossing
      on Create) and `JoinScreenTest` (the selector replaces the picker; `Now` disabled pre-start;
      download-only still disables the row).

## 9. Harnesses (`:test:world`, `:app:desktop`, `:app:desktop:ui`)

- [x] 9.1 `:test:world`: `CreatedEventDto` + `BackendStore` marker gain `startsAt`; `registerEvent(…,
      startsAt =)` (update all ~12 call sites). Mini-edge `POST /events` rejects a non-canonical
      `startsAt` with 400; `GET` synthesizes it from `createdAt` when absent. Keep the canned `createdAt`
      millisecond-bearing and the canned `startsAt` canonical — that asymmetry is the point.
- [x] 9.2 `:app:desktop` world inspector: the Create event control supplies a `startsAt`, with a
      past/future choice so the floor is drivable through the real stack.
- [x] 9.3 `:app:desktop:ui` forge harness: a not-started preset that forges a **future `startsAt` on the
      config** and lets the real reduction derive the health (not a fabricated health value), composable
      with the permission presets so the precedence is reviewable.

## 10. Integration + iOS wiring

- [x] 10.1 `:test:integration`: a future-start event admits nothing — no upload job, no object in the
      world's store, ledger empty — asserting the *theorem*, not a gate (design.md Decision 2). And its
      mirror: once the start is in the past, uploads flow.
- [x] 10.2 `:test:integration`: `JoinGateIntegrationTest` — the join gate defaults to `startsAt` (not
      `createdAt`), and a chosen cutoff below it is persisted clamped.
- [x] 10.3 `:app:ios` composition root: thread the new `create(name, startsAt)` and `JoinEvent`
      signatures. Wiring only — no logic.

## 11. Verify

- [x] 11.1 `./gradlew build` (compiles all targets + JVM tests, headless).
- [x] 11.2 `./gradlew compileIosMainKotlinMetadata` — the Linux-runnable proxy for the iOS source sets.
- [x] 11.3 `cd backend && deno test`.
- [x] 11.4 Drive `./gradlew :app:desktop:run` — **PARTIAL, and the gap is real.** The harness launches
      and exits cleanly with no exception (composition root wires up), and the past/future create
      controls are in place. But the sandbox has **no screenshot tool and no input automation**, so the
      scenario could not be clicked through by hand and *nobody has looked at the clock line in a
      window*. The behaviour it was meant to demonstrate is instead pinned automatically by
      `FullStackIntegrationTest.a_future_start_event_uploads_nothing_and_reads_not_started` and
      `…the_same_event_uploads_normally_once_its_start_is_in_the_past`, which drive the SAME `World`,
      `UploadCycle`, and reduction the harness runs. **A human should still open the harness once.**
- [x] 11.5 `npx --yes @fission-ai/openspec@1.5.0 validate --specs --strict`.
