## 1. Prerequisite

- [ ] 1.1 Wait for phase 8b (`transfer-contracts`) to merge `BackgroundTransferContract`; rebase this branch on
      it and read its clause list, state vocabulary and `TransferUnderTest` before adding to it

## 2. Mechanism: grant-keyed recordings and the new host

- [x] 2.1 `:test:contracts`: let a binding declare the grant it runs under (`Binding.grant`), and name recordings `<Contract>@<HOST>[.<GRANT>].rec` (unsuffixed when no grant is declared);
      provenance header carries the grant; `RecordingTest` covers both names
- [x] 2.2 `:test:architecture` `ContractCoverageTest`: read host and grant from the recording name; a
      grant-declaring replay binding counts only through its grant's recording; fail on a suffix no binding of
      that host declares; non-vacuity twins still hold; the recordings are declared inputs of the guard
      task (a recording-only change left it UP-TO-DATE before — found while testing this)
- [x] 2.3 Confirm `SecureStore@IOS_DEVICE_APP.rec` still resolves unchanged (no grant declared)

## 3. The registry contract

- [x] 3.1 `:adapter:ios:app-only`: an `internal` two-call seam in `PhotoKitExtensionRegistry` (`setEnabled`,
      `isEnabled`), behaviour unchanged
- [x] 3.2 `:test:contracts`: `UploadExtensionRegistryContract` — state vocabulary (record present / absent, per
      grant) and clauses: enable → registered and read back; disable of an absent record; the `3311` refusal in
      both directions under a partial grant; outcomes classified by `registrationOutcome`
- [ ] 3.3 Bindings: `Fake` over `SimulatorExtensionRecord`; `Live` device bindings (rig source set of
      app-only) for `GRANTED` and `LIMITED`; `Replay` bindings for both recordings in app-only `iosTest`

## 4. The upload-job seam and PhotoKit clauses

- [x] 4.1 `:adapter:ios:ext-safe`: the `internal` `UploadJobApi` seam in `IosPhotoKitUploadPlatform` — `fetch`
      returning job facts + handles, `create`/`retry`/`acknowledge` — with the adapter's logic otherwise unchanged
- [x] 4.2 `:test:contracts`: add `SINGLE_FREE_RETRY` to `BackgroundTransferState` and the PhotoKit-only clauses
      on it (offered for retry; retry re-points and completes; retry spent is handed up; every presented job is
      acknowledged); 8b's URLSession bindings declare it unreachable. Also: 8b's `seed` now records the
      destination each job is created with — the PhotoKit tier resolves a job's row only through it
- [ ] 4.3 `Fake` binding for `SimulatorUploadJobQueue` declaring exactly what the substitute reaches
- [x] 4.4 `Host.IOS_DEVICE_PHOTOKIT_EXT` lands with its first binding; the ext-safe rig source set: the extension's
      `Live` binding (usable = a library photo's resource; unusable = a non-resource payload; ledger = a fresh
      SQLDelight file per clause; `FixtureObjects` over the App Group's landed routes), and the recorder over
      `UploadJobApi` and the fixture reads, handle tokens and timestamps masked; registered in `extensionContracts()`
- [x] 4.5 `Replay` binding in ext-safe `iosTest` on `IOS_SIM_KEXE`, beside `IosKeychainReplayContractTest`

## 5. Running inside the extension

- [x] 5.1 Move `extensionRootEntries()` out of `UploadExtensionRoot.kt` into a production source directory of
      `:app:ios:extension`; the build script selects it, or a `:test:rig`-contributed directory, by
      `-Psnapsync.rig`; `:test:contracts` linked into the extension under the same property
- [x] 5.2 `ContractRunningEntries` (ext-safe rig source set): with a run request in the App Group, run the named
      contract instead of the cycle, write recording + outcome table after each stage, delete the request,
      return `COMPLETED`; otherwise delegate unchanged
- [x] 5.3 The shell gate scans the contributed directory and it holds no decision; the extension-safety gate
      and `ModuleSetTest` stay green
- [x] 5.4 `:test:rig`: `POST /contract/<name>?host=IOS_DEVICE_PHOTOKIT_EXT` — preconditions in order (full grant, no
      membership → refusal naming the reset verb; then re-registration), write the request, wait bounded for
      the result file, answer it verbatim or a distinct timeout status
- [x] 5.5 `:test:rig`: the receiver answers 8b's route grammar (`TransferFixture`) under `/api/v2`, and writes each
      landed route and its content type into the App Group
- [ ] 5.6 Confirm a production build contains none of 5.1–5.5 (no `app.snapsync.rig` or contract symbols in the
      extension binary)

## 6. Record on the device and land the findings

- [ ] 6.1 Rig build baked to the loopback base (`local` deployment at `127.0.0.1:18099`); install on the SE2
      under the device lock; unjoined, full grant
- [ ] 6.2 Record `BackgroundTransfer@IOS_DEVICE_PHOTOKIT_EXT.rec`; commit it unedited
- [ ] 6.3 Finding — destination guard: commit the failing clause outcome, then require an http(s) URL with a
      host in `IosPhotoKitUploadPlatform` (create and retry)
- [ ] 6.4 Finding — `resource` nil: read the retry-spent clause's recorded outcome; if nil is confirmed inside
      the extension, commit the failing outcome, then re-resolve the resource from the ledger key for
      re-creation; otherwise record that the app-process reading does not hold in the extension
- [ ] 6.5 Record `UploadExtensionRegistry@IOS_DEVICE_APP.GRANTED.rec`, switch the grant to limited in Settings,
      record `…LIMITED.rec`, switch back
- [ ] 6.6 End the session with a normal build installed and the lock released

## 7. `onTerminate`, docs and retirement

- [x] 7.1 `:domain:compose` `ExtensionCore.onTerminate`: log the end of a cycle at `Info` with the measured
      meaning; correct `ExtensionEntries.onTerminate`'s KDoc
- [x] 7.2 Delete `PhotoKitSmokeTest`
- [x] 7.3 `PhotoKitJobMapping.kt` / `IosPhotoKitUploadPlatform` KDoc: the fact conversion replay cannot cover,
      and the 50008 acknowledgement obligation, with their evidence
- [x] 7.4 Runbooks: `rig-channel` (the extension-host verb, the receiver, backoff after an overrun) and
      `snapsync-device` (recording the registry under two grants)
- [ ] 7.5 `./gradlew build`, `compileIosMainKotlinMetadata`, and `./gradlew architectureDiagrams` if the module
      graph changed; `npx --yes @fission-ai/openspec@1.5.0 validate --specs --strict`

## 8. Archive preparation

- [ ] 8.1 Add this change to the Purpose's decision-record lists of `port-contracts` and `ios-photokit-upload`
- [ ] 8.2 Delta accounting per module touched (`:adapter:ios:ext-safe`, `:adapter:ios:app-only`,
      `:app:ios:extension`, `:test:contracts`, `:test:rig`, `:domain:compose`, `:test:architecture`): name the
      capability delta, or record why none is needed (`:domain:compose`: a log line, no spec states its meaning)
