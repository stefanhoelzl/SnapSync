# Tasks — delete-dead-weight

## 1. Catalog + QR tool

- [x] 1.1 Remove `zxing` and both `kotlincrypto` entries (versions + libraries) from
      `gradle/libs.versions.toml`
- [x] 1.2 Delete `capability/config/src/jvmMain/` and the `generateConfigQr` task + `jvmMain`
      dependency block from `capability/config/build.gradle.kts`

## 2. `:capability:device-id`

- [x] 2.1 Move `KeychainDeviceIdentity` to
      `domain/keychain/src/iosMain/kotlin/app/snapsync/keychain/KeychainDeviceIdentity.kt`
      (package `app.snapsync.keychain`, no interface supertype), keeping the pinned pair
      `service = "app.snapsync.deviceid", account = "deviceid"` byte-identical
- [x] 2.2 Delete `capability/device-id/` and its `settings.gradle.kts` include
- [x] 2.3 `DeviceAttestation`: `identity: DeviceIdentity` → `deviceId: () -> String` (+ test)
- [x] 2.4 `JoinEvent`: `deviceIdentity: DeviceIdentity` → `deviceId: () -> String` (+ test)
- [x] 2.5 Update the roots and harness/integration use sites; drop the module dep from the six
      consuming build files
- [x] 2.6 Verify `RuntimeIdentityTest` still finds the pair exactly once (build gate)

## 3. One details client

- [x] 3.1 Delete `EventMetadataSource.kt` + `HttpEventMetadataSourceTest.kt`
- [x] 3.2 `SnapSyncRoot`: hoist one `HttpEventDetailsSource`; the join gate and
      `fetchAndStoreName` (via `Found.name`) share it
- [x] 3.3 `EventDetailsSource` doc: it is the one `GET /events/:id` client

## 4. Interface ceremonies

- [x] 4.1 `LeaveNotifier` interface deleted; `HttpLeaveNotifier` stands alone (file renamed);
      `SnapSyncRoot` types the field concretely
- [x] 4.2 `LoggingPushReceiver` deleted from `PushReceiver.kt`
- [x] 4.3 `LedgerReader` deleted; `LedgerWriter` carries `entry()`; contract-test narrowing
      lines removed

## 5. Edges + beacon

- [x] 5.1 Remove `:capability:membership` from `domain/status/build.gradle.kts` (declared,
      never imported)
- [x] 5.2 Beacon: exclude `test/architecture/migration/` from the deletion-ledger scan (its
      pattern strings self-matched)

## 6. Ride-alongs + gates

- [x] 6.1 CLAUDE.md: drop the `:capability:device-id` row; keychain + membership rows updated
- [x] 6.2 `./gradlew build` green
- [x] 6.3 `./gradlew compileIosMainKotlinMetadata` green
- [x] 6.4 `./gradlew architectureDiagrams` regenerated, output left in tree
- [x] 6.5 Beacon before/after captured; Δ recorded (divergence note in PLAN.md if needed)
- [x] 6.6 `npx --yes @fission-ai/openspec@1.5.0 validate --specs --strict` green
