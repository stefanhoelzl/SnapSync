## 1. Deeplink codec — `autoJoin` (`:capability:config`)

- [x] 1.1 Add `autoJoin: Boolean = false` to `EventLinkPayload` (kotlinx `@Serializable`, default so absence decodes cleanly under the strict `Json`).
- [x] 1.2 Extend `ConfigDecodeResult.Success` to carry the resolved `autoJoin` (default `false`); keep `encodeConfigUrl` emitting `eventId`-only (never `autoJoin`).
- [x] 1.3 Update the decoder so `autoJoin` is an accepted known key while any *other* extra key still fails; the round-trip stays injective for the canonical (no-`autoJoin`) form.
- [x] 1.4 `commonTest`: decode `{eventId, autoJoin:true}` → success with flag; decode `{eventId}` → `autoJoin=false`; a genuinely-unknown key still fails; `encode`→`decode` round-trips with `autoJoin=false`. (Runs on JVM + `iosSimulatorArm64`.)

## 2. Join seams — event details + enrollment

- [x] 2.1 Define an `EventDetailsSource` seam (`commonMain`): `suspend fun fetch(eventId): EventDetails` returning `Found(name)`, `NotFound`, or `Failed` — one `GET /events/:id`, no throw. `HttpEventDetailsSource` + MockEngine tests. (New richer seam because the existing `EventMetadataSource.name()` collapses 404/failure to null.)
- [x] 2.2 Define a `DeviceEnroller` seam (`commonMain`) + `ManifestDeviceEnroller` writing a **register-only empty** `DeviceManifest(deviceId, [])` via the existing `DeviceManifestUploader` (`PUT /events/:eventId/devices/:deviceId`). Tested.
- [x] 2.3 In-memory fakes for both seams provided in `:capability:join` tests; world-backed wiring lands in Group 7.

## 3. `JoinEvent` use-case (new `:capability:join`, `commonMain`)

- [x] 3.1 Create the `:capability:join` module (deps: `:capability:config`, `:capability:device-id`, `:domain:gallery`, ktor-core+json; no engine/platform deps) and register it in `settings.gradle.kts`.
- [x] 3.2 Implement `JoinEvent.join(eventId, name)`: `enroll` → on `Ok`, `provision(EventConfig(eventId, name))` (the injected platform effect = save config + enable upload + reconcile); on `Failed`, return `EnrollFailed` and commit nothing. Also `loadDetails(eventId)`.
- [x] 3.3 Implement the **new-join guard**: `join` returns `AlreadyJoined` (skipping enrollment) when the target equals the current config `eventId` — protects the manifest from empty-clobber. Tested.
- [x] 3.4 Switch composition lives in the container per design D5 (`onConfirmSwitch` = commit-with-leave: `leave()` then `commitJoin`) — `JoinEvent` stays free of the leave dep. Tested in `StatusContainerHostTest`.
- [x] 3.5 `commonTest`: enroll-then-commit; failed enrollment commits nothing; same-event no-op skips enrollment; switch-to-different enrolls; `loadDetails` distinguishes found/not-found/failed. (Switch leave→join ordering + retry covered in Group 4/7.)

## 4. Presentation gate (`:domain:presentation`)

- [x] 4.1 Added the `JoiningEvent(eventId, phase: JoinPhase)` family, `JoinPhase` (Loading/Ready/NotFound/LoadFailed/Committing/CommitFailed), `PendingSwitch`, and `pendingSwitch` on `Joined`.
- [x] 4.2 Reworked `onOpenUrl`: decode → failure `InvalidConfigLink`; success routes by `autoJoin` (auto-confirm) / no-config (JoiningEvent) / different-config (pendingSwitch) / same-config (no-op). Gate work runs inline in the intent.
- [x] 4.3 Added intents `onRetryLoad`, `onConfirmJoin`, `onCancelJoin`, `onConfirmSwitch`, `onCancelSwitch`, `onRetryJoin`; confirm wired via injected `loadJoinDetails`/`commitJoin`/`leave` lambdas (presentation-local `JoinLoad`, inert defaults — no `:capability:join` dep).
- [x] 4.4 `reduceFrom` folds a 6th pending flow: pending+config-absent → `JoiningEvent` (outranks create); pending+config-present → `Joined.pendingSwitch`.
- [x] 4.5 Load/commit failures map to `NotFound`/`LoadFailed`/`CommitFailed`; switch leave-then-join with retryable no-event covered in `StatusContainerHostTest` (7 gate tests green).

