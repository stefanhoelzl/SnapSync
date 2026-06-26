## Why

App Transport Security currently relaxes to permit plaintext (`http://`) PUTs, and the app fires a
Local Network priming probe, solely so the device could reach a **local** upload backend on the LAN
(the `scripts/local-s3.sh` MinIO rig) during on-device testing. Now that a real HTTPS backend is
deployed and device-facing, that local path is obsolete — and the non-secure allowance it justified
is an unnecessary attack surface that ships in every build (TestFlight included).

## What Changes

- **BREAKING (security posture):** Remove the `NSAllowsLocalNetworking` ATS exception from both the
  host app and the background-upload extension `Info.plist`. The app and extension become
  HTTPS-only (default ATS). Plaintext upload hosts no longer work — by design.
- Delete the Local Network priming probe (`LocalNetworkPriming.kt` + its call site). It only ever
  mattered for a private/LAN host; against a public HTTPS endpoint it is a no-op.
- Delete the local upload backend test rig (`scripts/local-s3.sh`, the MinIO loop).
- Keep the CI `upload_host` workflow_dispatch input, but **constrain it to `https://`** (the
  workflow fails fast on a non-https value). It can no longer bake a plaintext host.
- Single-source the deployed host: `Config.xcconfig` defaults `BACKGROUND_UPLOAD_URL_BASE` to the
  deployed HTTPS backend, and CI overrides the build setting **only** when `upload_host` is supplied
  (empty / plain push falls through to the xcconfig default). The host literal lives in one place.
- No runtime scheme guard in Kotlin: passing an HTTPS host is the developer's responsibility; iOS
  ATS enforces it at the platform level.
- The `backend/` Deno project and its local `deno run` convenience are **untouched** — only the
  device-pointed-at-LAN-over-http path is removed.
- Docs: delete the CLAUDE.md "real-s3-upload loop" section (leaving a one-line pointer to verifying
  against the deployed backend's storage zone).

## Capabilities

### New Capabilities
<!-- none -->

### Modified Capabilities
- `ios-background-upload`: Remove the requirement that the extension `Info.plist` include an
  `NSAllowsLocalNetworking` ATS exception (now HTTPS-only); remove the "App primes Local Network
  access for the upload host" requirement entirely.
- `ios-ci`: The "Compile-time edge host default and override" requirement changes and becomes the
  **single owner** of the compile-time upload-host contract — the default host comes from
  `Config.xcconfig` (the workflow no longer restates it; it overrides only on a non-empty
  `upload_host`), and the `upload_host` override is HTTPS-only (a non-https value fails the run)
  rather than "for pointing a dev IPA at a local Deno backend on the LAN".
- `ios-sideload-delivery`: **Removes** its "Optional compile-time upload host via workflow_dispatch"
  requirement, which duplicated the `ios-ci` host contract (the host is baked in the shared archive
  step and applies to TestFlight too, not just sideload). The sideload IPA simply inherits the host
  the shared archive bakes; no behavior is lost.

## Impact

- **iOS plists:** `iosApp/iosApp/Info.plist`, `iosApp/BackgroundUploadExtension/Info.plist` (drop
  the `NSAppTransportSecurity` dict).
- **Kotlin:** delete `app/ios/src/iosMain/kotlin/app/snapsync/ios/LocalNetworkPriming.kt`; remove
  the `primeLocalNetwork(...)` call in `SnapSyncRoot.kt`.
- **Build config:** `iosApp/Configuration/Config.xcconfig` (default host → deployed HTTPS URL).
- **CI:** `.github/workflows/ios.yml` (https guard on `upload_host`; override only when supplied;
  reword the DEBUG-config comment).
- **Test rig:** delete `scripts/local-s3.sh`.
- **Docs:** `CLAUDE.md` (remove the real-s3-upload loop section); spec files for the two modified
  capabilities. `docs/design.md` is essentially unaffected (the ATS/priming rationale lived in
  specs + plist comments, not the design doc).
- **No production behavior change** for the public HTTPS upload path: uploads to the deployed
  backend already use HTTPS and are unaffected.
