## 1. The contract module and its mechanism

- [x] 1.1 Create `:test:contracts` (jvm, iosSimulatorArm64, iosArm64; `commonMain` depends on `kotlin-test` and the port modules only); add it to `settings.gradle.kts` and to `module-architecture`'s contained group so `ModuleSetTest` passes
- [x] 1.2 Implement the mechanism in `commonMain`: `Clause` (id, required state, suspendable body), `Contract` (explicit clause list), `Binding` (host, kind `Fake`/`Live`/`Replay`, literal reachable-state set, `create(state)` → `Ready` | `Unreachable(reason)`), the `Host` enum (`JVM`, `IOS_SIM_KEXE`, `IOS_DEVICE_APP`), and the outcomes `Passed` / `Failed` / `NotRunHere` / `Diverged` / `NotWithin`
- [x] 1.3 Implement the runner: fresh instance per clause; declaration check (declared-but-unreachable and undeclared-but-ready are `Failed`); an early return never reads `Passed`; a CI entry point that runs every clause and fails once with the full outcome table on any `Failed`/`Diverged`
- [x] 1.4 Implement the recording format reader/writer in `commonMain` (provenance header, sorted `[CLAUSE_ID]` blocks of `call -> answer` lines, named volatile-key masking) with `commonTest` round-trip tests
- [x] 1.5 Unit-test the runner and outcomes in `commonTest` against toy contracts (runs on JVM and the simulator)
- [x] 1.6 Resolved by construction rather than measured: recordings are embedded at build time (`:adapter:ios:ext-safe:embedContractRecordings` turns each committed `.rec` into a generated Kotlin constant for the replay test source set; the `.rec` stays the only source), so the simulator test executable never reads a repository path

## 2. Convert the storage contracts

- [x] 2.1 Move `LedgerStoreContract` to `:test:contracts` as clause values, one clause per existing `@Test`, same names and assertions, single `Empty` state
- [x] 2.2 Same for `DownloadStoreContract`
- [x] 2.3 Repoint the SQLDelight (jvm) and native-driver (sim) tests in `:adapter:generic:app` to bindings over the moved contracts
- [x] 2.4 Move the fake bindings from `:test:world` `commonTest` to `:adapter:generic:fake` `commonTest`
- [x] 2.5 Remove the contracts and the `commonMain` `kotlin-test` dependency from `:test:world`; confirm nothing else in its `commonMain` used it
- [x] 2.6 Compare each of the four bindings' outcome tables with the pre-move test results — identical clause count, all `Passed`

## 3. The `SecureStore` contract, the fake and the live bindings

- [x] 3.1 Add `SecureStoreState` (`Inaccessible`, `Empty`, `Holding(value, protection)`) beside the contract in `:test:contracts`
- [x] 3.2 Write the `SecureStore` contract clauses (design D13): inaccessible read is `Unavailable` with a diagnostic, never `Absent`; inaccessible write refuses with `SecureStoreUnavailable` and leaves nothing; empty read is `Absent`; write-then-read is `Found(value, BACKGROUND_READABLE)`; write replaces; delete of absent is a no-op; delete removes; `migrateProtection` preserves the value and yields `BACKGROUND_READABLE`; `resolveOrMint` never mints when inaccessible and mints exactly once when empty — all inputs deterministic, addresses derived from the clause id
- [x] 3.3 Add the honest `InMemorySecureStore` to `:adapter:generic:fake` (`internal`, factory returning `SecureStore`, state only through the constructor — the module's honesty is the compiler's `internal`, not a text gate)
- [x] 3.4 Bind the fake (`JVM` and `IOS_SIM_KEXE`, kind `Fake`, all states) in `:adapter:generic:fake` `commonTest`
- [x] 3.5 Bind `IosKeychain` live on `IOS_SIM_KEXE` (reaches `Inaccessible`) in `:adapter:ios:ext-safe` `iosTest`
- [x] 3.6 Bind `AppGroupFileSecureStore` live on `IOS_SIM_KEXE` (reaches `Empty`, `Holding(_, BACKGROUND_READABLE)`) in `:adapter:ios:ext-safe` `iosSimulatorArm64Test`
- [x] 3.7 Run all three on the Mac; every clause `Passed` or `NotRunHere` with a stated reason
- [x] 3.8 (Found by the contract on its first run) `AppGroupFileSecureStore.write` threw a bare `IllegalStateException` when the container was unavailable or the write failed; it now throws `SecureStoreUnavailable`, which the composition roots catch and defer on. Simulator-target test equipment; no spec states its failure type, so no delta