## 5. Join UI (`:domain:ui`, `:domain:ui:components`)

- [x] 5.1 Build the full-screen "Join event" screen on `ScreenLayout` from `App*` only, rendering the four phases (loading / loaded-with-name+Join/Cancel / not-found-blocked / failed+Retry). Structure the body as a column so future option rows slot in; add no options and no `JoinOptions` type.
- [x] 5.2 Render the switch confirmation with `AppConfirmDialog` (loaded phase) and its loading/blocked/failed handling; reuse an `App*` for any new copy needs (no M3 in `App*` signatures).
- [x] 5.3 Wire `StatusScreen` to render `JoiningEvent` and the `pendingSwitch` dialog, routing actions to the new container intents.
- [x] 5.4 `:domain:ui:jvmTest` (offscreen): assert each phase renders its expected controls (Join enabled only when loaded; no Join on not-found; Retry on failed).

## 6. App wiring (`:app:ios`, untested shell)

- [x] 6.1 In `SnapSyncRoot`, construct the Darwin-HTTP `EventDetailsSource` + `DeviceEnroller` and the `JoinEvent` use-case (injecting `ConfigStore`, device-id, enable-upload, reconcile), and inject the confirm/switch lambdas into `StatusContainerHost`.
- [x] 6.2 Reroute `onOpenUrl`: interactive links go through the container gate (stop calling `provisionEvent` directly); `autoJoin` links take the direct provision path (leave-first if switching).
- [x] 6.3 Make the `SNAPSYNC_DEEPLINK` dev flow carry `autoJoin=true` (documented in-code) so headless launches auto-confirm; keep same-event re-provision a no-op.

## 7. Harness + integration (`:test:world`, `:test:integration`, desktop harnesses)

- [x] 7.1 Extend `:test:world` to serve `GET /events/:id` details (found/not-found/failure levers) and record enrollment PUTs (empty manifest → membership visible in the event device LIST).
- [x] 7.2 `:test:integration` (`commonTest`): loading → 404 blocks (no Join) / network fail → Retry / 200 → confirm; confirm → enrollment PUT lands + config saved + producer enabled + `Joined`; failed enrollment → not joined + Retry.
- [x] 7.3 `:test:integration`: switch = leave-then-join (asserts leave ran, new event joined); join-fails-after-leave → retryable no-event, Retry re-runs only join; same-event re-scan issues **no** enrollment PUT (clobber guard).
- [x] 7.4 `:test:integration`: `autoJoin` auto-confirms without a confirm action, still GETs + enrolls, and still leaves an existing event first.
- [~] 7.5 StatusPane now routes the join callbacks + accepts controllable join hooks (both harnesses render/drive the join UI; the full-stack harness can bind a real JoinEvent). Deferred: per-phase forge buttons + a world-inspector deeplink trigger (review conveniences). Original: Add forge presets (`:app:desktop:ui`) for the four `JoiningEvent` phases + the switch dialog, and a world-inspector control (`:app:desktop`) to drive a decoded deeplink through the gate.

## 8. Verify

- [x] 8.1 `./gradlew build` green (all targets compile; JVM + offscreen UI tests + integration tests pass).
- [x] 8.2 `./gradlew compileIosMainKotlinMetadata` green (iOS source sets compile via the Linux proxy).
- [x] 8.3 `openspec validate --strict` passes. On-device headless loop verified (iPhone SE2, iOS 26.5): `autoJoin=true` fresh-event link → gate `GET /events/:id`→200 → enrollment `PUT /events/:id/devices/:id`→**201** (manifest confirmed in the bunny zone) → provision + joined layer showing the event name (debug.log trace + screenshot).
