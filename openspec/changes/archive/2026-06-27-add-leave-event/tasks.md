## 1. Config seam: clear the persisted event (`:capability:config`)

- [x] 1.1 Add `suspend fun clear()` to the `ConfigStore` port (`ConfigPorts.kt`, `deeplink-config`):
      removes the persisted payload and updates the source to `null`; idempotent no-op when already
      absent; does not touch the ledger.
- [x] 1.2 Implement `clear()` on `KeychainConfigStore` (`iosMain`): extracted a shared `deleteItem()`
      (reused by `writeUrl`), `SecItemDelete` tolerating a missing item, then `state.value = null`.
      Green under `compileIosMainKotlinMetadata`.
- [x] 1.3 Added `clear()` to the other `ConfigStore` fakes: `PanelController` (desktop) and the
      presentation `FakeConfig`. (The extension's `UploadExtensionRoot` consumes only `ConfigSource`,
      not `ConfigStore`, so it needed no change.)
- [~] 1.4 Folded: `:capability:config` has no common `ConfigStore` impl (the real one is `iosMain`,
      and `save()` likewise has no config-module test), so a standalone commonTest would only test a
      throwaway fake. The `clear()` contract is covered through real call paths in 2.2 (`LeaveEvent`
      drives `config.clear()`) and 3.3 (the intent).

## 2. Leave use-case (`:capability:rejoin`)

- [x] 2.1 Add `LeaveEvent` (`commonMain`) taking `ConfigStore`, `LedgerBackend`,
      `MutableEventStatusSource`, `disableExtension: suspend () -> Unit`, and
      `clearDiscoveryCursor: suspend () -> Unit`. `run()` order: `disableExtension()` →
      `ledger.resetTo(emptyList())` → `clearDiscoveryCursor()` → `config.clear()` →
      `status.set(EventStatus.Idle)`. Best-effort: log and continue on a step failure; no rollback;
      construct no `LedgerWriter`.
- [x] 2.2 `commonTest` (runs on JVM + `iosSimulatorArm64`): asserts the ordered effects (disable
      called before the ledger reset), the ledger ends empty, the discovery-cursor lambda fires, the
      config is cleared, and `EventStatus` becomes `Idle`; a forced config-clear failure still leaves
      the ledger empty and the producer disabled (self-heal precondition).

## 3. Presentation: leave intent (`:domain:presentation`)

- [x] 3.1 Inject the leave action as a `leave: suspend () -> Unit = {}` **lambda** (no-op default),
      **not** the `LeaveEvent` type — presentation is Compose-free and must gain no engine/gallery dep
      (design revised; spec `leave-event` updated). iOS binds it to `LeaveEvent::leave`.
- [x] 3.2 Add `onLeaveEvent()` intent delegating to the injected `leave` lambda. `UiState` and the
      `reduceFrom` reduction **unchanged** (no new state, no new effect).
- [x] 3.3 `commonTest`: `onLeaveEvent()` invokes the use-case; with the default no-op it is inert and
      construction is unchanged.

## 4. Design-system components (`:domain:ui:components`)

- [x] 4.1 Add `compose.materialIconsExtended` to `domain/ui/components/build.gradle.kts` — the only
      module allowed Material 3 / icons. (No `libs.versions.toml` entry: it's versioned by the Compose
      plugin DSL, like `compose.material3`.)
- [x] 4.2 Add a flat, icon-only **leave** action component (label + `onClick` only) rendering an
      `IconButton` with `Icons.AutoMirrored.Filled.Logout`; no appearance/`Modifier`/M3 params in the
      signature.
- [x] 4.3 Add `AppConfirmDialog(title, confirmLabel, cancelLabel, onConfirm, onDismiss)` wrapping the
      M3 dialog; semantic signature only.
- [x] 4.4 Extend `ScreenLayout` with an optional bottom-right action slot
      (`bottomEndAction: (@Composable () -> Unit)? = null`) that the container places; screens pass no
      bottom-anchor geometry.

## 5. Status screen: leave affordance (`:domain:ui`)

- [x] 5.1 Add an `onLeaveEvent: () -> Unit = {}` callback param to `StatusScreen`.
- [x] 5.2 Render the leave action via `ScreenLayout`'s bottom-right slot **only** for joined-layer
      states (`InProgress`, `NothingToSync`, `Completed`); render nothing for `Loading`, `Setup`,
      `Joining`, `JoinFailed`.
- [x] 5.3 Hold the confirm dialog's open/closed state locally (`remember { mutableStateOf(false) }`);
      the action opens it; `AppConfirmDialog` Confirm calls `onLeaveEvent` then dismisses; Cancel
      dismisses.
- [x] 5.4 `:domain:ui:jvmTest` (offscreen): joined-layer states show the action; non-joined states do
      not; activating shows the dialog; Confirm invokes `onLeaveEvent`; Cancel does not.

## 6. iOS wiring (`:app:ios`)

- [x] 6.1 In `SnapSyncRoot`, construct `LeaveEvent` injecting `config`, `ledgerBackend`, `eventStatus`,
      `disableExtension = { PHPhotoLibrary.sharedPhotoLibrary().setUploadJobExtensionEnabled(false, error = null) }`
      (guarded by `backgroundUploadSupported()`), and `clearDiscoveryCursor = { clearDiscoveryCursor() }`;
      inject it into `StatusContainerHost`.
- [x] 6.2 Route the leave callback in `MainViewController`: pass `onLeaveEvent = host::onLeaveEvent`,
      and **name** `transientError` (it was the 4th positional arg, now displaced by `onLeaveEvent`).
- [x] 6.3 `compileIosMainKotlinMetadata` green.

## 7. Desktop harness (`:app:desktop`)

- [x] 7.1 Pass `onLeaveEvent = host::onLeaveEvent` from the phone frame's `StatusScreen` call (the
      container's no-op default makes Confirm inert — no leave fake, no control-panel change).
- [~] 7.2 Not run as a live desktop launch (`:app:desktop:run` needs a display; unavailable here).
      The equivalent behavior — action shows in joined-layer states, tap raises the dialog,
      Confirm/Cancel dismiss, no state mutated — is covered by the offscreen `:domain:ui:jvmTest`
      cases in 5.4, which render the same `StatusScreen`.

## 8. Docs

- [x] 8.1 `docs/design.md`: record leave as the local-only inverse of join (the four-layer model and
      the joined-layer leave affordance; leave is `disable → resetTo([]) → clear cursor → clear() →
      Idle`).

## 9. Verify

- [x] 9.1 `./gradlew build` (all targets compile + JVM/offscreen UI tests green).
- [x] 9.2 `./gradlew compileIosMainKotlinMetadata` (iOS source sets compile on Linux).
