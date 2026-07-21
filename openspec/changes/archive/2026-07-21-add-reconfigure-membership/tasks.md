## 1. Domain — `ReconfigureEvent` use-case (`:domain` `feature/membership`)

- [x] 1.1 Add `ReconfigureEvent` in `domain/src/commonMain/kotlin/app/snapsync/feature/membership/`, mirroring `EventName.storeEventNameIfChanged`: read `configSource.config.value`, guard the `eventId` still matches, and `store.save(current.copy(direction, minPhotoDate, saveToAlbum))`. Apply `clampToFloor(chosenCutoff, current.startsAt)` (from `model/Cutoff.kt`) before saving. No-op when the eventId guard fails.
- [x] 1.2 Inject the on-Save side-effect seams as suspend lambdas (constructor params, like `LeaveEvent`): `ensureAlbum`, `applyUploadArm(includesUpload)`, `applyDownload(includesDownload)` — each wrapped in the same best-effort `step { }` logging style, so a partial failure logs and continues without blocking the save.
- [x] 1.3 Order the effects: persist config first (whole-object save), then re-drive effects off the new config — ensure album when `saveToAlbum` now true; arm upload + schedule pump when `includesUpload` now true; trigger download reconcile when `includesDownload` now true; cancel in-flight downloads when `includesDownload` now false. Leave in-flight uploads untouched.
- [x] 1.4 Write `ReconfigureEventTest` in `commonTest` (runs JVM + `iosSimulatorArm64`), mirroring `EventNameTest`/`LeaveEventTest`: asserts the whole-object save with only the three fields changed, the eventId guard no-op, the floor clamp, and that each side-effect lambda fires only for the correct transition.

## 2. Domain — command bundle + shared composition (`model/`, `compose/`)

- [x] 2.1 Add a `reconfigure` field to `model/UserCommands.kt` (default inert no-op), named to avoid the existing `openSettings` (iOS system settings) collision — e.g. `reconfigure: suspend (direction: Direction, minPhotoDate: String, saveToAlbum: Boolean) -> Unit = { _, _, _ -> }`.
- [x] 2.2 Build the live `reconfigure` command in `compose/SnapSyncApp.kt` `AppCore.userCommands` (~line 470), wiring `ReconfigureEvent` over the existing seams: `AlbumCoordinator.ensureAlbum`, the `UploadArm` provision/stop path (arm + `BackgroundUploadPump` schedule), and `DownloadController` (reconcile-or-`onLeaveOrSwitch`). Construct `ReconfigureEvent` in the same graph.
- [x] 2.3 Smoke-cover the wiring only (no unit test of `SnapSyncApp` — it is composition), per the One-shared-composition law; the behavior is covered by 1.4 and the integration test in 7.3.

## 3. Presentation — surface state + current settings (`:ui:presentation`)

- [x] 3.1 In `StatusContainerHost`, expose the current membership settings to the screen from the existing `config: StateFlow<EventConfig?>` (a derived `StateFlow` of the current `direction`/`minPhotoDate`/`saveToAlbum`/`startsAt`, like the existing `inviteUrl` derivation) — no new `UiState` family.
- [x] 3.2 Add an `onReconfigure(direction, minPhotoDate, saveToAlbum)` intent method: `intent { commands.reconfigure(...) }`, mirroring `onLeaveEvent`. Keep surface open/close as screen-local navigation (no flow round-trip).
- [x] 3.3 Confirm the presentation-imports gate still passes (references only `model/` + read-models); update `ForgeStatusHost` only if needed to keep the forge factory compiling (commands stay inert).

## 4. Components — `SettingsButton` (`:ui:components`)

- [x] 4.1 Add `SettingsButton.kt` mirroring `ShareButton.kt`: flat icon-only `IconButton`, default content tint, signature `(description: String, onClick: () -> Unit)`, glyph chosen in-component (e.g. `Icons.Filled.Settings` / `Icons.Outlined.Settings`) — keep the `Icons.*` import confined to the components module (design-system Material-3-containment gate).

## 5. Screens — action row + reconfigure surface (`:ui:screens`)

- [x] 5.1 Add `SettingsButton` **before** `ShareButton` in `StatusScreen.kt`'s `bottomActions` lambda (~lines 114–123), gated on `state is UiState.Joined` and no pending switch; add an `onOpenEventSettings` screen param (mirroring `onShareInvite`).
- [x] 5.2 Build the reconfigure surface as a new composable reusing the join sub-components (the switch-header/Share section with cutoff-preset selector, and the album toggle), a read-only event-name header, and Save/Cancel. Seed the cutoff selector per the reconstruction rule (`minPhotoDate == startsAt` → Event start, else Custom). Toggle visibility via a local `remember` flag set by the gear (like `confirmingLeave`).
- [x] 5.3 Add the inline helper text: album-on → "only photos synced from now on are added"; narrowing changes → already-shared/received photos are not retracted. No confirm dialog on Save.
- [x] 5.4 Wire Save → `onReconfigure(...)` + close; Cancel → close (discard).

## 6. Shells / harness wiring (untested, wiring-only)

- [x] 6.1 Thread the new command + screen params through `app/desktop/.../StatusPane.kt` (`UserCommands(...)` bundle ~lines 91–98 and the `StatusScreen(...)` call ~120–138) so both desktop harnesses drive the real reconfigure path.
- [x] 6.2 Add a control to the forge `PanelController` and the world `WorldInspectorController` to open the surface / observe a reconfigure (world drives the real `world.userCommands`).
- [x] 6.3 Thread the params through `app/ios/.../MainViewController.kt` (~line 49) — Kotlin wiring only, no conditionals (shell gate).

## 7. Tests

- [x] 7.1 `StatusScreenTest` (`ui/screens/jvmTest`): assert the settings action exists by content-description in the joined layer across health states, is absent during a pending switch, and opens the surface; assert the surface pre-fills and that Save invokes the callback with the edited values.
- [x] 7.2 Add screen-level assertions for the cutoff-preset seeding (equal-to-floor → Event start; above → Custom) and the album-on helper text.
- [x] 7.3 `:test:integration` (`commonTest`): compose the real core over `:test:world`, drive a reconfigure that (a) enables share on a download-only membership → assert uploads start and objects land; (b) turns album on → assert only forward-synced photos are placed; (c) turns receive off → assert in-flight downloads cancel and no new imports. Assert `UiState` + world outcomes.

## 8. Specs, diagrams, build

- [x] 8.1 Run `./gradlew architectureDiagrams` and commit any `architecture/` changes (the diagrams check is required).
- [x] 8.2 Run `./gradlew build` (compiles all targets + JVM tests) and `./gradlew compileIosMainKotlinMetadata` (the Linux iOS-source proxy); fix any breakage.
- [x] 8.3 Sync/validate specs: `npx --yes @fission-ai/openspec@1.5.0 validate add-reconfigure-membership --strict` stays green; author the `reconfigure-membership` Purpose at sync/archive time (no placeholder), amend `join-event`'s code-doc immutability wording to point at the new capability (code comment only — no spec delta, join-event carries no immutability SHALL).
