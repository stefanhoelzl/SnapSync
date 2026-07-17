# Tasks — extract-adapter-modules

## 1. Create the adapter modules
- [x] 1.1 `:adapter:generic` (jvm + ios targets; sqldelight plugin, two databases with disjoint srcDirs)
- [x] 1.2 `:adapter:ios:ext-safe` (ios targets; iosMain sources)
- [x] 1.3 `:adapter:ios:app-only` (ios targets; iosMain sources)
- [x] 1.4 `settings.gradle.kts`: include the three, drop the two deleted modules

## 2. Moves (git mv, zero body edits)
- [x] 2.1 Ktor clients ×8 + SQLDelight stores ×2 (+ `.sq`/`.sqm` dirs) → `:adapter:generic`
- [x] 2.2 Self-contained MockEngine tests ×4 → `:adapter:generic` commonTest
- [x] 2.3 iosMain impls ×17 → `:adapter:ios:ext-safe`; `IosKeychainTest` → its iosTest
- [x] 2.4 iosMain impls ×5 → `:adapter:ios:app-only`
- [x] 2.5 Delete `:app:ios:photokit-discovery` and `:app:ios:url-session-upload` (contents moved)

## 3. Rewiring
- [x] 3.1 `:app:ios` + `:app:ios:photokit-extension` build files compose the adapter modules; baseNames untouched
- [x] 3.2 `:test:world`, `:test:integration`, `:app:desktop`, `:capability:push` gain `:adapter:generic`
- [x] 3.3 Emptied source-set blocks and dead deps pruned (engine, download-store, gallery, keychain, logging, permission, config, attest, album, membership, join, download, event-creation-ui)
- [x] 3.4 Root build: prune both deleted modules from `appShellSources`

## 4. Guards + diagrams
- [x] 4.1 `KeychainContainmentTest` owning module → `/adapter/ios/ext-safe/`
- [x] 4.2 Extension-safety gate deliberate-red (plant UIKit import, watch fail, remove, green)
- [x] 4.3 `tools/diagrams` `Scan.kt` + `Zones.kt` walk lists gain `adapter`; regenerate; verify adapters appear
- [x] 4.4 `RuntimeIdentityTest` + full `:test:architecture:test` green

## 5. Verification + ceremony
- [x] 5.1 `./gradlew build` green
- [x] 5.2 `./gradlew compileIosMainKotlinMetadata` green
- [x] 5.3 Beacon before/after measured; no law increase
- [x] 5.4 CLAUDE.md Modules list + invalidated references updated
- [x] 5.5 Spec deltas validated `--specs --strict`; archive
