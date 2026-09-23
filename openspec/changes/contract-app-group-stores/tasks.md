## 1. Measure before writing (design Risks; iosSimulatorArm64Test, macOS runner via `ssh-mac-build`)

- [x] 1.1 In the kexe, measure which unreadable entry at a file path — a mode-000 file, or a directory —
  makes `NSData.dataWithContentsOfFile` fail with an error that is not not-found, and record the domain and
  code. If neither does, drop `FILE_UNREADABLE` from D5/D6 and say so in `design.md` before continuing.
- [x] 1.2 In the kexe, measure whether a per-test `NSUserDefaults(suiteName:)` round-trips a value and can
  be removed with `removePersistentDomainForName`. If it cannot, record that in `design.md` and move the
  album-map live binding to the PhotoKit phase rather than covering it with a fake.

## 2. Injectable container locations (behaviour-preserving)

- [x] 2.1 `FileBackedConfigStore`: add `containerPath: String?`, defaulting to the App-Group lookup, and
  derive the file path from it. Both shells stay unchanged.
- [x] 2.2 `IosStagedBytes`: add `container: () -> String?`, defaulting to the lookup and still resolved
  lazily inside `stagingRoot()`. The shell stays unchanged.
- [x] 2.3 `./gradlew build` and `compileIosMainKotlinMetadata` are green, and `RuntimeIdentityTest`'s
  pinned literals are unmoved.

## 3. Config contract and the `clear()` fix

- [x] 3.1 `:test:contracts` commonMain: `ConfigStoreState` (`INACCESSIBLE`, `ABSENT`, `JOINED`, `FOREIGN`,
  `UNUSABLE`, `FILE_UNREADABLE`), the subject holder over the three ports, and `ConfigStoreContract` with the
  D6 clauses. Seed configs and their file text derive from the clause id.
- [x] 3.2 `:adapter:generic:fake`: `InMemoryConfigStore(persisted, readable)` implementing all three ports,
  plus port-typed factories (`inMemoryConfigSource`/`inMemoryConfigStore`/`inMemoryConfigReader`) over the
  caller's cells. The class stays `internal`: that, not a `FakeHonestyTest` (which no longer exists), is the
  honesty rule.
- [x] 3.3 Fake binding in `:adapter:generic:fake` commonTest: `currentHost`, `Fake`, reaching `INACCESSIBLE`,
  `ABSENT` and `JOINED`.
- [x] 3.4 Live binding in `:adapter:ios:ext-safe` iosTest on `IOS_SIM_KEXE`:
  - readable states use a fresh temporary directory with the seed written as the adapter would write it;
  - `INACCESSIBLE` uses the default container argument;
  - `FILE_UNREADABLE` uses the entry measured in 1.1.
- [x] 3.5 Make `clear()` raise
  when the container path is `null`, leaving `config` unchanged. A missing file stays success.
- [x] 3.6 Update the `FileBackedConfigStore` and `isConfigFileAbsence` KDoc:
  - the unresolvable-container `clear` now fails;
  - the locked-since-boot belief is not a contract clause (design D3).

## 4. The other four contracts (one commit each: contract, fake binding, live binding)

- [x] 4.1 `DeviceManifestStoreContract` (`UNAVAILABLE`, `EMPTY`, `HOLDING`):
  - the fake binding is in `:adapter:generic:fake`;
  - the live binding is `IosDeviceManifestStore` in ext-safe iosTest, where `UNAVAILABLE` uses the default
    container.
- [x] 4.2 `StagedBytesContract` (`UNAVAILABLE`, `EMPTY`, `STAGED`), with paths derived from `stagingRoot()`
  and the clause id:
  - the fake binding is in `:adapter:generic:fake`;
  - add `implementation(project(":test:contracts"))` to `:adapter:ios:app-only`'s iosTest;
  - the live binding is `IosStagedBytes` there.
- [x] 4.3 `DeviceLogSourceContract` (`NO_LOG`, `EMPTY_LOG`, `HOLDING`, `ROLLED_ONLY`):
  - fix `InMemoryDeviceLogSource` to answer `null` for an empty log;
  - the live binding is `IosDeviceLogSource` over injected paths.
  - finding: the live binding failed on CI because the reader dropped the first line of a log that fit its
    budget. Fix it to drop a partial line only on a mid-file read, and correct the test that pinned the drop.
- [x] 4.4 `AlbumMapStoreContract` (`EMPTY`, `HOLDING`, `CORRUPT`):
  - the fake binding is in `:adapter:generic:fake`;
  - the live binding is `IosAlbumMapStore` over a per-clause suite, or is moved out per 1.2.
- [x] 4.5 `ContractCoverageTest` is green: every new clause has a `Live` binding on `IOS_SIM_KEXE` declaring
  its state.

## 5. The world on the fake

- [x] 5.1 `:test:world`: replace the inline `configSource`/`configStore`/`configReader` objects with one
  `InMemoryConfigStore` over the existing config cell and a new readable cell. `membershipUnreadable`
  becomes a property over that readable cell, and the `-25308` Keychain status is gone.
- [x] 5.2 `:test:integration`, `:app:desktop` (the world inspector's lever) and `CycleEntryGateIntegrationTest`
  are green. Any test that saved while unreadable is fixed at the test.

## 6. Docs, diagrams, gates

- [x] 6.1 CLAUDE.md module map:
  - drop `IosDiscoveryStore` / the "cursor store" from `:adapter:ios:ext-safe`, and the "discovery" store
    from `:adapter:generic:fake`;
  - add `InMemoryConfigStore` to the fake's list;
  - name the new contracts in `:test:contracts`' line.
- [x] 6.2 `./gradlew architectureDiagrams`, and commit anything it changes.
- [ ] 6.3 `./gradlew build` is green on Linux. The `iosSimulatorArm64Test` suites of `:adapter:ios:ext-safe`,
  `:adapter:ios:app-only` and `:adapter:generic:fake` are green on the macOS runner, and each contract's outcome
  table has no `Failed`.
- [x] 6.4 `npx --yes @fission-ai/openspec@1.5.0 validate --specs --strict` and `… validate
  contract-app-group-stores --strict` both pass.
