## 1. Re-read config each cycle (`:capability:config` + `:app:ios:photokit-extension`)

- [x] 1.1 Add `KeychainConfigStore.reload()` (`iosMain`): re-read the Keychain (`readConfig()`) into
      the `config` `StateFlow`. Documents why a cross-process reader must refresh.
- [x] 1.2 `UploadExtensionRoot.process()`: call `configSource.reload()` before reading
      `configSource.config.value`, so each cycle uses the currently-joined event.
- [x] 1.3 Verify with `./gradlew compileIosMainKotlinMetadata` (iOS source sets compile on Linux).

## 2. Verify on device

- [x] 2.1 Build the dev IPA (CI #154), install, and exercise a switch: join event A → confirm uploads
      land in A → join event B without reinstalling → confirm uploads move to B (A frozen). Confirmed:
      A frozen at 9, B received the full library (the fresh extension re-read the Keychain → B).
