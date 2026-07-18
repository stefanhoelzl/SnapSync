# Tasks — migrate-config-to-app-group-file

## 1. Pure decision layer (`:domain`, commonMain + commonTest)

- [x] 1.1 `model/ConfigFile.kt`: versioned envelope codec (`encodeConfigFile`/`decodeConfigFile`
      → `ConfigFileDecode.Valid/Unusable/Foreign`) + `isConfigFileAbsence` (the ⑥ classifier)
- [x] 1.2 `ports/ConfigPorts.kt`: `ConfigFileRead` (Content/Missing/Failed),
      `CONFIG_FILE_FOREIGN_STATUS`, `configReadViaFile(file, fallback, migrate)`
- [x] 1.3 `commonTest`: `ConfigFileTest` (round-trip, defaults, future-version → Foreign,
      non-envelope → Foreign, unusable payload, unknown-key tolerance, classifier table) and
      `ConfigFileReadTest` (every algorithm branch incl. missing+unreadable-Keychain → Unavailable)

## 2. Adapter + wiring (iosMain)

- [x] 2.1 `:adapter:ios:ext-safe` `FileBackedConfigStore`: atomic CUFUA write, NSError → 
      `ConfigFileRead` mapping, Keychain write-through (save file-first, clear Keychain-first),
      adapter-resident migration, StateFlow seed + `reload()`
- [x] 2.2 Wire it in all three roots (`SnapSyncRoot`, `UrlSessionUploadController`,
      `UploadExtensionRoot`) replacing `KeychainConfigStore`
- [x] 2.3 Update stale `KeychainConfigStore` references (extension-root doc, `EventConfig` doc,
      root + `app/ios` CLAUDE.md)

## 3. Guards

- [x] 3.1 Add `eventconfig.json` to `RuntimeIdentityTest`'s pinned inventory
- [x] 3.2 Deliberate-red the pin (re-value the production literal → guard fails naming it →
      restore → green)

## 4. Verification

- [x] 4.1 `./gradlew build` green (JVM tests incl. `:test:world` / `:test:integration`)
- [x] 4.2 `./gradlew compileIosMainKotlinMetadata` green (the adapter is iosMain)
- [x] 4.3 `./gradlew architectureDiagrams` re-run; beacon + detekt unchanged (22)
- [x] 4.4 Enumerate the device-only checks for Session C (report to the orchestrator)
