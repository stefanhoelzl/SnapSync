## Why

The iOS app's `Info.plist` currently declares portrait **and** both landscape orientations (plus upside-down on iPad), so the UI rotates into landscape when the device turns. SnapSync is a single status screen for a personal photo backup — there is no landscape layout, and the design system / `StatusScreen` is built and previewed at fixed iPhone-portrait proportions (the desktop harness frame is a hard-coded 390×844 portrait). Landscape rotation only ever reflows a portrait-only layout into an unintended shape. This change locks the app to upright portrait so the only orientation the UI is designed for is the only one it can ever be shown in.

## What Changes

- The iOS `Info.plist` narrows **both** orientation arrays to exactly `UIInterfaceOrientationPortrait`:
  - `UISupportedInterfaceOrientations` — drop `LandscapeLeft` and `LandscapeRight`.
  - `UISupportedInterfaceOrientations~ipad` — drop `LandscapeLeft`, `LandscapeRight`, and `PortraitUpsideDown`.
- The app remains a **universal** binary (`TARGETED_DEVICE_FAMILY = "1,2"`, iPhone + iPad), so both keys are live at runtime and both must be locked — iPad is not dropped as a target by this change.
- No upside-down portrait on either device: the single upright `UIInterfaceOrientationPortrait` is the only allowed value.
- No Compose/Kotlin or Swift code changes — orientation is a static platform declaration; the shared UI already has no orientation logic and the Swift entry point is unchanged.

## Capabilities

### New Capabilities
<!-- None — modifies an existing capability. -->

### Modified Capabilities
- `ios-app-shell`: gains a requirement that the app is presented in **portrait only** — the `Info.plist` orientation declarations lock both iPhone and iPad to upright portrait, so the UI never rotates to landscape or upside-down.

## Impact

- **iOS-only, single file:** `iosApp/iosApp/Info.plist` (both `UISupportedInterfaceOrientations*` arrays). No module, build, or dependency changes.
- **No automated coverage:** orientation lives only in `Info.plist`, which `./gradlew build` and the `compileIosMainKotlinMetadata` proxy do not read, and `:app:ios` / `iosApp/` are wiring-only and untested by rule. The spec scenarios document the contract but are **not** machine-checked.
- **Out of scope:** dropping iPad as a target (`TARGETED_DEVICE_FAMILY` stays `1,2`); any landscape layout work; the upload extension (it has no UI-orientation declaration).
- **Manual verification:** install on the physical iPhone SE2 and rotate — the UI stays upright; ideally confirm on an iPad (or iPad simulator) that it also stays portrait.
