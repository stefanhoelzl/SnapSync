## Why

The Material 3 skin already ships a dark theme that follows the OS setting (`AppTheme` →
`isSystemInDarkTheme()`), but neither desktop harness can render it: both wrap the window in a bare
`MaterialTheme {}`, and the phone pane's own inner `AppTheme` re-derives the theme from the host OS —
unreliable on a headless Linux dev box, and never explicitly forceable. So the dark theme can only be
reviewed on a physical device. A deterministic toggle in the harness lets us review and iterate on the
dark skin without a device.

## What Changes

- `AppTheme` selects dark vs light from the OS setting **by default**, but honors a test-only ambient
  override (a `LocalDarkThemeOverride` CompositionLocal, default absent) that forces light or dark.
  Production is unchanged: no one provides the override, so `AppTheme` falls through to
  `isSystemInDarkTheme()` exactly as today. No appearance parameter is added to any `App*` signature.
- The forge harness (`:app:desktop:ui:run`) and world harness (`:app:desktop:run`) each gain a
  **Light/Dark toggle** in their right-hand control surface. The toggle drives the override for the
  **phone pane only** (via the shared `StatusPane`); the raw-M3 control panel / inspector chrome stays
  light. Default is Light, matching today's harness appearance.

## Capabilities

### New Capabilities
<!-- none -->

### Modified Capabilities
- `design-system`: `AppTheme`'s theme selection follows the system setting by default and is
  overridable by a test-only ambient that forces light or dark — without introducing any appearance
  parameter on an `App*` signature.
- `desktop-test-harness`: the control panel gains a Light/Dark toggle that forces the phone pane's
  theme; the panel chrome is unaffected.
- `full-stack-harness`: the world inspector gains the same Light/Dark toggle over the phone pane.

## Impact

- `:domain:ui:components` — `AppTheme.kt`: add `LocalDarkThemeOverride` and read it in `AppTheme`.
- `:app:desktop` — `StatusPane.kt`: new `darkThemeOverride: Boolean?` param provides the ambient
  around the rendered `StatusScreen` (shared by both harnesses).
- `:app:desktop` — `FullStackHarness.kt` + world inspector: toggle state + control.
- `:app:desktop:ui` — `Main.kt` + `ControlPanel`: toggle state + control.
- No product-surface behavior changes; no iOS or backend impact.
