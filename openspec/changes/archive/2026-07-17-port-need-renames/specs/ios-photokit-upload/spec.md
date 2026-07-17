# ios-photokit-upload — delta for port-need-renames

## MODIFIED Requirements

### Requirement: Background upload extension target

On iOS ≥26.1 the system SHALL provide an iOS app-extension target conforming to the iOS 26.1 `PHBackgroundResourceUploadExtension` protocol (an ExtensionKit `AppExtension`, declared via a `@main` Swift principal class), embedded in the host app with `NSExtensionPointIdentifier = com.apple.photos.background-upload`. The platform-agnostic upload **orchestration** — the upload cycle (`UploadCycle`), the fine-grained OS-verb platform seam (`BackgroundTransfer`), the discovery-cursor port (`DiscoveryStore`), and the config assembly (`UploadConfig`/`buildUploadConfig`) — SHALL live in a Kotlin Multiplatform capability module `:capability:upload` that declares **`jvm()`** alongside `iosArm64`/`iosSimulatorArm64`, depending only on `:domain:engine` and `:domain:gallery` (for the shared `assetIdFromUploadKey` parser) — no Compose/UI. Because that module has a `jvm()` target, its orchestration tests run on JVM (and the iOS simulator) per testing rule 1. The **iOS platform adapters** (`IosPhotoKitUploadPlatform` — renamed from `IosBackgroundTransfer` — and `IosDiscoveryStore`), the composition root (`UploadExtensionRoot`), and the compile-time host read (`uploadHostFromBundle`) SHALL live in a lean `:app:ios:photokit-extension` module that **composes** `:capability:upload` (plus `:capability:upload-url`'s real `EdgeUploadRequestProvider` and `:capability:config`'s Keychain-backed `ConfigSource`) and the shared PhotoKit discovery module `:app:ios:photokit-discovery` (the `IosDiscovery` change-token walk + request builder + token archiver, shared with the `ios-url-session-upload` adapter), and is packaged as its own static framework. The Swift shell SHALL be a thin pass-through that forwards `process()` and `notifyTermination()` into the Kotlin core; all discovery, decision, ledger, and job-disposition logic SHALL be Kotlin/Native. The extension `Info.plist` SHALL declare `BackgroundUploadURLBase` as the build setting `$(BACKGROUND_UPLOAD_URL_BASE)` (the compile-time edge host the system permits). The extension SHALL NOT relax App Transport Security: the `Info.plist` SHALL declare no `NSAppTransportSecurity` exception (no `NSAllowsLocalNetworking`, no `NSAllowsArbitraryLoads`), so default ATS applies and the upload host MUST be a valid HTTPS endpoint. Supplying a non-HTTPS host is a build/configuration error; iOS blocks the plaintext request at the platform level.

#### Scenario: Extension declares the PhotoKit background-upload point
- **WHEN** the extension target is built
- **THEN** its Info.plist declares `NSExtensionPointIdentifier = com.apple.photos.background-upload`, a principal class, and `BackgroundUploadURLBase = $(BACKGROUND_UPLOAD_URL_BASE)`, it links the `:app:ios:photokit-extension` framework (which composes `:capability:upload`), and it declares **no** `NSAppTransportSecurity` exception (default HTTPS-only ATS)

#### Scenario: Logic is Kotlin, shell is thin
- **WHEN** the system invokes `process()` on the Swift principal class
- **THEN** the shell delegates to the Kotlin core, which performs all discovery, engine decisions, ledger writes, and job disposition

#### Scenario: Orchestration is JVM-reachable
- **WHEN** the upload orchestration's tests are run
- **THEN** because `UploadCycle`/`BackgroundTransfer`/`DiscoveryStore`/`UploadConfig` live in `:capability:upload` (a `jvm()`-enabled module), the tests execute on JVM **and** `iosSimulatorArm64`, not on the iOS targets alone

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
- **THEN** the extension constructs the `LedgerWriter` and the app constructs none — it reads the ledger only through `LedgerStore`'s read and reset-family operations

### Requirement: Disabling the extension clears orphaned REQUESTED rows

The app SHALL recover the in-flight jobs wiped by a disable. Disabling the upload extension
(`setUploadJobExtensionEnabled(false)`) deletes the system's `AssetResourceUploadJobConfiguration` and
therefore **wipes every in-flight OS upload job**. Whenever
the app disables the extension it SHALL, immediately after the disable, **both** (a) call the ledger's
`clearRequested()` (`sync-ledger`) to drop the now-orphaned `REQUESTED` rows, and (b) **reset the
discovery cursor** (clear the App-Group change-token) so the next cycle does a **full re-enumeration**.
Both are required: `clearRequested()` only makes the keys *absent*, but a settled cursor scans
incrementally and would never re-surface them — so without the cursor reset the cleared photos are
re-discovered only when the library next changes. This SHALL apply to **both** disable paths: the
disable half of the `disable→enable` re-register, and the leave use-case's extension-disable.

