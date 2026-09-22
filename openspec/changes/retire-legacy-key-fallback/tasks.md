## 1. Retire the fallback in the PhotoKit adapter

- [x] 1.1 `PhotoKitJobMapping.kt`: delete `legacyKeyOf` and the `FetchedJob.Emit.legacyKey` field (and its KDoc paragraph); `classifyPhotoKitJob` yields the path only
- [x] 1.2 `PhotoKitJobMapping.kt`: `jobRowOf(path, rowByDestination)` — `Found` by destination, else `Pruned` for a byte route, else `Unmappable`; rewrite its KDoc without the pre-identity branch
- [x] 1.3 `PhotoKitJobMapping.kt`: `isByteRoute` recognises only the v2 shape `/files/devices/<deviceId>/<assetId>/<role>` (D2); update its KDoc and the `retryJobMatching` KDoc ("the recorded destination path", no v1 fallback)
- [x] 1.4 `IosPhotoKitUploadPlatform.kt`: `rowFor` drops the `legacyRowExists` lookup; fix the class KDoc, `resolveKey`, and `retryJobFor` KDocs that describe the last-segment fallback

- [x] 1.5 Move `get(key)` from `TransferRecord` to `LedgerStore` (D5); fix the `TransferRecord` KDocs and the "two row reads" wording in `IosPhotoKitUploadPlatform.kt` and `TransportLedgerGateTest`

## 2. Tests

- [x] 2.1 `PhotoKitJobMappingTest`: remove `a pre-identity destination still carries its key in the last segment`, `a pre-identity retry job still resolves through the fallback`, `a pre-identity key is recovered only when its row exists`, and every `legacyKey` assertion; adapt `jobRowOf` call sites to the new signature
- [x] 2.2 `PhotoKitJobMappingTest`: add a test that a v1 destination is `Unmappable` (with no destination row), pinning D2
- [x] 2.3 `grep -rn "legacyKey\|legacyKeyOf\|last-segment\|pre-identity" adapter/ domain/ app/ test/` returns nothing about upload-job recovery

## 3. Verify

- [x] 3.1 `./gradlew compileIosMainKotlinMetadata` (the adapter is iosMain-only)
- [x] 3.2 `./gradlew build` green, including `:test:architecture` and the detekt tiers
- [x] 3.3 `./gradlew architectureDiagrams` shows no diff (or commit it)
- [ ] 3.4 The mapping tests are iosTest: run `iosSimulatorArm64Test` for `:adapter:ios:ext-safe` on a Mac (the `ssh-mac-build` skill) or let the macOS CI run on the PR, and record which — pending: compiled for iosSimulatorArm64 and iosArm64 on Linux; not run yet, left to the macOS CI on the PR
- [x] 3.5 `npx --yes @fission-ai/openspec@1.5.0 validate --specs --strict` and `validate retire-legacy-key-fallback --strict`