## 4. The Keychain OS seam

- [x] 4.1 Introduce an `internal` seam in `:adapter:ios:ext-safe` over `SecItemAdd` / `SecItemCopyMatching` / `SecItemUpdate` / `SecItemDelete`, with the real implementation calling the platform; route `IosKeychain` through it with no behaviour change
- [x] 4.2 Confirm `IosKeychainTest`, `KeychainDeviceIdentityTest`, `KeychainAttestStoreTest` and `KeychainContainmentTest` pass unchanged

## 5. Recording on the device

- [x] 5.1 Add the rig-gated source set to `:adapter:ios:ext-safe` (compiled only under `-Psnapsync.rig=true`, depending on `:test:contracts` only) holding the recording seam and the `IOS_DEVICE_APP` `IosKeychain` binding (reaches `Empty`, `Holding(_, any)`; seeds legacy-protection items through the seam)
- [x] 5.2 Link `:test:contracts` into `:app:ios` under the same property; confirm a build without it contains none of either
- [x] 5.3 Add the `/contract/<name>` verb to `:test:rig`, taking the runner as a lambda wired in `:app:ios`'s rig hook; it answers with the recording text and the live outcome table
- [x] 5.4 Take the lease, build with `-Psnapsync.rig=true`, install on the entitled device, call the verb for `SecureStore`, and commit the returned text unedited as `test/contracts/recordings/SecureStore@IOS_DEVICE_APP.rec`; extend the masked volatile keys if a second run shows noise; release the lease

## 6. Replay in CI

- [x] 6.1 Add the replay binding in `:adapter:ios:ext-safe` `iosTest`: the current `IosKeychain` over a replaying seam reading the committed recording, reporting host `IOS_DEVICE_APP`; exact, ordered matching per clause; missing file or missing block is `Failed`
- [x] 6.2 Verify on the Mac: replay all `Passed`; a comment-only edit to `IosKeychain` still passes; changing the accessibility attribute `IosKeychain` writes reads `Diverged`; revert both

## 7. The contract-coverage gate

- [x] 7.1 Add the gate to `:test:architecture` (derived scope over contracts, bindings, recordings; literal-declaration parsing that fails loudly on an unreadable form; a non-vacuity twin per group; unused `Host` values fail)
- [x] 7.2 Prove it: a temporary clause no real binding reaches fails the gate naming it; a binding with a computed declaration fails naming the binding; revert both

## 8. The simulator app, measured once

- [x] 8.1 Measured once on a simulator: the ad-hoc-signed app answers `-34018` to every explicit-group query (iOS 26.2). No binding added; written into `port-contracts`' host matrix
- [x] 8.2 (Found by 8.1) A contract refuses to record in a process that is not its host — the simulator run produced a recording labelled `IOS_DEVICE_APP` — and the channel answers that refusal `409`

## 9. Documentation

- [x] 9.1 CLAUDE.md: add `:test:contracts` to the module list; update `:test:world` (no longer hosts the contracts) and `:adapter:ios:ext-safe` (the OS seam and the rig-gated source set)
- [x] 9.2 `rig-channel` skill: document the `/contract/<name>` verb and the record-then-commit procedure
- [x] 9.3 Rewrite `IosKeychainTest`'s KDoc where it says no environment can exercise the happy path: the entitled device recording, replayed every build, now does

## 10. Verification

- [ ] 10.1 `./gradlew build` green, including `:test:architecture`, `detektAppShell` and the `architectureDiagrams` freshness check (regenerate and commit if the module graph changed)
- [x] 10.2 All `iosSimulatorArm64Test`s green on the Mac
- [ ] 10.3 `npx --yes @fission-ai/openspec@1.5.0 validate --specs --strict` and `validate establish-port-contracts --strict` pass
- [ ] 10.4 Before archive: give `port-contracts` a real `## Purpose` (the archive mints a placeholder) and run the three archive gates in `openspec/config.yaml`
