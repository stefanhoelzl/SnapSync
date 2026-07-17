# Tasks — split-mixed-files

## 1. Beacon-counted splits (mixed 6 → 0)

- [x] 1.1 download: `HttpEventUnionSource` (+ private DTOs) → `HttpEventUnionSource.kt`
- [x] 1.2 event-creation-ui: `HttpEventCreationClient` → `HttpEventCreationClient.kt`
- [x] 1.3 join: `HttpEventDetailsSource` → `HttpEventDetailsSource.kt`
- [x] 1.4 membership: `HttpDeviceFilesSource` → `HttpDeviceFilesSource.kt`
- [x] 1.5 push: `KtorPushHttpClient` → `KtorPushHttpClient.kt`; `PushHttpClient` →
      `PushHttpClient.kt`; `PushTokenSource` → `PushTokenSource.kt`
- [x] 1.6 download-store: `SqlDelightDownloadStore` + `DownloadDatabase` factory →
      `SqlDelightDownloadStore.kt`

## 2. Plan-listed splits (clean step-3a moves)

- [x] 2.1 engine: `LedgerBackend` → `LedgerBackend.kt`; `LedgerWriter` → `LedgerWriter.kt`
- [x] 2.2 engine: vocabulary → `SyncModel.kt`; `UploadRequestProvider` → `UploadRequestProvider.kt`
- [x] 2.3 gallery: `RawAssetSource` → `RawAssetSource.kt`; `InMemoryRawAssetSource` →
      `InMemoryRawAssetSource.kt`
- [x] 2.4 gallery: `DeviceManifestStore` → `DeviceManifestStore.kt`; `DeviceManifestUploader` →
      `DeviceManifestUploader.kt`; `deviceManifestAssetsFromResources` → `DeviceManifestMapping.kt`

## 3. Gates

- [x] 3.1 `./gradlew build` green
- [x] 3.2 `./gradlew compileIosMainKotlinMetadata` green
- [x] 3.3 `./gradlew architectureDiagrams` re-run (no output changes)
- [x] 3.4 Beacon after: mixed 6 → 0, total 97 → 91, no other law moved
- [x] 3.5 CLAUDE.md reference grep for each split filename (only `EventDetailsSource` as a
      declaration name in the module list — still valid, no edit needed)
