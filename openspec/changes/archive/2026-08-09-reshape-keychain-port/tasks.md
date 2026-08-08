## 1. Reshape the port

- [x] 1.1 Rename `ports/Keychain.kt` → `ports/SecureStore.kt`: `Keychain` → `SecureStore`,
      `KeychainRead` → `SecureStoreRead`, `KeychainUnavailable` → `SecureStoreUnavailable`,
      `KeychainResolution` → `SecureStoreResolution`; `migrateAccessibility()` →
      `migrateProtection()`. `DeviceIdentityAbsent` keeps its name (already need-named)
- [x] 1.2 Replace `Unavailable(status: Int)` / `SecureStoreUnavailable(status: Int)` with an opaque
      `detail: String` the adapter formats; nothing in the core branches on it (design D3)
- [x] 1.3 Replace `Found.accessibility: String?` with `StoredProtection`
      (`BACKGROUND_READABLE` / `RESTRICTED` / `UNREPORTED`) and drop the
      `requiredAccessibility: String` parameter from `resolveOrMint`, `readExisting` and
      `needsMigration` (design D4)
- [x] 1.4 Re-ground `needsMigration`'s KDoc on the store's own contract — outlives the install,
      written once — instead of on "the Keychain survives app uninstall" (design D6)
- [x] 1.5 Rewrite the port's KDoc so no `errSec*`/`kSec*` reasoning remains in the core; keep the
      three-state read's rationale (the build-297 crash and the false leave) stated neutrally

## 2. Move the two translations into the iOS adapter

- [x] 2.1 `IosKeychain.read()` maps the `OSStatus` to `Unavailable("OSStatus <n>")` and the raw
      `kSecAttrAccessible` value to `StoredProtection`, comparing against the **unchanged**
      `ACCESSIBLE_AFTER_FIRST_UNLOCK` constant
- [x] 2.2 `IosKeychain` logs the observed class when it is not the required one, so the diagnostic
      `RESTRICTED` no longer carries stays on the device (design D4)
- [x] 2.3 Verify `baseQuery()`, `writtenAttributes()`, `itemAddress()`, `ACCESSIBLE_AFTER_FIRST_UNLOCK`,
      `SHARED_KEYCHAIN_ACCESS_GROUP` and `deviceIdItem`'s service/account are **untouched** (design D1)

## 3. Follow the renames through every consumer

- [x] 3.1 `KeychainDeviceIdentity` — drop the required-class argument, report `StoredProtection` in
      its once-per-process identity log line
- [x] 3.2 `KeychainAttestStore`, `IosAlbumMapStore` — port type and dropped argument only
- [x] 3.3 `feature/album/AlbumMapMigration` takes `SecureStoreRead` (design D5)
- [x] 3.4 `ports/AttestSeams` — its absence contract stops naming `KeychainUnavailable(status)`
- [x] 3.5 `compose/UploadCore` — log the adapter's detail string instead of a status code

## 4. Gates, specs and tests

- [x] 4.1 Delete `PlatformIdentifierTest`'s two deferred pins (`ports/Keychain.kt`,
      `feature/album/AlbumMapMigration.kt`) and update the map's KDoc to record how each `Keychain`
      pin was discharged
- [x] 4.2 Update `AbsenceIsNamedTest`'s failure message, which names `KeychainRead` as an exemplar
- [x] 4.3 `KeychainResolveTest` → `SecureStoreResolveTest`, driving the enum instead of class strings
- [x] 4.4 Update the three `iosTest` files for the renamed types — **without** editing any assertion
      on the item's address, its written attributes, or the shared access group (design D1)
- [x] 4.5 `./gradlew build` and `compileIosMainKotlinMetadata` green on Linux; `architectureDiagrams`
      reports no drift
- [x] 4.6 `./gradlew iosSimulatorArm64Test` green on a macOS runner — mandatory here, because every
      assertion that proves no stored value moved lives in an Apple-target source set
