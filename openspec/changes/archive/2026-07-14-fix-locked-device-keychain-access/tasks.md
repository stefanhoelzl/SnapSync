## 1. `:domain:keychain` — the single Keychain adapter

- [x] 1.1 Create the `:domain:keychain` module (jvm + ios targets, no platform deps in `commonMain`), register it in `settings.gradle.kts`, and mirror the `:domain:logging` build setup.
- [x] 1.2 `commonMain`: define the read outcome (`Found(value)` / `Absent` / `Unavailable(status)`) and the typed `KeychainUnavailable(status)` error.
- [x] 1.3 `commonMain`: implement the pure decision logic — `resolve(read, write, generate)` mints **only** on `Absent`, propagates `Unavailable`, and never writes on `Unavailable`; plus the migrate branch (`needsMigration(currentClass, targetClass)`).
- [x] 1.4 `commonTest`: cover every branch — `Found` returns verbatim; `Absent` mints + writes once; `Unavailable` neither mints nor writes and surfaces the typed error; migration triggers only on a mismatched class. (Runs on JVM **and** `iosSimulatorArm64`.)
- [x] 1.5 `iosMain`: implement the `SecItem` adapter — one query returning `kSecReturnData` + `kSecReturnAttributes`; map `errSecItemNotFound` → `Absent` and every other non-success status → `Unavailable(status)`; write with `kSecAttrAccessibleAfterFirstUnlock`.
- [x] 1.6 `iosMain`: implement in-place migration — on a successful read whose accessibility class differs from the target, `SecItemUpdate` the class **preserving the value**; no delete-and-re-add, no re-mint.
- [x] 1.7 `iosTest` against the real `SecItem*` API. NOTE: a Kotlin/Native test binary is not an app bundle, so `securityd` refuses it Keychain access (`errSecNotAvailable`, -25291) — the happy path (round-trip, real migration) is **not testable at any level**, only on a device. What IS asserted, and is the bug itself: a refusal reads as `Unavailable` never `Absent`, and mints/writes nothing; plus `writtenAttributes()` (the single source both write paths consume) carries `AfterFirstUnlock`.

## 2. Device id (`:capability:device-id`)

- [x] 2.1 Rewrite `KeychainDeviceIdentity` over `:domain:keychain`; delete its local `baseQuery`/`readValue`/`writeValue`/`deleteItem` copies.
- [x] 2.2 Ensure `readValue`'s old "any failure → null" behaviour is gone: a non-`Absent` failure surfaces `KeychainUnavailable` and never mints. Keep `DeviceIdentity.deviceId(): String` unchanged (per design Decision 5).
- [x] 2.3 Update the module's KDoc to state the accessibility class, the restorable-by-design rationale, and the never-mint-on-error invariant.

## 3. Config (`:capability:config`)

- [x] 3.1 Rewrite `KeychainConfigStore`'s Keychain access over `:domain:keychain`; delete its local `SecItem` copies.
- [x] 3.2 Give the config seam a three-state read (*joined* / *definitely absent* / *unreadable*) and seed the `StateFlow` accordingly; a **decode** failure stays "no config" (existing behaviour, capability `photo-date-cutoff`) and is distinct from an **unreadable** item.
- [x] 3.3 `commonTest`: an unreadable read is not reported as absent; a decode failure still reads as no config.

## 4. The extension must not false-leave (`:app:ios:photokit-extension`, `:capability:upload`)

- [x] 4.1 In `UploadExtensionRoot.process()`, branch on the three-state config: on **unreadable**, skip the cycle entirely — no `reconciler.reconcile(...)`, no marker clear, no cursor reset, no jobs — and return a clean `COMPLETED`.
- [x] 4.2 Catch `KeychainUnavailable` from the `deviceId` resolution in `process()` and skip the cycle the same way (symmetry with 4.1); never let it propagate out of `process()`.
- [x] 4.3 Add a test in `:capability:upload` (`commonTest`) proving an unreadable config skips the cycle and leaves the `joinedEventId` marker intact, while a definitely-absent config still clears it.

