# Tasks

## 1. Backend: a refusal names its reason

- [x] 1.1 Add `AttestationRejected(reason, detail)` with the closed reason set (`device-modified`,
  `device-unverifiable`, `app-not-genuine`). Throw it from every check in `api/src/android-attest.ts` and
  `api/src/attest.ts` per design D1, and keep each check's current message as `detail`. Verify: new
  `api/test/android-attest.test.ts` and `api/test/attest.test.ts` cases assert the reason for the boot,
  software-key, signing-cert, package, unpinned-root and `rpIdHash` failures (`cd api && deno task test`).
- [x] 1.2 In `api/src/routes/attest.ts`, answer v2's rejected mint with `401 "attestation rejected: <reason>"`.
  v1 keeps `"attestation rejected"`. An untyped throw reads as `device-unverifiable`. The log line keeps the
  detail. Verify: extend "v2 answers a rejected attestation 401, exactly as v1 does" to assert both bodies, and run
  `deno lint` (the complexity plugin).
- [x] 1.3 Document the v2 refusal body and its three reasons in the `/attest/token` row of `docs/architecture.md`.
  Verify: the row names the body and says v1 is unchanged.

## 2. Client domain: carry the reason to a verdict

- [x] 2.1 Add the `DeviceRefusal` enum to model/. Change `TokenOutcome.Refused` to carry it, and add
  `TokenOutcome.ProofFailed` for a local proof failure (D3). Have `tokenRefusal()` parse the `: <reason>` suffix,
  with an unknown or missing suffix read as `device-unverifiable`. Verify: `TokenRefusalTest` covers each reason,
  the bare v1 body, an unknown suffix, and the unchanged stale or not-attested cases.
- [x] 2.2 Give `DeviceAttestation` a `refusal: StateFlow<DeviceRefusal?>` with `attested`'s bracket: cleared on
  entry to `refresh()`, and set at its end from the mint's outcome. `ProofFailed` and `Unreachable` leave it
  `null`. Verify: `DeviceAttestationTest` asserts it is set on a service refusal, `null` on no answer and on a
  local proof failure, and cleared on a later success and at the start of the next refresh.
- [x] 2.3 In `CredentialedBackend.gated`, obtain a token through the `Credential` (`missing()`) before a
  user-tapped gated call — create, join — that holds none (D4). The extension's credential answers `null`, which keeps today's behaviour. Verify: an
  `AuthenticatedBackend` test shows a tokenless call attesting first and then succeeding, and that the extension's
  path makes no attestation call.
- [x] 2.4 Map a create's `401` to `CreateOutcome.Unverified` → `CreationFailureReason.UNVERIFIED`, and a join's
  `401` to `JoinResult.UNVERIFIED` → `JoinOutcome.Unverified` (D5). Verify: `CreateEventTest` and the `JoinEvent`
  tests cover the 401 case, and a 5xx still yields SERVER / EnrollFailed.

## 3. Mocks, harness and rig: reach every state through a lever

- [x] 3.1 Add `BackendOperator.refuseAttestation(reason?)`, persisted in `MockState` like `refuseNextCredential`.
  While it is set, `InMemoryBackend.mintToken` answers `401 "attestation rejected: <reason>"` and gated calls
  carrying a token from before the lever answer `401` (D9). Verify: the mock's commonTest binding and the
  `MockState` round-trip test.
- [x] 3.2 Add the rig verb `backend/refuse-attestation?reason=…|off` to `MockLevers` and `RigVocabulary`, and a
  reason selector to the world inspector. Verify: a `:test:control` test drives the verb on the JVM host, and
  `GET /device` lists it.

## 4. Presentation and UI: say why, on every screen

- [x] 4.1 Add `ScreenMessage.DEVICE_MODIFIED`, `DEVICE_UNVERIFIABLE` and `APP_NOT_GENUINE`, with English and German
  strings in `ui/screens` worded as the proposal's agreed copy, and map them in `Words.kt`. Verify: the
  `HardCodedUiText` gate passes, and `./gradlew nativeStrings` reports no diff.
- [x] 4.2 Pass `refusal` through `StatusSources` to the unjoined reduction. The line below Create shows the network
  notice, then a create failure, then the standing refusal, then the scan hint (D6). An `UNVERIFIED` create failure
  shows the refusal's message, or `CREATE_FAILED` when no refusal is known. Verify: `StatusContainerHostTest`
  covers each priority step and the fallback; a `StatusScreenTest` shows the refusal and an enabled Create.
