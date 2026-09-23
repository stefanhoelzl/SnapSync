## 1. Push seam narrowed (D6)

- [x] 1.1 Move `ApnsPushToken` from `feature/push` to `:domain` `model/`; replace `ports/PushHttpClient.kt` with `ports/PushTokenPublisher.kt` (`suspend fun publish(token: ApnsPushToken): Result<Unit>`, KDoc naming the need and its absence rule)
- [x] 1.2 Add `HttpPushTokenPublisher(client, host, deviceId: () -> String)` in `:adapter:generic:app` `push/` (takes `deviceConfigJson` and the URL); delete `KtorPushHttpClient`
- [x] 1.3 Rewire `PushRegistration` onto the port (drop `host`/`deviceId` params; keep absorb/retry/credential-change policy) and `SnapSyncRoot`'s construction; update `PushRegistrationTest`, `PushRegistrationIntegrationTest`, `CompositionSeamTest`
- [x] 1.4 Replace `KtorPushHttpClientTest` with `HttpPushTokenPublisherTest` (URL + body shape, non-2xx and transport failure → failed result)

## 2. App Attest seam (D1)

- [x] 2.1 Add `internal interface AppAttestApi` + `SystemAppAttestApi` in `:adapter:ios:ext-safe` `iosMain`; route `IosAttestKey` through it (hash stays above the seam), defaulted constructor param
- [x] 2.2 `./gradlew compileIosMainKotlinMetadata` and the ext-safe native compile green; `KeychainContainmentTest`/extension-safety gate unaffected

## 3. Contracts (D1, D2, D4, D6, D7)

- [x] 3.1 `AttestKeyContract` (`UNSUPPORTED`, `SUPPORTED`) with the D1 clauses; fixed challenge derived from clause id
- [x] 3.2 `AttestStoreContract` (`INACCESSIBLE`, `EMPTY`, `HOLDING`) with the D2 clauses
- [x] 3.3 Extend `AttestClientContract` with the three D4 refusal clauses; rewrite its header comment on what is and is not contracted
- [x] 3.4 `PushTokenPublisherContract` (`ENROLLED`, `REJECTED_CREDENTIAL`) with the D6 clauses
- [x] 3.5 `ProtectedStorageContract` (`UNLOCKED`) with its one clause

## 4. Honest doubles (D5)

- [x] 4.1 `InMemoryAttestKey`: refuse every operation when unsupported; refuse a `keyId` it never generated
- [x] 4.2 `InMemoryAttestClient`: mint only for `attestation:<keyId>:<challenge>` over its issued challenge; KDoc on what `mints`/`renews = true` model and that no host reaches the latter
- [x] 4.3 Bind the doubles (`AttestKey`, `AttestStore`, `AttestClient`, `ProtectedStorage`) in `:adapter:generic:fake` `commonTest`; run `:adapter:generic:fake`, `:test:world`, `:test:integration` tests — world outcomes unchanged

## 5. CI-reachable real bindings

- [x] 5.1 `IOS_SIM_KEXE` live bindings in `:adapter:ios:ext-safe` `iosTest`: `AttestKey` `UNSUPPORTED`, `AttestStore` `INACCESSIBLE` (production default construction for the unavailable state); trim `IosAttestKeyTest` to the adapter's own step-naming diagnostic
- [x] 5.2 Live-edge `AttestClient` refusal + `PushTokenPublisher` bindings in `LiveEdgeContractsTest` (`JVM`)
- [x] 5.3 Mini-edge `PushTokenPublisher` binding in `:test:world` `commonTest`; declare unreachable any state the mini-edge does not model
- [ ] 5.4 `ProtectedStorage` live binding on `IOS_SIM_APP` registered in `SimulatorAppContracts.kt` via `simulatorAppContract`; confirm the `ios-contracts` job lists and passes it

## 6. Recording on the device (D1, D2, D3)

- [x] 6.1 App Attest tape in `:adapter:ios:ext-safe` `rig`: recording/replaying `AppAttestApi`, masking `keyId` in answers and requests and attestation/assertion bytes in answers
- [x] 6.2 Device bindings + `deviceContracts()` entries for `AttestKey` and `AttestStore` (refusing on a simulator, as `recordSecureStore` does)
- [x] 6.3 Replay tests `IosAttestKeyReplayContractTest` / `KeychainAttestStoreReplayContractTest` in `iosTest`
- [x] 6.4 Device session (load `snapsync-device` + `rig-channel`, hold the lease): install the rig build, `POST /contract/AttestKey` and `/contract/AttestStore`, commit both `.rec` files unedited; check no unmasked credential bytes; settle design's open question and adjust the unknown-key assert clause if needed
- [ ] 6.5 Replays green on the simulator build

## 7. Documentation of exclusions

- [x] 7.1 `IosProtectedStorage` KDoc: the locked state has no host, citing `port-contracts` "Hosts are a closed set of what changes reachable states"
- [x] 7.2 `MetricKitProcessMetricSource` KDoc: no contract, no double, OS-timed one-shot delivery — pointing at the evidence already there
- [x] 7.3 `HttpAttestClient` KDoc: successful mint/renew uncontracted (challenge TTL + chain-at-now), verified by `api/test/attest.test.ts`

## 8. Gates and wrap-up

- [x] 8.1 `ContractCoverageTest` green (every new clause reached by a real host or recording)
- [x] 8.2 `./gradlew build` green; `./gradlew architectureDiagrams` and commit `architecture/`
- [x] 8.3 Update CLAUDE.md's module map lines naming `KtorPushHttpClient`/`PushHttpClient` and `:test:contracts`' contract list
- [x] 8.4 `npx --yes @fission-ai/openspec@1.5.0 validate --specs --strict` and `validate device-credential-contracts --strict`
