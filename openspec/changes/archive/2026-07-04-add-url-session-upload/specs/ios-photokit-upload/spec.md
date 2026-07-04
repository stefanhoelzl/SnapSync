# ios-photokit-upload delta

> **Capability renamed** from `ios-background-upload` (both tiers do background upload; the
> distinguishing axis is the *mechanism*, mirroring the platform classes). The base spec dir and this
> delta folder were renamed to `ios-photokit-upload` during apply (the delta model has no first-class
> capability rename); the `MODIFIED` requirements below (≥26.1 qualifiers) apply to the renamed base
> at archive.

## MODIFIED Requirements

### Requirement: Background upload extension target

On iOS ≥26.1 the system SHALL provide an iOS app-extension target conforming to the iOS 26.1 `PHBackgroundResourceUploadExtension` protocol (an ExtensionKit `AppExtension`, declared via a `@main` Swift principal class), embedded in the host app with `NSExtensionPointIdentifier = com.apple.photos.background-upload`. The platform-agnostic upload **orchestration** — the upload cycle (`UploadCycle`), the fine-grained OS-verb platform seam (`UploadJobPlatform`), the discovery-cursor port (`DiscoveryStore`), and the config assembly (`UploadConfig`/`buildUploadConfig`) — SHALL live in a Kotlin Multiplatform capability module `:capability:upload` that declares **`jvm()`** alongside `iosArm64`/`iosSimulatorArm64`, depending only on `:domain:engine` and `:domain:gallery` (for the shared `assetIdFromUploadKey` parser) — no Compose/UI. Because that module has a `jvm()` target, its orchestration tests run on JVM (and the iOS simulator) per testing rule 1. The **iOS platform adapters** (`IosPhotoKitUploadPlatform` — renamed from `IosUploadJobPlatform` — and `IosDiscoveryStore`), the composition root (`UploadExtensionRoot`), and the compile-time host read (`uploadHostFromBundle`) SHALL live in a lean `:app:ios:photokit-extension` module that **composes** `:capability:upload` (plus `:capability:upload-url`'s real `EdgeUploadRequestProvider` and `:capability:config`'s Keychain-backed `ConfigSource`) and the shared PhotoKit discovery module `:app:ios:photokit-discovery` (the `IosDiscovery` change-token walk + request builder + token archiver, shared with the `ios-url-session-upload` adapter), and is packaged as its own static framework. The Swift shell SHALL be a thin pass-through that forwards `process()` and `notifyTermination()` into the Kotlin core; all discovery, decision, ledger, and job-disposition logic SHALL be Kotlin/Native. The extension `Info.plist` SHALL declare `BackgroundUploadURLBase` as the build setting `$(BACKGROUND_UPLOAD_URL_BASE)` (the compile-time edge host the system permits). The extension SHALL NOT relax App Transport Security: the `Info.plist` SHALL declare no `NSAppTransportSecurity` exception (no `NSAllowsLocalNetworking`, no `NSAllowsArbitraryLoads`), so default ATS applies and the upload host MUST be a valid HTTPS endpoint. Supplying a non-HTTPS host is a build/configuration error; iOS blocks the plaintext request at the platform level.

#### Scenario: Extension declares the PhotoKit background-upload point
- **WHEN** the extension target is built
- **THEN** its Info.plist declares `NSExtensionPointIdentifier = com.apple.photos.background-upload`, a principal class, and `BackgroundUploadURLBase = $(BACKGROUND_UPLOAD_URL_BASE)`, it links the `:app:ios:photokit-extension` framework (which composes `:capability:upload`), and it declares **no** `NSAppTransportSecurity` exception (default HTTPS-only ATS)

#### Scenario: Logic is Kotlin, shell is thin
- **WHEN** the system invokes `process()` on the Swift principal class
- **THEN** the shell delegates to the Kotlin core, which performs all discovery, engine decisions, ledger writes, and job disposition

#### Scenario: Orchestration is JVM-reachable
- **WHEN** the upload orchestration's tests are run
- **THEN** because `UploadCycle`/`UploadJobPlatform`/`DiscoveryStore`/`UploadConfig` live in `:capability:upload` (a `jvm()`-enabled module), the tests execute on JVM **and** `iosSimulatorArm64`, not on the iOS targets alone

#### Scenario: Extension adapters compose the capability
- **WHEN** the extension's composition root assembles a cycle
- **THEN** the iOS adapters (`IosPhotoKitUploadPlatform`, `IosDiscoveryStore`) implement the `:capability:upload` seams — sharing the `IosDiscovery` walk from `:app:ios:photokit-discovery` — and the root constructs the `:capability:upload` `UploadCycle`, with the download-store / rejoin / manifest edges wired in the composition root rather than in `:capability:upload`

#### Scenario: The app-driven tier applies below 26.1
- **WHEN** the app runs on iOS 18–26.0 (below the `PHBackgroundResourceUploadExtension` floor)
- **THEN** no PhotoKit upload extension is invoked; the `ios-url-session-upload` capability's app-driven path performs uploads instead, over the same `:capability:upload` orchestration

### Requirement: Extension owns the single ledger writer

**On iOS ≥26.1** (the two-process PhotoKit tier) the extension process SHALL be the single holder of the `LedgerWriter` over the App-Group ledger, and the host app SHALL NOT construct a `LedgerWriter`. This binds the ledger's single-record-writer invariant (see `sync-ledger`) to the extension process on this tier. On iOS 18–26.0 there is no extension process and the **app** holds the writer (see `ios-url-session-upload`); the invariant is preserved on both tiers, only its process binding differs.

#### Scenario: Only the extension writes on ≥26.1
- **WHEN** the app and extension are both assembled on iOS ≥26.1
- **THEN** the extension constructs the `LedgerWriter` and the app constructs only `LedgerReader`/`LedgerWatcher`

### Requirement: Extension registration is a disable→enable toggle

**On iOS ≥26.1**, on a full photo-access grant the app SHALL register the background-upload extension with a
**disable→enable toggle** — `setUploadJobExtensionEnabled(false)` then `setUploadJobExtensionEnabled(true)` — rather than a bare enable. The system's `AssetResourceUploadJobConfiguration` is keyed by bundle id and **persists across app delete/reinstall and device reboot**; a stale record (e.g. left by a differently-signed build) makes a bare `enable(true)` fail with `PHPhotosError 3202` ("existing configuration record"), after which the system never launches the extension. The leading `enable(false)` deletes the stale record so `enable(true)` re-creates it cleanly for the currently-installed extension. On iOS 18–26.0 there is no such OS toggle; "enable" starts the app-driven pump and "disable" cancels it (see `ios-url-session-upload`).

#### Scenario: Stale registration is replaced, not rejected
- **WHEN** the app registers the extension on a grant on iOS ≥26.1 and a configuration record already exists
- **THEN** the existing record is deleted and a fresh one is inserted (no `3202` rejection), and the system can launch the extension
