# iosApp: the Xcode host

The Xcode project: the app target and the `BackgroundUploadExtension` target. **Not a Gradle project.** The Swift
shells and the project compile **only on macOS CI** (`macos-26`) or through the `ssh-mac-build` skill; there is no
Swift toolchain on this machine. Structure and rationale: `docs/architecture.md` §11. The Kotlin roots' rules are in
`app/ios/AGENTS.md`, the adapters' in `adapter/ios/AGENTS.md`.

## Swift is a pure transcriber

Swift forwards raw, ObjC-visible OS inputs **whole**; every decision is Kotlin. `SwiftShellGuardTest` pins the
decision keywords (`if` / `guard` / `switch` at zero, one `??`). Keep edits to the extension shell minimal.

⚠️ Key the Compose scene on the **app-level** `didBecomeActive` notification, never `sceneDidBecomeActive`:
`dvt launch` foregrounds the process without connecting a scene session, so a scene-level hook gives a black screen
and kills the headless screenshot loop (measured 2026-08-06).

## Never `NSLog` for diagnostics

**Do not reach for `NSLog` when debugging, not even "just this once", not even from Swift.** An interpolated
`NSLog("x \(y)")` is a *dynamic format string*, which os_log redacts wholesale: the line never appears in
`idevicesyslog`, and the capture looks like "the code never ran". It was ignored once (2026-07-16) and burned a full
build/install/scan cycle. From Swift, route diagnostics through Kotlin (`SnapSyncRoot`) so they land in `debug.log`.

## Info.plist rules (each one shipped broken once, or would)

- **`CADisableMinimumFrameDurationOnPhone = true` is mandatory** in the app's plist: Compose MP ≥1.7 hard-aborts at
  launch without it.
- The app registers **no** `CFBundleURLTypes`: the `snapsync` scheme is retired, and a scheme re-added here would
  route links the one authoritative codec no longer accepts.
- **`BackgroundUploadURLBase` must be in BOTH plists.** `assetsd` reads it from a bundle's own `Info.plist` to
  validate the upload registration; absent, `setUploadJobExtensionEnabled(true)` fails with a bare
  `PHPhotosErrorDomain -1` and nothing uploads on the PhotoKit tier (builds 675/687). It is composed as
  `$(UPLOAD_SCHEME)://$(UPLOAD_HOST)/api/v2`, never carried whole, because `//` opens a comment on an xcconfig line.
- **`PHPhotoLibraryPreventAutomaticLimitedAccessAlert = true` must be in BOTH plists**: each process reads it from
  its own bundle (`adapter/ios/AGENTS.md`, fact 1).
- Deployment values go in the generated `Deployment.plist`, **never** `Deployment.xcconfig`, where `//` truncated
  the DSN to `https:` (`docs/deployment.md` §5).

## Entitlements

- The App Group `group.app.snapsync` and the associated domain `applinks:snapsync.stho.net` must be **enabled on the
  App IDs in the portal** (the `asc-portal` skill), or signed builds fail to provision. Enabling the associated
  domain invalidates existing profiles.
- The Keychain group needs no portal step, but ⚠️ the device id must **name** it (`kSecAttrAccessGroup`): with no
  group named, a dev-signed build writes into each process's own group, and the app and the extension then hold two
  different device ids (2026-07-20).
- Never raise default data protection to `NSFileProtectionComplete`: it makes every App-Group file unreadable
  while locked, which kills the background tier (the entitlements guard in `:test:architecture`).