- [x] 4.3 Add a join `Step.DeviceRefused(message)` with Retry and Cancel. An `Unverified` join with no refusal known
  falls back to `CommitFailed`. Verify: `StatusContainerHostTest` and `StatusScreenTest` cover both cases.
- [x] 4.4 Change `SyncHealth.Unattested` to carry `refusal`, and `AppSyncStatus.CannotVerifyDevice` to carry its
  cause with a title for each cause. The line stays non-tappable and keeps its "photos aren't lost" detail (D7).
  Verify: `UiStateSerializationTest` is updated, and a status-line test renders each cause and the cause-less line.
- [x] 4.5 Add the "Report this" action to the could-not-be-verified notice (front screen and join screen). It
  dispatches `ReportRefusal(message)`; `Overlays` holds the seed; the sheet opens prefilled; and with
  `PromptField.submitUnchanged` the seed can be sent as is, while rename keeps its rule (D8). Verify: a
  `StatusScreenTest` opens the sheet prefilled and sends it unedited, cancelling sends nothing, and the rename
  sheet still refuses an unchanged name.
- [x] 4.6 Regenerate the architecture diagrams if the flows or graph moved (`./gradlew architectureDiagrams`), and
  commit. Verify: `:tools:diagrams:test` passes.

## 5. Integration

- [x] 5.1 Write `:test:integration` scenarios through the rig's JVM host: for each reason, the front screen shows
  the refusal on launch; create shows it and leaves no event; join shows it with Retry; clearing the lever and
  tapping Create creates the event. Verify: the scenarios pass in `./gradlew build`.
- [x] 5.2 Review every new state in the world harness, headlessly through the `ui-harness` skill, in light and
  dark, English and German. Verify: a screenshot of each state shows no clipped text.
  (Done for English in light mode: the front screen for all three reasons, the seeded report sheet, the refused join
  step, the joined line. Dark mode and German are NOT reviewed — the headless driver has no theme or locale switch; accepted as is.)
- [x] 5.3 Run `./gradlew build` and `./gradlew compileIosMainKotlinMetadata`. Verify: both pass.

## 6. Verification facts in the report

- [x] 6.1 Tag every certificate check (`verifyChain`'s throws, and a revoked certificate) with the `certificate`
  detail, and have v2 answer `attestation rejected: device-unverifiable (certificate)`. Other failures carry no code,
  and v1 is unchanged (D10). Verify: `api/test/android-attest.test.ts` asserts the detail for an unpinned root, a
  broken chain, an expired RKP chain and a revoked certificate, and none for a foreign challenge; the route test pins
  the v2 body (`cd api && deno task test`, `deno lint`).
- [x] 6.2 Document the code in `docs/architecture.md`'s `/attest/token` row. Verify: the row names the `(certificate)`
  suffix and its scope.
- [x] 6.3 Parse the bracketed code in `tokenRefusal()`, and add `TokenOutcome.Refused.detail` (D10). Verify:
  `TokenRefusalTest` covers a body with the code, one without, and v1's bare body.
- [x] 6.4 Add the certificate summary to model/ and to `Proof`, and build it in `AndroidDeviceIntegrity.prove()`: for
  each certificate above the leaf its subject, issuer, validity, key algorithm and size, plus the root key's SHA-256.
  No serials and no leaf (D11). Verify: `AndroidDeviceIntegrityContractTest` (android device test, `android-build`)
  asserts a summary whose last entry's key matches the reported fingerprint, with names kept verbatim (not redacted).
- [x] 6.5 Keep the last refused mint's `RefusalFacts` in `DeviceAttestation`: cleared by a success, not by the start
  of a refresh (D12). Verify: `DeviceAttestationTest` asserts the facts after a refusal, their survival during a gated
  refresh, and their absence after a success.
- [x] 6.6 Carry the facts only in an offered report: a seeded sheet's `SendDiagnostics` sets the flag, and
  `CollectDiagnosticDump` then adds `attest_failure`, `attest_chain` and `attest_root_key_sha256` (D13). Verify: dump
  tests show the fields on a flagged report and their absence on a menu report, and `HostStatusActionsTest`'s
  "Report this" scenario sends the flag.
- [x] 6.7 Give `DeviceIntegrityMock`'s attestation a fixed chain summary, and let `backend/refuse-attestation` take
  `detail=certificate`, so an integration scenario sends an offered report from a refused phone and reads the fields in
  the reporter's dump. Verify: a `DeviceRefusalIntegrationTest` scenario passes, and a menu report in the same
  scenario carries none of them.
- [x] 6.8 Run `./gradlew build` and `./gradlew compileIosMainKotlinMetadata`. Verify: both pass.
