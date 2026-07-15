## 1. Design-system override

- [x] 1.1 In `:domain:ui:components` `AppTheme.kt`, add `val LocalDarkThemeOverride = staticCompositionLocalOf<Boolean?> { null }`.
- [x] 1.2 Change `AppTheme` to compute `val dark = LocalDarkThemeOverride.current ?: isSystemInDarkTheme()` and select `DarkColors`/`LightColors` from `dark`; update the KDoc to note the test-only override defaults to following the system.

## 2. Shared phone-pane injection

- [x] 2.1 In `:app:desktop` `StatusPane.kt`, add param `darkThemeOverride: Boolean? = null` and wrap the `StatusScreen(...)` call in `CompositionLocalProvider(LocalDarkThemeOverride provides darkThemeOverride)` (inside `PhoneFrame`, phone pane only). Also added `:domain:ui:components` as a direct dependency in `app/desktop/build.gradle.kts` (it was only transitive).

## 3. Forge harness toggle

- [x] 3.1 In `:app:desktop:ui` `Main.kt`, hold `var dark by remember { mutableStateOf(false) }` and pass `darkThemeOverride = dark` to `StatusPane`.
- [x] 3.2 Add a Light/Dark control (raw M3) to the `ControlPanel`, reading/writing that state, defaulting to Light; keep it out of `PanelController`.

## 4. Full-stack harness toggle

- [x] 4.1 In `:app:desktop` `FullStackHarness.kt`, hold `var dark by remember { mutableStateOf(false) }` and pass `darkThemeOverride = dark` to `StatusPane` (unaffected by the `key(controller.generation)` world rebuild).
- [x] 4.2 Add a Light/Dark control (raw M3) to the world inspector, reading/writing that state, defaulting to Light; keep it out of `WorldInspectorController`.

## 5. Verify

- [x] 5.1 `./gradlew build` compiles all targets and JVM tests pass (production `AppTheme` behavior unchanged — no override provided).
- [x] 5.2 Launched `:app:desktop:ui:run`: the harness composes and runs cleanly with the new provider + toggle (no runtime composition crash), rendering the default Light state. NOTE: no desktop screenshot tool is present on the host, so the visual light↔dark flip and the dark-mode QR were not captured — confirm by eye on the running window (or on device, where the same `AppTheme` path already ships).
- [x] 5.3 `npx --yes @fission-ai/openspec@1.5.0 validate --strict --changes add-harness-theme-toggle` passes.
