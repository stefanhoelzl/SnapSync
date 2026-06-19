## Why

The iOS upload extension currently mints **dummy** destinations (`https://dummy.invalid/…`) and
never really uploads — the `ios-background-upload` slice deliberately parked the real provider
behind a one-line swap. The real `S3UploadRequestProvider` (capability `s3-request-provider`) is
built and golden-tested, but nothing wires it in, so no photo has ever left the device. This change
turns on real uploads end-to-end and makes them verifiable on a physical device against a local S3
server.

Doing so forces a config-model correction. Apple's `PHBackgroundResourceUploadExtension` validates
every upload against a **compile-time** host hardcoded in the extension's `Info.plist`
(`BackgroundUploadURLBase`) — a user-configurable upload host is impossible with this API. Carrying
the host in the runtime QR/deeplink config (as `deeplink-config` does today) is therefore redundant
and drift-prone: the runtime endpoint must always equal the baked one. We move the **host** to where
iOS already demands it (compile time) and keep only the genuinely-runtime fields (bucket, region,
credentials) in the QR.

## What Changes

- **Wire in the real provider.** The extension composition root builds `S3UploadRequestProvider`
  (replacing `DummyUploadRequestProvider`) from an `S3Config` assembled out of the compile-time
  upload host plus the Keychain-provided bucket/region/credentials. The dummy provider is removed.
- **Source config in the extension; skip cleanly when absent.** The extension reads the shared
  Keychain config at cycle start; when it is absent (extension woke before setup), it logs and
  returns success — no work, no crash — mirroring the app's setup gate.
- **BREAKING (config model): the upload host leaves the runtime config.** `deeplink-config` drops
  `endpoint` from the QR `ConfigPayload`, the Keychain payload, and decode-validation. The QR now
  carries `bucket`, `region`, `accessKeyId`, `secretAccessKey` only. The host comes from the
  compile-time `BackgroundUploadURLBase`; the composition root combines the two into `S3Config`.
  (`S3Config` and the `s3-request-provider` presigner contract are unchanged — only the *source* of
  each field moves.)
- **Make `BackgroundUploadURLBase` a build setting.** The extension `Info.plist` value becomes
  `$(BACKGROUND_UPLOAD_URL_BASE)`, defaulting to `https://dummy.invalid`, overridable per build.
- **Allow plaintext local HTTP.** Add `NSAllowsLocalNetworking` to the extension (and app) ATS
  config so a presigned `PUT` to `http://<lan-ip>:<port>` is permitted; the public HTTPS endpoint is
  unaffected. The main app primes the iOS Local Network permission against the baked host at launch,
  so the background extension (which cannot prompt) inherits the grant.
- **Bake a test host via `workflow_dispatch`.** `ios-sideload-delivery` gains an optional
  `upload_host` dispatch input; the archive injects it into `BACKGROUND_UPLOAD_URL_BASE`. A plain
  push leaves it `dummy.invalid` (inert); a deliberate dispatch bakes the LAN host into that build's
  dev IPA. `main`/TestFlight is never polluted.
- **Keep drain-all unchanged.** The extension still records only `REQUESTED` and acknowledges every
  job to drain — no completion/retry adjudication in this slice. Success is confirmed out-of-band
  (the bytes landing in the bucket), not via app state. Only the spec wording that asserts "dummy
  destinations cannot succeed" is softened, since they now can.
- **Local S3 test rig (test equipment, not spec'd).** A `scripts/local-s3.sh` launches MinIO via
  podman, creates the bucket, auto-detects the LAN IP, prints the host to pass as `upload_host`,
  renders the deeplink QR to the terminal (+ PNG fallback) carrying bucket/region/creds, and
  **streams each uploaded object live** (`mc watch`). This is QA tooling with no `SHALL`
  requirements.

## Capabilities

### New Capabilities
<!-- none -->

### Modified Capabilities
- `ios-background-upload`: "Engine-gated dummy job creation" becomes real-provider job creation
  (build the request from `S3UploadRequestProvider`, still `recordRequested`); a new requirement
  has the extension source config from the Keychain + the compile-time host and **skip the cycle
  cleanly when config is absent**; the extension target now depends on `:capability:s3` and
  `:capability:config` (was `:domain:engine` only), declares `BackgroundUploadURLBase` as
  `$(BACKGROUND_UPLOAD_URL_BASE)`, and adds `NSAllowsLocalNetworking`; "Drain-all job disposition"
  wording is softened (real jobs may now succeed) but its behavior is unchanged.
- `deeplink-config`: the QR/`ConfigPayload`, Keychain payload, and decode-validation drop
  `endpoint`; the payload becomes `{ bucket, region, accessKeyId, secretAccessKey }`. `ConfigSource`
  exposes this 4-field payload, and assembling `S3Config` (adding the compile-time host) moves to
  the iOS composition root.
- `ios-sideload-delivery`: add a `workflow_dispatch` trigger with an optional `upload_host` input;
  the archive step injects `BACKGROUND_UPLOAD_URL_BASE` from it (default `https://dummy.invalid`),
  so only a deliberate dispatch bakes a non-dummy host.

## Impact

- **Code — extension wiring**: `app/ios/photokit-extension/.../UploadExtensionRoot.kt` (swap
  provider, read config + host, skip-when-absent); delete
  `app/ios/photokit-extension/.../DummyUploadRequestProvider.kt`; new iosMain glue to read
  `BackgroundUploadURLBase` from the extension bundle (`NSBundle`).
- **Code — config model**: `capability/config/.../ConfigDeeplink.kt` (drop `endpoint` from
  `ConfigPayload` + codec + validation), `ConfigPorts.kt` (`ConfigSource` payload type),
  `KeychainConfigStore.kt`, and the JVM `QrGeneratorMain.kt`/`generateConfigQr` inputs; composition
  root `app/ios/src/iosMain/.../SnapSyncRoot.kt` assembles `S3Config` + primes Local Network.
- **Module deps**: `:app:ios:photokit-extension` gains `:capability:s3` + `:capability:config`.
- **iOS project**: `iosApp/BackgroundUploadExtension/Info.plist`
  (`BackgroundUploadURLBase = $(BACKGROUND_UPLOAD_URL_BASE)`, ATS), app `Info.plist`/entitlements as
  needed for priming, `iosApp/Configuration/Config.xcconfig`
  (`BACKGROUND_UPLOAD_URL_BASE = https://dummy.invalid` default).
- **CI**: `.github/workflows/ios.yml` — add `workflow_dispatch` `upload_host` input; pass
  `BACKGROUND_UPLOAD_URL_BASE=${{ inputs.upload_host || 'https://dummy.invalid' }}` to the archive
  step.
- **Test equipment**: new `scripts/local-s3.sh`; terminal-QR rendering added to the JVM QR generator.
- **Tests**: `deeplink-config` codec round-trip updated for the 4-field payload; extension
  `UploadCycle`/config-sourcing logic in `commonTest`.
- **Open spike (gates the http decision)**: confirm on-device whether `BackgroundUploadURLBase`
  accepts `http://` + bare IP + port or demands `https`; fallback is MinIO-over-TLS.
- **No change to**: `s3-request-provider` (presigner + `S3Config` shape), the merge gate / single
  archive, `ios-test`, sync ledger/engine semantics, completion/retry behavior.
