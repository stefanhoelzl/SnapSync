## MODIFIED Requirements

### Requirement: Background upload extension target

The system SHALL provide an iOS app-extension target conforming to the iOS 26.1 `PHBackgroundResourceUploadExtension` protocol (an ExtensionKit `AppExtension`, declared via a `@main` Swift principal class), embedded in the host app with `NSExtensionPointIdentifier = com.apple.photos.background-upload`. The extension's logic SHALL live in a lean Kotlin Multiplatform module (`:app:ios:photokit-extension`) that depends on `:domain:engine`, `:capability:s3` (the real `S3UploadRequestProvider`), and `:capability:config` (the Keychain-backed `ConfigSource`) — no Compose/UI — packaged as its own static framework. The Swift shell SHALL be a thin pass-through that forwards `process()` and `notifyTermination()` into the Kotlin core; all discovery, decision, ledger, and job-disposition logic SHALL be Kotlin/Native. The extension `Info.plist` SHALL declare `BackgroundUploadURLBase` as the build setting `$(BACKGROUND_UPLOAD_URL_BASE)` (the compile-time upload host the system permits), and SHALL include an App Transport Security `NSAllowsLocalNetworking` exception so a presigned `PUT` to a private/local host over plaintext HTTP is permitted (the public HTTPS endpoint is unaffected).

#### Scenario: Extension declares the PhotoKit background-upload point
- **WHEN** the extension target is built
- **THEN** its Info.plist declares `NSExtensionPointIdentifier = com.apple.photos.background-upload`, a principal class, `BackgroundUploadURLBase = $(BACKGROUND_UPLOAD_URL_BASE)`, and `NSAllowsLocalNetworking`, and it links the `:app:ios:photokit-extension` framework

#### Scenario: Logic is Kotlin, shell is thin
- **WHEN** the system invokes `process()` on the Swift principal class
- **THEN** the shell delegates to the Kotlin core, which performs all discovery, engine decisions, ledger writes, and job disposition

### Requirement: Engine-gated real upload-job creation

For each discovered `Resource` the extension SHALL drive the shared `SyncEngine` with `ResourceChanged` and act on the decision. On a `Work` decision (`Upload`/`ReUpload`) it SHALL build the destination request from the real `S3UploadRequestProvider` (a presigned S3 `PUT` minted from the assembled `S3Config`), create a system upload job via `creationRequestForJob(destination:resource:)`, and the engine SHALL `recordRequested`. On `AlreadyUploaded` it SHALL create no job and write nothing. The extension SHALL NOT record `COMPLETED` in this capability — completion and retry adjudication remain out of scope; the upload either succeeds against the real destination or not, and that outcome is observed out-of-band (the object landing in the bucket), not reduced into the ledger here.

#### Scenario: New resource emits a real presigned destination and records REQUESTED
- **WHEN** the engine returns a `Work` decision for a discovered resource
- **THEN** a real presigned S3 `PUT` destination is minted from the `S3UploadRequestProvider`, a system upload job is created with it, and the ledger records `REQUESTED` for the key

#### Scenario: Already-recorded resource is skipped
- **WHEN** the engine returns `AlreadyUploaded` for a discovered resource
- **THEN** no system job is created and the ledger is not written

### Requirement: Drain-all job disposition

The extension SHALL acknowledge every system upload job it is handed regardless of the system's upload outcome, so the queue drains and `process()` returns a terminal result. It SHALL NOT perform retry/backoff adjudication or completion recording in this capability. A re-handed job whose key is already recorded SHALL be acknowledged as an idempotent no-op (no new destination, no ledger write).

#### Scenario: Jobs are acknowledged to drain
- **WHEN** `process()` fetches existing system upload jobs
- **THEN** each is acknowledged so the queue drains, without recording completion or issuing a real retry

#### Scenario: Already-recorded re-handed job is a no-op
- **WHEN** a re-handed job maps to a key the ledger already holds
- **THEN** the job is acknowledged and no destination is emitted and nothing is written

## ADDED Requirements

### Requirement: Extension assembles config from the Keychain payload and compile-time host

The extension SHALL assemble the `S3Config` it hands to `S3UploadRequestProvider` from two sources: the runtime `S3ConfigPayload` (`bucket`, `region`, `accessKeyId`, `secretAccessKey`) read synchronously from the **shared Keychain** via the `:capability:config` `ConfigSource`, and the compile-time upload **host** read from the extension bundle's `BackgroundUploadURLBase` (`NSBundle` info dictionary), used as `S3Config.endpoint`. When the Keychain payload is **absent** (the extension woke before the user completed setup), the extension SHALL log and complete the cycle as a successful no-op — creating no job and writing nothing — never crashing.

#### Scenario: Config present — provider built from host + payload
- **WHEN** `process()` runs with an `S3ConfigPayload` present in the shared Keychain
- **THEN** the extension constructs `S3Config` with `endpoint` from `BackgroundUploadURLBase` and `bucket`/`region`/`accessKeyId`/`secretAccessKey` from the payload, and builds `S3UploadRequestProvider` from it

#### Scenario: Config absent — cycle skipped cleanly
- **WHEN** `process()` runs with no `S3ConfigPayload` in the shared Keychain
- **THEN** the extension logs the absence and returns a terminal success, creating no upload job and writing nothing to the ledger

### Requirement: App primes Local Network access for the upload host

On launch, when a config payload is present, the host app SHALL make a throwaway connection to the compile-time upload host (`BackgroundUploadURLBase`) to surface and satisfy the iOS Local Network permission prompt before the background extension — which cannot present a prompt — runs. Against a public HTTPS endpoint this is a harmless no-op (no Local Network permission applies); against a private/local host it grants the app-wide permission the extension's uploads depend on. A failure of this throwaway connection SHALL NOT affect app startup or sync.

#### Scenario: Priming touches the configured host at launch
- **WHEN** the app launches with a config payload present
- **THEN** it issues one fire-and-forget request to the `BackgroundUploadURLBase` host, ignoring the result, and continues startup regardless of outcome
