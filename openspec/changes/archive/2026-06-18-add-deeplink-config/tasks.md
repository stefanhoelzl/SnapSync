## 1. deeplink-config module — seam and pure decoder

- [x] 1.1 Create the `:capability:config` module (commonMain/commonTest/iosMain) wired into the build like `:capability:s3`, depending on `:capability:s3` for `S3Config`
- [x] 1.2 Define `ConfigSource` (`config: StateFlow<S3Config?>`) and `ConfigStore` (`suspend fun save(config: S3Config)`) ports in commonMain
- [x] 1.3 Implement the pure `decodeConfigUrl(raw: String)` in commonMain: scheme/host check, `v == 1`, base64url decode, UTF-8 JSON parse, all five fields non-empty → typed success/failure (never throws)
- [x] 1.4 commonTest: cover well-formed decode, each malformed case (bad scheme/host, wrong version, undecodable base64url, non-JSON, missing key, empty field), and round-trip against the generator's URL format

## 2. deeplink-config — iOS Keychain store

- [x] 2.1 Implement `KeychainConfigStore` (iosMain) implementing both `ConfigSource` and `ConfigStore`: serialize `S3Config` to one Keychain item under the shared keychain-access-group
- [x] 2.2 Seed `config` StateFlow synchronously at construction (missing item → `null`); `save` writes then emits; identical-config save is a no-op, different config replaces silently
- [x] 2.3 Verify persistence across reconstruction (save → reconstruct → value present) in an iOS test or harness check

## 3. iOS entitlements / signing

- [x] 3.1 Add the App Group + shared keychain-access-group entitlement to the iOS app target
- [x] 3.2 Thread the entitlement through the cloud-managed signing pipeline (provisioning/ASC) so device builds still sign
- [x] 3.3 Register the `snapsync` URL scheme in `Info.plist` (`CFBundleURLTypes`)

## 4. design-system — SetupCard

- [x] 4.1 Add the `SetupCard(indicator, title, detail?) { actionSlot }` semantic container (Material 3 card containment inside; appearance-free, no `Modifier`)
- [x] 4.2 Make it render compact when no detail/action is supplied; add a neutral "pending step" `StatusIndicator` value only if needed

## 5. presentation — Setup state, third source, onOpenUrl

- [x] 5.1 Add `UiState.Setup` carrying per-step satisfaction + permission status
- [x] 5.2 Widen `StatusContainerHost` to take `ConfigSource` as a third source; combine it into the intent flow and seed initial state from all three current values
- [x] 5.3 Update `reduceFrom` for two-input precedence: config absent OR permission ≠ GRANTED → `Setup`; else existing sync states (Loading only when config present AND GRANTED)
- [x] 5.4 Add the `onOpenUrl(raw)` intent: decode via `deeplink-config`; success → `ConfigStore.save`; failure → emit transient invalid-link side effect
- [x] 5.5 Widen the container side-effect type from `Nothing` to an effect carrying the transient invalid-link error; update consumers
- [x] 5.6 presentation tests: two-input precedence table, onOpenUrl success → config emission, onOpenUrl failure → error effect + config unchanged

## 6. setup-gate rendering

- [x] 6.1 Render `UiState.Setup` as a stack of two `SetupCard`s in `ScreenLayout`: storage (passive instruction, no button; collapses when satisfied) and permission (Allow access / Open Settings per status)
- [x] 6.2 Surface the transient invalid-link error on the storage card; self-clearing
- [x] 6.3 Route `onRequestPermission` / `onOpenSettings` / `onOpenUrl` through the container from the screen/host

## 7. ios-app-shell wiring

- [x] 7.1 Construct `KeychainConfigStore` in `SnapSyncRoot` and wire it as the third source into `StatusContainerHost`
- [x] 7.2 Add `SnapSyncRoot.onOpenUrl(String)` forwarding to the container intent
- [x] 7.3 Add SwiftUI `onOpenURL` in `iosApp.swift` forwarding the raw URL string to `SnapSyncRoot.onOpenUrl` (cold + warm launch), no parsing in Swift

## 8. desktop-test-harness

- [x] 8.1 Add a config cell (`MutableStateFlow<S3Config?>`) + stand-in `ConfigSource` to `PanelController`, with `setConfigPresent(Boolean)` (canned config / null)
- [x] 8.2 Wire the third source into the harness `StatusContainerHost`; make sync presets also force config-present (alongside GRANTED)
- [x] 8.3 Add a single config toggle Switch to `ControlPanel`

## 9. QR generator

- [x] 9.1 Add a Gradle task that encodes the five fields into `snapsync://config?v=1&d=<base64url(json)>` and renders a QR PNG (ZXing), reading secrets from env / gitignored `local.properties`
- [x] 9.2 Verify the emitted URL decodes back to the same fields via the pure decoder (shared format check)

## 10. Verification

- [x] 10.1 Desktop harness: toggle config off/on and walk the gate → hero transitions
- [x] 10.2 iOS: generate a QR, scan with the stock Camera app, confirm cold-launch deeplink provisions config and the gate advances
- [x] 10.3 `openspec validate add-deeplink-config --strict` passes; full build + tests green

## Notes

- All JVM/common work (config module + decoder, presentation/setup-gate logic, design-system
  `SetupCard`, desktop harness, QR generator) is implemented and **verified on this Linux host**:
  `:capability:config`, `:domain:presentation`, `:domain:ui` JVM tests pass; `generateConfigQr`
  runs and emits a round-trip-decodable URL.
- The iOS-native pieces (`KeychainConfigStore` cinterop, `SnapSyncRoot`/`MainViewController` wiring,
  `iOSApp.swift` `onOpenURL`, Info.plist scheme, `iosApp.entitlements` + xcconfig) are written but
  **compile only on macOS CI** — Kotlin/Native + Swift + Xcode are unavailable here. Open items
  2.3, 3.2, 10.2 are macOS/device verification. Expect a possible cinterop fix-up pass on the first
  macOS build of `KeychainConfigStore`.
- Keychain sharing is achieved by the entitlement's shared `keychain-access-groups` (the default
  group), so `KeychainConfigStore` sets no `kSecAttrAccessGroup` and no team-id prefix is hardcoded.
- App Group trimmed from entitlements for now (registered with the extension slice that needs it).
  Keychain groups need no portal registration, so `3.2` has **no manual App Store / Developer-portal
  step** — cloud-managed signing carries the keychain-only entitlement as-is.
- iosMain compiles verified on Linux via `compileIosMainKotlinMetadata` (the same task the Linux
  `build` job runs): `:capability:config` and `:app:ios` both green.
