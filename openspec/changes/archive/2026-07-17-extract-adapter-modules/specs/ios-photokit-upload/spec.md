# ios-photokit-upload — delta for extract-adapter-modules

## MODIFIED Requirements

### Requirement: Background upload extension target

On iOS ≥26.1 the system SHALL provide an iOS app-extension target conforming to the iOS 26.1 `PHBackgroundResourceUploadExtension` protocol (an ExtensionKit `AppExtension`, declared via a `@main` Swift principal class), embedded in the host app with `NSExtensionPointIdentifier = com.apple.photos.background-upload`. The platform-agnostic upload **orchestration** — the upload cycle (`UploadCycle`), the fine-grained OS-verb platform seam (`BackgroundTransfer`), and the config assembly (`UploadConfig`/`buildUploadConfig`) — SHALL live in a Kotlin Multiplatform capability module `:capability:upload` that declares **`jvm()`** alongside `iosArm64`/`iosSimulatorArm64` — no Compose/UI. Because that module has a `jvm()` target, its orchestration tests run on JVM (and the iOS simulator) per testing rule 1. The **iOS platform adapters** (`IosPhotoKitUploadPlatform` — renamed from `IosBackgroundTransfer`) with the composition root (`UploadExtensionRoot`) and the compile-time host read (`uploadHostFromBundle`) SHALL live in a lean `:app:ios:photokit-extension` module that **composes** `:capability:upload` and the extension-safe adapter module `:adapter:ios:ext-safe` (which, since migration step 4, carries the shared PhotoKit discovery — the `IosDiscovery` change-token walk + request builder + token archiver and the `IosDiscoveryStore` cursor store, shared with the `ios-url-session-upload` adapter, formerly `:app:ios:photokit-discovery` — plus the Keychain-backed `ConfigSource`, formerly `:capability:config`), and is packaged as its own static framework. The Swift shell SHALL be a thin pass-through that forwards `process()` and `notifyTermination()` into the Kotlin core; all discovery, decision, ledger, and job-disposition logic SHALL be Kotlin/Native. The extension `Info.plist` SHALL declare `BackgroundUploadURLBase` as the build setting `$(BACKGROUND_UPLOAD_URL_BASE)` (the compile-time edge host the system permits). The extension SHALL NOT relax App Transport Security: the `Info.plist` SHALL declare no `NSAppTransportSecurity` exception (no `NSAllowsLocalNetworking`, no `NSAllowsArbitraryLoads`), so default ATS applies and the upload host MUST be a valid HTTPS endpoint. Supplying a non-HTTPS host is a build/configuration error; iOS blocks the plaintext request at the platform level.

#### Scenario: Extension declares the PhotoKit background-upload point
- **WHEN** the extension target is built
- **THEN** its Info.plist declares `NSExtensionPointIdentifier = com.apple.photos.background-upload`, a principal class, and `BackgroundUploadURLBase = $(BACKGROUND_UPLOAD_URL_BASE)`, it links the `:app:ios:photokit-extension` framework (which composes `:capability:upload` and the adapter modules), and it declares **no** `NSAppTransportSecurity` exception (default HTTPS-only ATS)

#### Scenario: Logic is Kotlin, shell is thin
- **WHEN** the system invokes `process()` on the Swift principal class
- **THEN** the shell delegates to the Kotlin core, which performs all discovery, engine decisions, ledger writes, and job disposition

#### Scenario: Orchestration is JVM-reachable
- **WHEN** the upload orchestration's tests are run
- **THEN** because `UploadCycle`/`BackgroundTransfer`/`UploadConfig` live in `:capability:upload` (a `jvm()`-enabled module), the tests execute on JVM **and** `iosSimulatorArm64`, not on the iOS targets alone

#### Scenario: Extension adapters compose the capability
- **WHEN** the extension's composition root assembles a cycle
- **THEN** the iOS adapters (`IosPhotoKitUploadPlatform`, `IosDiscoveryStore`) implement the upload seams — sharing the `IosDiscovery` walk from `:adapter:ios:ext-safe` — and the root constructs the `:capability:upload` `UploadCycle`, with the download-store / rejoin / manifest edges wired in the composition root rather than in `:capability:upload`

#### Scenario: The app-driven tier applies below 26.1
- **WHEN** the app runs on iOS 18–26.0 (below the `PHBackgroundResourceUploadExtension` floor)
- **THEN** no PhotoKit upload extension is invoked; the `ios-url-session-upload` capability's app-driven path performs uploads instead, over the same `:capability:upload` orchestration
