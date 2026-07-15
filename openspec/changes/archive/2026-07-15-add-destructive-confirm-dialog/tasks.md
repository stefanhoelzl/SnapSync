## 1. Design-system: destructive dialog + internal helpers

- [x] 1.1 Extract a private `ConfirmDialogScaffold` in `:domain:ui:components` that owns the modal scrim, the side-by-side `Row`, and the outlined `SecondaryButton` cancel, taking the confirm button as a composable slot; refactor `AppConfirmDialog` to delegate to it (confirm = green `PrimaryButton`) with no change to `AppConfirmDialog`'s public signature or rendered appearance.
- [x] 1.2 Add a private destructive filled button helper (Material 3 `Button` mirroring `PrimaryButton`'s geometry — full-width, 52dp tall, 16dp corners, `titleMedium` — but `containerColor = colorScheme.error` / `contentColor = colorScheme.onError`). Not exported as an `App*` component.
- [x] 1.3 Add the public `AppDestructiveConfirmDialog(title, confirmLabel, cancelLabel, onConfirm, onDismiss)` — signature-identical to `AppConfirmDialog` — delegating to `ConfirmDialogScaffold` with the destructive button as confirm. Include a doc comment framing it as the destructive sibling.
- [x] 1.4 Tint the `LeaveButton` glyph with `MaterialTheme.colorScheme.error`; leave `ShareButton` at the default content tint. No signature change to either.
- [x] 1.5 Confirm no new color is added to `AppTheme` (reuse the M3 default `error`/`onError` roles); update the `AppTheme` convention comment only if wording needs it. — No edit needed; `AppTheme` already leaves `error`/`onError` at the M3 default with the "keep the Material error red by convention" comment, now exercised.

## 2. Screen wiring

- [x] 2.1 In `StatusScreen.kt`, switch the plain Leave confirmation (`confirmingLeave`, ~line 120) from `AppConfirmDialog` to `AppDestructiveConfirmDialog` (+ import).
- [x] 2.2 In `SwitchDialog` (`StatusScreen.kt`), switch only the `JoinPhase.Ready` "Switch" confirmation (~line 440) to `AppDestructiveConfirmDialog`; leave `NotFound` ("OK"), `LoadFailed` and `CommitFailed` ("Retry") on `AppConfirmDialog`.

## 3. Tests

Note: the codebase has **no color-pixel test precedent** — appearance is verified by harness eyeball, and
every other skin-color requirement in the `design-system` spec (e.g. "complete uses primary, not green")
has no unit test. The components module has no test source set (commonMain only). So color is verified in
the harness (§4.3); the behavioral coverage below is what locks the scaffold refactor.

- [x] 3.1 Regression net for the scaffold refactor: the existing `StatusScreenTest` leave-flow tests (activate → confirm invokes `onLeaveEvent`; "Stay" dismisses) and `JoinScreenTest`'s switch-flow test ("Switch" invokes `onConfirmSwitch`) now run against `AppDestructiveConfirmDialog` — verifying labels, callbacks, and dismiss survive the shared-scaffold refactor. No net-new component-level test infra stood up (against the design system's "appearance is the skin's, verified by eyeball" grain; no test source set in the module).
- [x] 3.2 Leave-icon appearance verified by harness eyeball (§4.3), consistent with all other spec color requirements; the icon's *presence/label/flat* behavior remains covered by the existing `StatusScreenTest` leave tests.
- [x] 3.3 Added a guard test (`JoinScreenTest`) that the switch dialog's non-destructive `NotFound` phase stays a plain `AppConfirmDialog` ("OK" → `onCancelSwitch`), proving the refactor did not sweep every phase into the destructive path. The destructive Ready/"Switch" and leave paths are covered per §3.1.

## 4. Verify & validate

- [x] 4.1 Run `./gradlew build` (compiles all targets + JVM/offscreen Compose tests) and `./gradlew compileIosMainKotlinMetadata` (iOS proxy) — both green.
- [x] 4.2 Confirm the `:test:architecture` Material-3-containment guard still passes (all M3, including the new helpers, stays inside `:domain:ui:components`) — passes as part of `./gradlew build`.
- [x] 4.3 Eyeball both dialog states and the red leave icon in the forge harness (`./gradlew :app:desktop:ui:run`) — operator-verified, looks good.
- [x] 4.4 Run `npx --yes @fission-ai/openspec@1.5.0 validate --specs --strict` and fix any delta issues — all 47 specs pass; the change validates strict.
