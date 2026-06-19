## 1. Spike: BackgroundUploadURLBase host validation (gates the HTTP decision)

- [ ] 1.1 Build a dev IPA via `workflow_dispatch` with `upload_host=http://<lan-ip>:9000` (this depends on task 5 being in place; until then, hand-edit the extension `Info.plist` host locally for the spike build), install on device, and watch `idevicesyslog` while a real upload job is created.
- [ ] 1.2 Determine whether `BackgroundUploadURLBase` accepts `http://` + bare IP + port (job accepted) or rejects it (host-validation error) — record the verdict in the change. If `https`-only, switch the test rig (task 6) to MinIO-over-TLS and adjust D4.

## 2. Config model: host leaves the runtime payload (`deeplink-config`)

- [x] 2.1 Introduce `S3ConfigPayload` (`bucket`, `region`, `accessKeyId`, `secretAccessKey`) in `:capability:config`; keep `S3Config` (5 fields) in `:capability:s3` unchanged.
- [x] 2.2 Update `ConfigDeeplink.kt`: encode/decode the 4-key payload, bump `v=1 → v=2`, reject `v=1` and any `endpoint` key; remove `endpoint` from validation.
- [x] 2.3 Update `ConfigPorts.kt` (`ConfigSource.config: StateFlow<S3ConfigPayload?>`, `ConfigStore.save(S3ConfigPayload)`) and `KeychainConfigStore.kt` to store/seed the 4-field payload. (Ripples to `:domain:presentation` + `:app:desktop` consumers updated.)
- [x] 2.4 Update `QrGeneratorMain.kt` + `generateConfigQr` inputs: drop the endpoint/host field; read only the four payload fields from env/`local.properties`.
- [x] 2.5 Update `commonTest` codec round-trip + version tests for the 4-field `v=2` payload (runs on JVM + `iosSimulatorArm64`). JVM verified green.

## 3. Extension wiring: real provider + config assembly (`ios-background-upload`)

- [x] 3.1 Add `:capability:s3` + `:capability:config` dependencies to `:app:ios:photokit-extension`.
- [x] 3.2 Add iosMain glue to read `BackgroundUploadURLBase` from the extension bundle (`NSBundle` info dictionary) as the upload host. (`UploadHost.kt`)
- [x] 3.3 In `UploadExtensionRoot.kt`: read the `S3ConfigPayload` from the Keychain `ConfigSource`; when absent, log and return success (no job, no write); when present, assemble `S3Config(endpoint=host, …payload)` and build `S3UploadRequestProvider`. (Assemble-or-skip extracted to pure `buildS3Config` in `commonMain` so it's testable off-device.)
- [x] 3.4 Replace `DummyUploadRequestProvider` with the real provider in the engine wiring; delete `DummyUploadRequestProvider.kt`. (`UploadCycleTest` now uses a local stub provider.)
- [x] 3.5 Update/extend `UploadCycle`/config-sourcing `commonTest` (present → provider built; absent → clean skip). (`UploadConfigTest`; runs on macOS-CI simulator — no JVM target in this module.)
- [x] 3.6 Verify iOS compile via the Linux proxy `./gradlew compileIosMainKotlinMetadata` — green. (Simulator tests run on macOS CI.)

## 4. App composition: assemble + prime (`:app:ios`)

- [x] 4.1 In `SnapSyncRoot.kt`, source the `S3ConfigPayload` from `KeychainConfigStore` (decode path already feeds it); confirm setup-gate presence check still works on the payload. (Presence is a null check — type swap is transparent.)
- [x] 4.2 Add launch-time Local Network priming: one fire-and-forget `NSURLSession` request to the `BackgroundUploadURLBase` host when a payload is present; ignore the result, never block startup. (`LocalNetworkPriming.kt`, called from `SnapSyncRoot`.)

## 5. iOS project + CI: bake the host

- [x] 5.1 `iosApp/BackgroundUploadExtension/Info.plist`: set `BackgroundUploadURLBase = $(BACKGROUND_UPLOAD_URL_BASE)` and add `NSAppTransportSecurity` → `NSAllowsLocalNetworking`.
- [x] 5.2 App `Info.plist`: add `NSAllowsLocalNetworking` (for the priming request) + `BackgroundUploadURLBase = $(BACKGROUND_UPLOAD_URL_BASE)` so the app process can read the host for priming.
- [x] 5.3 `iosApp/Configuration/Config.xcconfig`: add `BACKGROUND_UPLOAD_URL_BASE` default `https://dummy.invalid` (via the `$()` `//`-escape so xcconfig doesn't comment-truncate the URL).
- [x] 5.4 `.github/workflows/ios.yml`: add `workflow_dispatch` with optional `upload_host` input; pass `BACKGROUND_UPLOAD_URL_BASE=${{ inputs.upload_host || 'https://dummy.invalid' }}` to the archive step. Plain pushes bake `dummy.invalid` (verified: `inputs` is empty off-dispatch). YAML validated.

## 6. Local S3 test rig (test equipment, no spec)

- [x] 6.1 Add terminal-QR rendering to the JVM QR generator: render the ZXing matrix as Unicode half-blocks (ANSI black/white) to stdout, alongside the existing PNG. Verified by running `generateConfigQr`.
- [x] 6.2 Write `scripts/local-s3.sh`: start MinIO via podman (`--network host`), create the bucket (ephemeral podman named volume, fresh each run, trap-cleanup on exit), auto-detect the LAN IP (env override), print the `upload_host` value to pass to the dispatch, and print the deeplink QR (terminal + PNG) carrying bucket/region/creds. Verified end-to-end in-sandbox. (Fully-qualified images for podman; named volume not bind mount — rootless podman can't grant the container write to a `/tmp` bind mount.)
- [x] 6.3 Stream uploads live: run `mc` (via the `minio/mc` container, `--network host`, self-configuring `MC_HOST_*` alias) + `mc watch --recursive` the bucket, printing each uploaded object. Verified: a pushed object streamed as `s3:ObjectCreated:Put … resources/AB12-photo.jpg`.

## 7. End-to-end on-device verification

- [ ] 7.1 Run `scripts/local-s3.sh`; dispatch a dev IPA build with the printed `upload_host`; download + install the artifact over usbmuxd.
- [ ] 7.2 Scan the QR (bucket/region/creds → Keychain), trigger an upload cycle, and confirm objects appear in the `mc watch` stream and the MinIO browser; capture `idevicesyslog` evidence.
- [ ] 7.3 Confirm the absent-config path (fresh install, no QR) skips cleanly with no crash.

## 8. Docs

- [x] 8.1 Document the local-S3 test loop (run script → dispatch with host → install → scan QR → watch) in `CLAUDE.md`, including that drain-all means success is verified in the bucket, not app state.
