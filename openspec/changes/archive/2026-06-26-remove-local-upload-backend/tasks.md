## 1. Forbid non-secure connections (ATS)

- [x] 1.1 Remove the `NSAppTransportSecurity` / `NSAllowsLocalNetworking` dict from `iosApp/iosApp/Info.plist` (and its explanatory comment)
- [x] 1.2 Remove the `NSAppTransportSecurity` / `NSAllowsLocalNetworking` dict from `iosApp/BackgroundUploadExtension/Info.plist` (and its explanatory comment)

## 2. Remove the local-backend path

- [x] 2.1 Delete `app/ios/src/iosMain/kotlin/app/snapsync/ios/LocalNetworkPriming.kt`
- [x] 2.2 Remove the `primeLocalNetwork(config, log)` call (and its comment) from `app/ios/src/iosMain/kotlin/app/snapsync/ios/SnapSyncRoot.kt`; confirm `config`/`log` remain used by their other consumers
- [x] 2.3 Delete `scripts/local-s3.sh`

## 3. Single-source the deployed host

- [x] 3.1 In `iosApp/Configuration/Config.xcconfig`, change the default `BACKGROUND_UPLOAD_URL_BASE` from `https://dummy.invalid` to the deployed host `https://snapsync.stefanhoelzl.deno.net`; update the surrounding comment to drop the "inert dummy / uploads nowhere" framing

## 4. CI: HTTPS-only override, fall through to xcconfig

- [x] 4.1 In `.github/workflows/ios.yml`, add a step (before the archive) that fails the run when `inputs.upload_host` is non-empty and does not begin with `https://`
- [x] 4.2 Change the archive step so it sets `BACKGROUND_UPLOAD_URL_BASE` **only** when `inputs.upload_host` is non-empty (empty/plain push omits the override and uses the `Config.xcconfig` default) — remove the inline `|| 'https://…'` literal
- [x] 4.3 Reword the `Select build configuration` comment (and `upload_host` input description) from the "local-backend upload loop" framing to "any (HTTPS) host override"

## 5. Docs

- [x] 5.1 In `CLAUDE.md`, delete the "Verify real uploads against a local S3 (the `real-s3-upload` loop)" section, leaving a one-line pointer that on-device uploads are now verified against the deployed backend's bunny storage zone (keep the usbmux / sideload sections intact)
- [x] 5.2 Scan `docs/design.md` for any local-host/ATS/priming rationale and reconcile if present (expected: little/none)

## 6. Verify

- [x] 6.1 `./gradlew build` — compiles all targets and runs JVM tests
- [x] 6.2 `./gradlew compileIosMainKotlinMetadata` — iOS source sets still compile after the priming removal
- [x] 6.3 Grep the repo for stragglers: `NSAllowsLocalNetworking`, `primeLocalNetwork`, `local-s3`, `dummy.invalid` — confirm only intended references remain
