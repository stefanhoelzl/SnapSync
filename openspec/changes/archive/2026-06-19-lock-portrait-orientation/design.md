## Context

Orientation on iOS is a static `Info.plist` declaration read by UIKit at scene attach. `iosApp/iosApp/Info.plist` (owned by the `ios-app-shell` capability, which already governs this file for the `snapsync` URL scheme) originally listed portrait + both landscapes for iPhone and all four orientations for iPad. The shared UI has no orientation awareness: `StatusScreen` (`:domain:ui`) is a single-column status layout, the design system is semantic-only, and the desktop harness previews it inside a fixed 390×844 portrait `PhoneFrame`. The `iosApp.xcodeproj` originally built a **universal** app — `TARGETED_DEVICE_FAMILY = "1,2"` — so iPad was a genuine runtime target whose `~ipad` orientation key was honored.

## Goals / Non-Goals

**Goals:**
- The iOS UI is presented in upright portrait only — never landscape, never upside-down.

**Non-Goals:**
- Any landscape or adaptive layout.
- A runtime/per-view-controller orientation override (the plist is sufficient and canonical for an always-portrait app).
- Automated test coverage of the plist (not reachable from Gradle; `:app:ios` is untested by rule).

## Decisions

### D1 — Lock via `Info.plist`, not a runtime override
For an app that is *always* portrait, the static `UISupportedInterfaceOrientations` declaration is the canonical mechanism — UIKit honors it at scene attach with no code. *Alternative:* overriding `supportedInterfaceOrientations` on a view controller — rejected: it exists for per-screen variation we don't have, and would push logic into the untestable Swift/`:app:ios` shell for no benefit.

### D2 — Target iPhone only (`TARGETED_DEVICE_FAMILY = "1"`)
*Revised during implementation.* The original plan kept iPad as a universal target and locked both the iPhone and `~ipad` orientation keys to portrait. But the App Store **rejects** that bundle: TMS-90474 requires a *universal* app to either declare all four iPad orientations or opt out of iPad multitasking (`UIRequiresFullScreen = true`). Two ways to keep portrait-only and pass validation: (a) keep iPad + add `UIRequiresFullScreen`; (b) drop iPad as a target. We chose **(b)** — `TARGETED_DEVICE_FAMILY` `"1,2" → "1"`. iPad then runs the app letterboxed in iPhone-compatibility mode (following the iPhone orientation list), the `~ipad` key becomes dead config and is removed, and the multitasking rule no longer applies. *Why not (a):* `UIRequiresFullScreen` keeps a native iPad target we don't design or test for; dropping the target is the simpler, honest scope for a personal iPhone backup tool.

### D3 — Upright portrait only (exclude upside-down)
The single value `UIInterfaceOrientationPortrait`. iPhone ignores upside-down by default regardless, so a single upright entry means the UI never flips.

### D4 — Verification is manual by necessity
The lock is invisible to `./gradlew build` and `compileIosMainKotlinMetadata` (neither reads plist orientation keys), and `:app:ios` / `iosApp/` are wiring-only and untested. The spec scenarios are the documented contract; confirmation is a manual device/simulator rotation check. This is accepted, consistent with the project's "`:app:ios` is wiring-only and untested" rule.
