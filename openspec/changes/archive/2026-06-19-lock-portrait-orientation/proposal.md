## Why

The iOS app's `Info.plist` currently declares portrait **and** both landscape orientations (plus upside-down on iPad), so the UI rotates into landscape when the device turns. SnapSync is a single status screen for a personal photo backup — there is no landscape layout, and the design system / `StatusScreen` is built and previewed at fixed iPhone-portrait proportions (the desktop harness frame is a hard-coded 390×844 portrait). Landscape rotation only ever reflows a portrait-only layout into an unintended shape. This change locks the app to upright portrait so the only orientation the UI is designed for is the only one it can ever be shown in.

## What Changes

- The iOS `Info.plist` narrows `UISupportedInterfaceOrientations` to exactly `UIInterfaceOrientationPortrait` (drop `LandscapeLeft` and `LandscapeRight`), and removes the now-dead `UISupportedInterfaceOrientations~ipad` key.
- The app drops iPad as a target: `TARGETED_DEVICE_FAMILY` changes from `"1,2"` to `"1"` (iPhone only) in `iosApp.xcodeproj`. iPad then runs the app letterboxed in iPhone-compatibility mode, and the `~ipad` orientation key is no longer honored.
  - **Why the iPad-target drop, not just the plist:** the App Store rejects a *universal* app whose iPad orientation set isn't all four (TMS-90474 — iPad multitasking requires either all four orientations or an opt-out via `UIRequiresFullScreen`). Targeting iPhone only sidesteps that rule entirely while keeping portrait-only.
- No upside-down portrait: the single upright `UIInterfaceOrientationPortrait` is the only allowed value.
- No Compose/Kotlin or Swift code changes — orientation and device family are static platform declarations; the shared UI already has no orientation logic and the Swift entry point is unchanged.

## Capabilities

### New Capabilities
<!-- None — modifies an existing capability. -->

### Modified Capabilities
- `ios-app-shell`: gains a requirement that the app is presented in **portrait only** and targets iPhone only — the `Info.plist` orientation declaration locks the UI to upright portrait, and `TARGETED_DEVICE_FAMILY = "1"` avoids the iPad-multitasking validation rule.

## Impact

- **iOS-only:** `iosApp/iosApp/Info.plist` (`UISupportedInterfaceOrientations`; `~ipad` removed) and `iosApp/iosApp.xcodeproj/project.pbxproj` (`TARGETED_DEVICE_FAMILY` in both build configs). No module, Gradle, or dependency changes.
- **No automated coverage:** orientation/device-family live only in `Info.plist`/`pbxproj`, which `./gradlew build` and the `compileIosMainKotlinMetadata` proxy do not read, and `:app:ios` / `iosApp/` are wiring-only and untested by rule. The spec scenarios document the contract but are **not** machine-checked.
- **Out of scope:** any landscape layout work; the upload extension (it has no UI-orientation declaration).
- **Manual verification:** install on the physical iPhone SE2 and rotate — the UI stays upright. The TMS-90474 fix is confirmed by a clean TestFlight upload of the resulting build.
