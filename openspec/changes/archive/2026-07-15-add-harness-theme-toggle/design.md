## Context

`AppTheme` (the Material 3 skin in `:domain:ui:components`) already defines `LightColors`/`DarkColors`
and selects between them with `isSystemInDarkTheme()`. On iOS this correctly tracks the system setting.
The desktop harnesses render the **real** `StatusScreen`, which wraps *itself* in `AppTheme` — so the
phone pane's theme is decided by `AppTheme`'s internal `isSystemInDarkTheme()`, not by the harness's
outer `MaterialTheme {}`. Consequences:

- The harness cannot force the phone pane dark; its outer theme is overridden by the inner `AppTheme`.
- `isSystemInDarkTheme()` on JVM/Linux is unreliable, so even OS-following review is not dependable.

The result is that the shipped dark theme can only be seen on a physical device. This change makes it
reviewable from either desktop harness.

## Goals / Non-Goals

**Goals:**
- A deterministic Light/Dark toggle in both harnesses that flips the real skin on the phone pane.
- Zero behavior change on any product surface (iOS app). Production `AppTheme` stays OS-driven.
- Go through the real `AppTheme` so the harness exercises the shipped skin, not a lookalike.

**Non-Goals:**
- Tuning the dark color values. This change *enables* review; refining `DarkColors` is separate.
- A manual light/dark override in the shipping app (system-following stays the only product behavior).
- Theming the raw-M3 control panel / world inspector — those stay light (test equipment, not product).

## Decisions

### Ambient override via CompositionLocal, not a threaded parameter
Add `val LocalDarkThemeOverride = staticCompositionLocalOf<Boolean?> { null }` to `AppTheme.kt`.
`AppTheme` computes `val dark = LocalDarkThemeOverride.current ?: isSystemInDarkTheme()`.

- **Why:** `StatusScreen` calls `AppTheme()` internally with no args. An ambient reaches that inner
  call without changing `StatusScreen`'s (large) signature or the `App*` contract. Default `null`
  means "follow the system", so production — which never provides the local — is byte-for-byte
  unchanged.
- **Alternatives considered:** (a) thread `darkTheme: Boolean` through `StatusScreen` → `AppTheme` —
  rejected: bloats a product signature for a test-only need. (b) Have the harness set its own outer
  `MaterialTheme(darkColorScheme())` — rejected: the inner `AppTheme` re-derives and overrides it, so
  the phone pane never flips; also duplicates the color scheme instead of exercising the real one.

### Inject the ambient in the shared `StatusPane` (phone pane only)
`StatusPane` (in `:app:desktop`, used by both harnesses) gains `darkThemeOverride: Boolean? = null` and
wraps its `StatusScreen` call in `CompositionLocalProvider(LocalDarkThemeOverride provides darkThemeOverride)`.

- **Why:** one injection point serves both harnesses, and scoping the provider to the pane keeps the
  control panel / inspector chrome on the outer light `MaterialTheme` — exactly the "phone pane only"
  decision. Default `null` keeps `StatusPane` callers that don't opt in unaffected.

### Toggle state and control live in each harness's control surface
Each `main()` holds `var dark by remember { mutableStateOf(false) }` (default Light), passes it to
`StatusPane(darkThemeOverride = dark)`, and renders a Light/Dark control in the right pane
(`ControlPanel` for the forge harness, the world inspector for the full-stack harness).

- **Why:** the theme is a pure view concern, not forge/world state, so it stays out of `PanelController`
  / `WorldInspectorController`. Default Light matches the harness's current appearance (no surprise).

## Risks / Trade-offs

- [A test-only ambient lives in the production `:domain:ui:components` module] → It is inert by default
  (`null` → follow system) and adds no `App*` appearance parameter, preserving the design-system
  containment rule; documented in the `design-system` spec so its purpose is on the record.
- [Harness dark ≠ device dark, since JVM font/rendering differ] → Acceptable: the review target is the
  color scheme and layout, which are shared; final sign-off still happens on device.
- [The QR must stay dark-on-light in dark mode] → Already guaranteed by `AppQrCode`; the toggle only
  changes the `colorScheme`, and the existing design-system scenario still holds.
