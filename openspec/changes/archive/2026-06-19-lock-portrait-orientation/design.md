## Context

Orientation on iOS is a static `Info.plist` declaration read by UIKit at scene attach. `iosApp/iosApp/Info.plist` (owned by the `ios-app-shell` capability, which already governs this file for the `snapsync` URL scheme) currently lists portrait + both landscapes for iPhone and all four orientations for iPad. The shared UI has no orientation awareness: `StatusScreen` (`:domain:ui`) is a single-column status layout, the design system is semantic-only, and the desktop harness previews it inside a fixed 390×844 portrait `PhoneFrame`. The `iosApp.xcodeproj` builds a **universal** app — `TARGETED_DEVICE_FAMILY = "1,2"` — so iPad is a genuine runtime target and the `~ipad` orientation key is honored (it is not dead config).

## Goals / Non-Goals

**Goals:**
- The iOS UI is presented in upright portrait only, on both iPhone and iPad — never landscape, never upside-down.

**Non-Goals:**
- Dropping iPad as a target (`TARGETED_DEVICE_FAMILY` stays `1,2`).
- Any landscape or adaptive layout.
- A runtime/per-view-controller orientation override (the plist is sufficient and canonical for an always-portrait app).
- Automated test coverage of the plist (not reachable from Gradle; `:app:ios` is untested by rule).

## Decisions

### D1 — Lock via `Info.plist`, not a runtime override
For an app that is *always* portrait, the static `UISupportedInterfaceOrientations*` declaration is the canonical mechanism — UIKit honors it at scene attach with no code. *Alternative:* overriding `supportedInterfaceOrientations` on a view controller — rejected: it exists for per-screen variation we don't have, and would push logic into the untestable Swift/`:app:ios` shell for no benefit.

### D2 — Lock both keys, keep iPad a target
Because the binary is universal (`1,2`), iPhone reads `UISupportedInterfaceOrientations` and iPad reads `UISupportedInterfaceOrientations~ipad`; locking only the iPhone key would still let an iPad rotate. So both are narrowed to portrait. Dropping iPad entirely (`TARGETED_DEVICE_FAMILY = "1"`) would make the `~ipad` key moot, but removing a supported device is a separate scope decision — explicitly deferred; iPad stays.

### D3 — Upright portrait only (exclude upside-down)
The single value `UIInterfaceOrientationPortrait` on both keys. iPhone ignores upside-down by default regardless; excluding `PortraitUpsideDown` from the iPad key makes "portrait only" literal there too, so neither device flips 180°.

### D4 — Verification is manual by necessity
The lock is invisible to `./gradlew build` and `compileIosMainKotlinMetadata` (neither reads plist orientation keys), and `:app:ios` / `iosApp/` are wiring-only and untested. The spec scenarios are the documented contract; confirmation is a manual device/simulator rotation check. This is accepted, consistent with the project's "`:app:ios` is wiring-only and untested" rule.