## 5. Album map leaves the Keychain (`:capability:album`)

- [x] 5.1 Reimplement `IosAlbumMapStore` over the App-Group `NSUserDefaults` suite (mirror `IosDiscoveryStore`, `suiteName = LEDGER_APP_GROUP`); keep the `AlbumMapStore` seam unchanged.
- [x] 5.2 One-shot migration on first read: if the App-Group map is empty and the legacy Keychain item exists, copy it over, then `SecItemDelete` the legacy item. Idempotent and safe in either process.
- [x] 5.3 Tests: the store round-trips via the App Group; the legacy Keychain item is migrated once and then deleted; a second read does not re-migrate.
- [x] 5.4 Confirm no `event-album` requirement changes (its spec pins "a shared store that survives leave", never the Keychain) — no spec delta.

## 6. Protected-data deferral + diagnostics (`:app:ios`)

- [x] 6.1 Add a tested seam for protected-data availability (state + a "became available" signal) in a `domain`/`capability` module — `:app:ios` is wiring-only, so no decision logic may live there.
- [x] 6.2 Implement the iOS adapter over `UIApplication.isProtectedDataAvailable` + `UIApplicationProtectedDataDidBecomeAvailable`, and wire it in `SnapSyncRoot`: when unavailable, defer background work and resume on the notification.
- [x] 6.3 Wrap the app's background entry points (`runDownloadBackstop`, `onSilentPush`, `handleBackgroundUrlSession`) and the extension's `process()` so each logs protected-data availability and every Keychain status code, carrying its `[<entryPoint>]` prefix (capability `diagnostic-logging`).
- [x] 6.4 Tests for the deferral logic in its tested module (deferred work runs exactly once when protected data becomes available; nothing is minted, written, or cleared while deferred).

## 7. Architecture guards (`:test:architecture`)

- [x] 7.1 Create the test-only `:test:architecture` module (jvm target), add the Konsist dependency to `gradle/libs.versions.toml`, and register the module in `settings.gradle.kts`.
- [x] 7.2 Konsist guard: no `SecItem*` / `kSecClass*` / `kSecAttr*` outside `:domain:keychain`, catching **fully-qualified** references as well as imports (use the `text` escape hatch — `ForbiddenImport`-style import checking alone cannot see an FQN call).
- [x] 7.3 Fail-loud assertion: the scanned scope is non-empty and covers the expected `iosMain` files, so a Konsist parser regression (it embeds a Kotlin 2.0 PSI against this repo's Kotlin 2.4) fails the build instead of passing vacuously.
- [x] 7.4 Entitlements guard: a plain JVM test asserting neither `iosApp.entitlements` nor `BackgroundUploadExtension.entitlements` sets `com.apple.developer.default-data-protection` to `NSFileProtectionComplete`, with a comment explaining that doing so would make every App-Group file unreadable while locked.
- [x] 7.5 Confirm the guards run under `./gradlew build` (the canonical check) and fail the build on violation.

## 8. Verify

- [x] 8.1 `./gradlew build` — all JVM + simulator-shared tests, plus the new architecture guards.
- [x] 8.2 `./gradlew compileIosMainKotlinMetadata` — the Linux-runnable proxy for the iOS source sets (both roots + the new module compile).
- [x] 8.3 Run the `iosSimulatorArm64` tests (macOS-only: ssh-mac). All `commonTest` suites pass on the simulator; `IosKeychainTest` rewritten after the real-Keychain premise proved false (see 1.7).
- [x] 8.4 `npx --yes @fission-ai/openspec@1.5.0 validate --specs --strict`.
- [ ] 8.5 After shipping: pull `debug.log` from a device after a real background wake and confirm protected data was available and the Keychain status was `errSecSuccess` on a locked device (design Decision 10 — this is the only evidence the end-to-end locked wake works; no test can produce it).
