## MODIFIED Requirements

### Requirement: Background upload extension target

On iOS ≥26.1 the system SHALL provide an iOS app-extension target conforming to the iOS 26.1 `PHBackgroundResourceUploadExtension` protocol (an ExtensionKit `AppExtension`, declared via a `@main` Swift principal class), embedded in the host app with `NSExtensionPointIdentifier = com.apple.photos.background-upload`. The platform-agnostic upload **orchestration** — the upload cycle (`UploadCycle`, `:domain` `feature/upload`), the fine-grained OS-verb platform seam (`BackgroundTransfer`, `:domain` `ports/`), and the config assembly (`UploadConfig`/`buildUploadConfig`, `:domain` `feature/upload`) — SHALL live in `:domain` (migration step 5; formerly `:capability:upload`), which declares **`jvm()`** alongside `iosArm64`/`iosSimulatorArm64` — no Compose/UI — so the orchestration tests run on JVM (and the iOS simulator) per testing rule 1. The extension SHALL assemble its cycle through the **shared composition** `uploadCore` (`:domain` `compose/`, spec `module-architecture` "One shared composition"): the root supplies only its ports and platform reads — the file-backed `ConfigReader`, the device-identity thunk, the compile-time host read, the PhotoKit platform adapter, and the generic HTTP adapters (`:adapter:generic:app`'s `HttpEnrollment` is the device-manifest uploader; there is no extension-local uploader copy). The **PhotoKit platform adapter** (`IosPhotoKitUploadPlatform`, the `BackgroundTransfer` impl) SHALL live in the extension-safe adapter module `:adapter:ios:ext-safe` — an adapter is placed by linkage and MAY branch on technology vocabulary (spec `module-architecture`; seated there at the migration finale — its former shell seat put adapter branching inside the zero-decision shell gate's scope), beside the shared PhotoKit discovery it delegates to (the `IosDiscovery` change-token walk + request builder + token archiver and the `IosDiscoveryStore` cursor store, shared with the `ios-url-session-upload` adapter) and the file-backed `ConfigSource`. The **compile-time host read** (`bakedUploadBase`, the `BackgroundUploadURLBase` bundle read) SHALL live in `:adapter:ios:ext-safe` beside the build-version read the boot banner uses, for the same two reasons: **both processes** read it (each `NSBundle.mainBundle` being its own bundle), and its absent-key defaulting is a **decision**, which the zero-decision shell gate forbids a wiring-only root to hold — the same reasoning that seated `IosPhotoKitUploadPlatform` there at the migration finale. The composition root (`UploadExtensionRoot`) SHALL live in a lean `:app:ios:extension` module that **composes** `:domain` (which also carries the upload receive seam in `feature/upload`), `:adapter:ios:ext-safe`, and `:adapter:generic:app`, and is packaged as its own static framework. The Swift shell SHALL be a thin pass-through that forwards `process()` and `notifyTermination()` into the Kotlin core; all discovery, decision, ledger, and job-disposition logic SHALL be Kotlin/Native. The extension `Info.plist` SHALL declare `BackgroundUploadURLBase` as the build setting `$(BACKGROUND_UPLOAD_URL_BASE)` (the compile-time edge host the system permits). The extension SHALL NOT relax App Transport Security: the `Info.plist` SHALL declare no `NSAppTransportSecurity` exception (no `NSAllowsLocalNetworking`, no `NSAllowsArbitraryLoads`), so default ATS applies and the upload host MUST be a valid HTTPS endpoint. Supplying a non-HTTPS host is a build/configuration error; iOS blocks the plaintext request at the platform level.

#### Scenario: Extension declares the PhotoKit background-upload point
- **WHEN** the extension target is built
- **THEN** its Info.plist declares `NSExtensionPointIdentifier = com.apple.photos.background-upload`, a principal class, and `BackgroundUploadURLBase = $(BACKGROUND_UPLOAD_URL_BASE)`, it links the `:app:ios:extension` framework (which composes `:domain` and the adapter modules), and it declares **no** `NSAppTransportSecurity` exception (default HTTPS-only ATS)

#### Scenario: Logic is Kotlin, shell is thin
- **WHEN** the system invokes `process()` on the Swift principal class
- **THEN** the shell delegates to the Kotlin core, which performs all discovery, engine decisions, ledger writes, and job disposition

#### Scenario: Orchestration is JVM-reachable
- **WHEN** the upload orchestration's tests are run
- **THEN** because `UploadCycle`/`BackgroundTransfer`/`UploadConfig` live in `:domain` (a `jvm()`-enabled module), the tests execute on JVM **and** `iosSimulatorArm64`, not on the iOS targets alone

#### Scenario: Extension adapters compose the capability
- **WHEN** the extension's composition root assembles a cycle
- **THEN** the iOS adapters (`IosPhotoKitUploadPlatform`, `IosDiscoveryStore`) implement the upload seams — sharing the `IosDiscovery` walk from `:adapter:ios:ext-safe` — and the root supplies them as `UploadPorts` to `uploadCore`, which constructs the `:domain` `feature/upload` `UploadCycle`, with the download-store / rejoin / manifest edges answered in the ports bundle rather than inside the feature

#### Scenario: The app-driven tier applies below 26.1
- **WHEN** the app runs on iOS 18–26.0 (below the `PHBackgroundResourceUploadExtension` floor)
- **THEN** no PhotoKit upload extension is invoked; the `ios-url-session-upload` capability's app-driven path performs uploads instead, over the same shared `:domain` orchestration assembled by the same `uploadCore`

#### Scenario: The extension's cycle is the shared composition
- **WHEN** `UploadExtensionRoot` assembles its upload cycle
- **THEN** it calls `uploadCore` over its ports — it constructs no cycle, gate, reconciler, or
  device-manifest producer of its own, and its device-manifest uploader is `:adapter:generic:app`'s
  `HttpEnrollment`
