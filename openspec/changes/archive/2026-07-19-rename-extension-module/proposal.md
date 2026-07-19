# Proposal: rename-extension-module

## Why

Migration step 13a (`test/architecture/migration/PLAN.md`): the target module set
(`module-architecture` spec) names the extension composition-root module `:app:ios:extension` —
the module is defined by what it withholds (app-only platform API, Compose/UI) and by its seat
(the extension process's composition root), not by the PhotoKit technology of the current tier.
The beacon's `targetModules` and the `architecture-guards` spec (extension-safety gate scope)
already carry the target name; this step makes the actual module match.

## What Changes

Pure mechanical rename — `:app:ios:photokit-extension` → `:app:ios:extension` — with the
verified ride-alongs in one diff, per PLAN 13a:

- `git mv app/ios/photokit-extension app/ios/extension`; `settings.gradle.kts` include. No
  Kotlin body, package, signature, or behavior change; packages keep `app.snapsync.ios.upload`.
- `iosApp/iosApp.xcodeproj/project.pbxproj`: the `embedAndSignAppleFrameworkForXcode` run-script
  task path + the two `FRAMEWORK_SEARCH_PATHS` entries pointing into the module's build dir.
- `.github/actions/ios-archive/action.yml` (the extension compile task path), `ios.yml` comment.
- Root `build.gradle.kts` `appShellSources` (the detekt shell measurement's source list).
- `:test:architecture` `ExtensionSafetyTest.extensionLinkedRoots` — coverage moves, it does not
  shrink (the non-vacuity twin alone would NOT catch a stale entry here, because
  `adapter/ios/ext-safe` keeps the scan non-empty).
- Framework identity is UNCHANGED: `baseName = "SnapSyncUploadKit"` (RuntimeIdentityTest pin) —
  the module seat renames, the OS-visible framework does not.

## Impact

- Specs: `ios-photokit-upload` (the two requirement-text mentions of the module seat). The
  capability id/spec name stays `ios-photokit-upload` — it names the OS-driven PhotoKit upload
  tier (the capability), not the module.
- No runtime behavior change; gated by `ios-build` (the Xcode archive compiles the renamed
  module's framework).