The disable-and-clear SHALL be **awaited off the main thread and completed before any re-enable**. The
`clearRequested()` write SHALL run on `Dispatchers.Default` (Kotlin/Native has no `Dispatchers.IO`),
never on the `Dispatchers.Main` scope — it is a synchronous SQLite `DELETE` that on the main thread is
a hang risk under cross-process WAL contention — and SHALL use a small bounded retry around the write.
The `disable→enable` re-register SHALL NOT call `setUploadJobExtensionEnabled(true)` until the clear
has completed, so the re-enabled extension's freshly recorded `REQUESTED` rows can never be deleted by
a still-running clear. The clear SHALL NOT be fire-and-forget. The bounded-retry, off-main clear is
pure logic and SHALL live in a tested `domain`/`capability` helper injected into both disable paths,
not in the untested app shell; only the sequencing of the two iOS platform calls remains in the shell.

Without `clearRequested()`, the rows stay `REQUESTED` forever: the engine treats `REQUESTED` as
in-flight and never re-issues it, there is no API to enumerate live jobs to detect that the job is
gone, and a same-event cycle never reconciles — so the photos that were mid-upload at the disable are
permanently abandoned. With both clears, the next full enumeration re-discovers the cleared keys and
re-creates exactly the not-yet-stored jobs (stored files remain `COMPLETED` and are skipped). The app
SHALL route both disable paths through a single helper so they cannot diverge, and SHALL use the
`LedgerStore` directly (constructing no `LedgerWriter`), since `clearRequested` is an app-side
reset-family operation.

#### Scenario: A re-register self-heals instead of orphaning

- **WHEN** photos are mid-upload (`REQUESTED` rows, OS jobs registered, the discovery cursor settled)
  and the app re-registers the extension (disable→enable)
- **THEN** the disable wipes the OS jobs, `clearRequested()` drops the `REQUESTED` rows, and the
  discovery cursor is reset — so the next cycle's full re-enumeration re-discovers and re-creates the
  not-yet-stored jobs (bytes resume landing), with no permanently-stuck `REQUESTED`

#### Scenario: The re-enable does not race the clear

- **WHEN** the app re-registers the extension (disable→enable)
- **THEN** `clearRequested()` runs off-main and completes **before** `setUploadJobExtensionEnabled(true)`
  is called, so no `REQUESTED` row recorded by the re-enabled extension is deleted by the clear

#### Scenario: The clear runs off the main thread

- **WHEN** a disable triggers `clearRequested()`
- **THEN** the SQLite delete executes on `Dispatchers.Default` (not the `Dispatchers.Main` scope) with
  a bounded retry, and is awaited rather than launched fire-and-forget

#### Scenario: Leave clears REQUESTED

- **WHEN** the leave use-case disables the extension while resources are `REQUESTED`
- **THEN** `clearRequested()` runs as part of the disable, leaving no orphaned `REQUESTED` rows behind

#### Scenario: Completed rows survive the clear

- **WHEN** a disable triggers `clearRequested()` and the ledger holds `COMPLETED` rows for
  already-stored files
- **THEN** those `COMPLETED` rows are retained, so a subsequent reconcile/discovery does not re-upload
  already-stored bytes

### Requirement: Post a cross-process liveness notification after each cycle

The extension SHALL post a **named cross-process Darwin notification** (via `CFNotificationCenter`'s
Darwin notify center) after every `process()` run — once `cycle.run()` returns, regardless of the
tri-state result (`completed` / `processing` / `failure`) — to signal the main app that the ledger may
have changed and status should be re-read. The post SHALL be **payload-free** (its only
promise is "re-read the truth", so coalescing and missed signals are harmless) and SHALL be made from
the **extension composition root** (`UploadExtensionRoot`), **not** from `LedgerStore` — the ledger
backend continues to post no cross-process notification (its change flow stays in-process). The post
SHALL be **unconditional** (fired on every run, so both a rising in-flight count and a drain are
signalled) and best-effort (a post failure SHALL NOT affect the returned processing result).

This is the extension→app half of the notify-driven status refresh; the app-side observer that
re-reads the ledger on this notification is specified in `ios-app-shell`, and the status source's
response is specified in `sync-status`.

#### Scenario: A completed cycle posts the liveness notification
- **WHEN** `cycle.run()` returns and `process()` is about to return `completed`
- **THEN** the extension has posted the payload-free Darwin liveness notification

#### Scenario: A processing (still-draining) cycle also posts
- **WHEN** `cycle.run()` returns and `process()` is about to return `processing` (pending rows remain)
- **THEN** the extension has posted the Darwin liveness notification (so the app reflects the rising /
  in-flight state), independent of the result

#### Scenario: The backend still posts no cross-process ding
- **WHEN** the extension writes the ledger during the cycle
- **THEN** `LedgerStore` posts no cross-process notification; the only cross-process post is the
  composition-root liveness notification after the cycle
