## 1. New :capability:upload-url (edge URL builder)

- [x] 1.1 Create the `:capability:upload-url` module (Gradle build file, KMP common/ios/jvm targets, register in `settings.gradle.kts`) mirroring the s3 module's target set
- [x] 1.2 Move the URL-segment percent-encoder (uppercase `%XX` for bytes outside `[A-Za-z0-9._-]`) from `:capability:s3` `Encoding.kt` into this module's `commonMain`
- [x] 1.3 Implement `EdgeUploadRequestProvider(host, eventId, deviceId)` in `commonMain`: builds `UploadRequest(url = "<host>/event/<eventId>/device/<deviceId>/file/<encoded-filename>", headers = {Content-Type: resource.contentType}, resource = same instance)`; no network, no crypto, no metadata headers, no auth/Host
- [x] 1.4 `commonTest`: URL composition, filename encoding (unreserved passthrough, reserved/UTF-8/`/`→`%2F`, injectivity), headers are exactly `Content-Type`, no query string, byte-identical rebuild (stable/no-expiry). Verify they run on JVM and `iosSimulatorArm64`

## 2. Reshape :capability:config to EventConfigPayload (v=3)

- [x] 2.1 Replace `S3ConfigPayload` with `EventConfigPayload { eventId: String }` (`@Serializable`); update `ConfigSource`/`ConfigStore` port types to `EventConfigPayload?`
- [x] 2.2 Update `ConfigDeeplink`: `CONFIG_VERSION = 3`; encode `{eventId}`; decode requires `v==3`, exactly the `eventId` key, non-empty, canonical UUID (case-insensitive `8-4-4-4-12`); reject v1/v2 and non-UUID with a typed failure (never throw)
- [x] 2.3 Update `KeychainConfigStore` (iosMain) to store/read the serialized `EventConfigPayload`; keep the shared keychain-access-group; remove the `:capability:s3` API re-export from the config build file
- [x] 2.4 Update the authoritative QR-generator Gradle task to take `eventId` (env/local.properties) and emit `snapsync://config?v=3&d=…`; drop host/credential inputs
- [x] 2.5 `commonTest`: v=3 encode/decode round-trip; reject v=1/v=2; reject missing/empty/extra key; reject non-UUID eventId; accept canonical UUID. Run on JVM + simulator

## 3. Rewire the iOS extension composition

- [x] 3.1 Add an App-Group device-id store: a `commonMain` port (read-or-mint-and-persist over opaque storage) + an iosMain `NSUserDefaults(suite=group.app.snapsync)` adapter; mint with `NSUUID` (no UIKit), lowercase canonical UUID; `commonTest` for the orchestration with a fake
- [x] 3.2 Replace `buildS3Config`/`UploadConfig` with edge-config assembly: gather `eventId` (Keychain `ConfigSource`), `host` (`BackgroundUploadURLBase` from bundle), `deviceId` (App-Group store)
- [x] 3.3 Update `UploadExtensionRoot` to construct `SyncEngine(EdgeUploadRequestProvider(host, eventId, deviceId), ledger)`; skip the cycle (log + terminal success) when eventId payload is absent OR deviceId unavailable
- [x] 3.4 Set `Resource.metadata = emptyMap()` remains; confirm `contentType` flows into the Content-Type header via the new provider (retry path keeps the `application/octet-stream` fallback)
- [x] 3.5 Confirm the completion/retry drain rebuilds the destination via the edge provider (stable URL); no presign/expiry assumptions remain

## 4. Delete :capability:s3 and fix consumers

- [x] 4.1 Delete the `:capability:s3` module (SigV4 presigner, golden/known-answer tests, `S3Config`, `S3ConfigPayload`, `Encoding.kt`) and remove it from `settings.gradle.kts`
- [x] 4.2 Remove all `:capability:s3` dependencies and `app.snapsync.s3.*` imports across modules (config, extension, desktop, integration)
- [x] 4.3 Update `:app:desktop` `PanelController` `CANNED_CONFIG` to `EventConfigPayload(eventId = <valid UUID>)`
- [x] 4.4 Update `:test:integration` (and any other test module) to drop S3 types / use the edge provider or a fake

## 5. CI: bake the real edge host

- [x] 5.1 In `.github/workflows/ios.yml`, change the `BACKGROUND_UPLOAD_URL_BASE` default from `https://dummy.invalid` to `https://snap-sync-n8xmz.bunny.run`; keep the `upload_host` dispatch override; update the input description (S3 → edge / local Deno backend)

## 6. Docs and verification

- [x] 6.1 Edit `docs/design.md` §3.2/§4: record the eventId-only `v=3` payload, the lazy-mint App-Group deviceId, and that `name`/`startDate` + date-filtered discovery are deferred for this migration
- [x] 6.2 `./gradlew build` (compiles all targets + JVM/UI tests) and `./gradlew compileIosMainKotlinMetadata` (iOS proxy) both green
- [ ] 6.3 Branch → PR → `/ship`
- [ ] 6.4 On-device (post-merge, manual): scan a `v=3` test-event QR, trigger a sync, confirm objects land under `event/<id>/device/<id>/` in the bunny zone, and verify via `idevicesyslog`/edge logs that iOS uses a plain single-shot PUT (the OPTIONS/resumable-preflight check)
