## 1. Route migration — `/os` gains root groups (D9, D15)

- [x] 1.1 Restructure `RigHooks.triggers`/`excludedTriggers` into groups keyed by composition root, and
      route `/os/<root>/<member>` in `RigServer`. Existing app entry points move to `/os/app/<member>`;
      leaves stay equal to the `@PlatformEntry` member names.
- [x] 1.2 Rewrite `RigControlChannelTest.derivedEntryPoints()` to derive **per root group** — each root
      file's marked members compared against that group's wired-plus-excluded set. Replace the scoping
      KDoc (*"the rig runs in the app process, so the extension root's entry points are not reachable
      from it"*) with the true reason; it is falsified by task 3, not merely outdated.
- [x] 1.3 Add the non-vacuity twin for the new shape: assert every group is non-empty and that a name
      shared by two roots is accounted for twice, so a regression to a flat set fails.
- [x] 1.4 Update the `rig-channel` and `ios-simulator` skills' `/os` examples; confirm
      `RunbookSkillsTest` still passes.

## 2. Shipped seams — the port bundle and the adjudication (D7, D12)

- [x] 2.1 Add the target-bound `uploadJobQueue(log, discovery, ledger): BackgroundTransfer` seam in
      `:adapter:ios:ext-safe` and point `UploadExtensionRoot` at it instead of constructing
      `IosPhotoKitUploadPlatform` directly. (Supersedes the `uploadPorts(transfer:)` shape, which
      contradicted D3 — the root builds its own transfer, so the rig cannot pass one and still invoke the
      root verbatim. See the rewritten D7.)
- [x] 2.2 Move `drainTerminals`'s per-job adjudication (`SUCCEEDED → UPLOADED` else `FAILED`; emit for
      re-creation only when retry-spent **and** the resource is live) out of `IosPhotoKitUploadPlatform`
      into a pure function in `PhotoKitJobMapping.kt`; call it from the adapter.
- [x] 2.3 Cover the extracted function in `PhotoKitJobMappingTest` — every `PhotoKitJobState`, and both
      the live-resource and absent-resource cases.
- [x] 2.4 Verify `./gradlew build` and `compileIosMainKotlinMetadata` are green before anything depends
      on these seams.

## 3. Invoking the real extension root from the channel (D3, D10, D11)

- [x] 3.1 Add `implementation(project(":app:ios:extension"))` to `:app:ios`'s `iosMain` **only** under
      `-Psnapsync.rig=true`, beside the existing rig-gated dependency.
- [x] 3.2 Wire `/os/photokit-ext/processRawValue` and `/os/photokit-ext/onTerminate` in the rig hook,
      calling `UploadExtensionRoot` members directly. The hook holds no decision; every default,
      rendering and lane choice lives in `:test:rig`.
- [x] 3.3 In `:test:rig`, snapshot `Logger.config.logWriterList` and restore it around the invocation, so
      the extension root's `init` does not redirect the app's log and silence `/logs`.
- [x] 3.4 Invoke on a dedicated **serial** thread of the rig's own — never `mainLane`. State in the KDoc
      why the "Swift calls entry points on main" reasoning does not transfer (the extension process has no
      main lane).
- [x] 3.5 Refuse the invocation with the resolved mechanism named unless `resolveUploadMechanism` yields
      `photokit`, so a second `LedgerWriter` over one App-Group ledger is unrepresentable.
- [x] 3.6 Dropped: the spike already recorded the pre-substitution crash at `→ platform.createJob`
      (`PROBE-FINDINGS.md`), and the substitution lands in the same pass, so the observation is no longer
      separately available.

## 4. The substituted upload-job subsystem (D2, D4, D5, D6, D14)

- [x] 4.1 Add the per-target seam for the job queue: `iosArm64` binds `IosPhotoKitUploadPlatform`;
      `iosSimulatorArm64` binds the substitute. Nothing outside the substitute's own target compiles it.
- [x] 4.2 Implement the substitute as a **stateless** `BackgroundTransfer`: `fetchRetryJobs` and
      `drainTerminals` read the invocation's input, `createJob`/`retryJob` append to its output,
      `discoverResources` delegates to the real `IosDiscovery`. It holds no memory between invocations.
- [x] 4.3 Route the ledger writes through the function extracted in 2.2, so the rig and the device
      adjudicate identically by construction.
- [x] 4.4 Implement key → `PHAssetResource` recovery (`<assetId>-<role>.<ext>`, `_`→`/`, then
      `fetchAssetsWithLocalIdentifiers` → `assetResourcesForAsset`), so `drainTerminals` can return
      retry-spent failures whose resource is live. Without it the cycle takes the legal
      "resource no longer live" branch and the re-create path degrades **silently**.
- [x] 4.5 Define the request/response shape over the pinned platform vocabulary — action
      (`retry`|`acknowledge`), `PhotoKitJobState`, `UploadError` — with the caller stating the retry
      disposition. Created jobs are returned with their destination URL and headers **verbatim**.
- [x] 4.6 Add the `jobLimit` lever so `CreateResult.LIMIT_EXCEEDED` and the un-advanced cursor are
      drivable.

## 5. Performing an upload for real (D5)

- [x] 5.1 Add `POST /device/upload-jobs/perform`: recover the resource by key (4.4), stage bytes with
      `PHAssetResourceManager.writeDataForAssetResource(resource, toFile:)`, and PUT to the destination
      and headers the cycle composed. Use a plain `NSURLSession` — **not** `IosUrlSessionUploadPlatform`,
      which is the other tier's mechanism.
- [x] 5.2 Support a forced failure that skips the PUT and names an `UploadError`, so the retry chain is
      deterministic without breaking the backend.
- [x] 5.3 Return the transfer's real outcome (`status`, `expected`, `received`) so a caller asserts on the
      transfer rather than on a progress read-model.

## 6. Registration behind a port (D8, D13)

- [x] 6.1 Add the registration port to `:domain` `ports/`, named for the need, returning
      `RegistrationOutcome`; add the read-back.
- [x] 6.2 Implement the PhotoKit adapter in `:adapter:ios:app-only` — the sole caller of
      `setUploadJobExtensionEnabled` / `isUploadJobExtensionEnabled` — and bind it per target, with the
      simulator target's binding answered by the rig.
- [x] 6.3 Rewrite `PhotoKitUploadProducer` to take the port; it names no `PHPhotoLibrary` API. Confirm
      `detektAppShell` still passes at complexity 2.
- [x] 6.4 **Delete** the unconditional `background-upload extension re-registered (…)` line. Not a
      conditional — the shell gate forbids the branch, and `Applied(enabling = true)` plus
      `clearRequestedOffMain` already report both halves.
- [x] 6.5 Give the rig the registration state it needs: a boolean plus a forced-failure code, so `3202`
      (stale record), `3201` (clean device) and `3311` (partial grant, both directions) are all drivable.
- [x] 6.6 Route the cursor reset through `DiscoveryStore.clearToken()` (it was open-coding a second raw
      `NSUserDefaults` write against the key that port already owns), leaving the class platform-free; move
      it to `:domain` `feature/upload` beside `UploadArm`/`RelinquishThenRun`/`BackgroundUploadPump` and
      rename it `OsDrivenUploadMechanism` — named for the need, as the platform-free core requires. Then
      test the ritual and the repair on JVM + simulator: disable-before-enable, the clear completing
      **before** the re-enable, a stale record replaced, a refused enable claiming nothing, `stop()`
      repairing both halves, and `deregister()` repairing neither.

## 7. Guards (specs `architecture-guards`)

- [x] 7.1 Add the upload-job subsystem binding gate: `iosArm64` actuals name `setUploadJobExtensionEnabled`
      and `creationRequestForJobWithDestination`; `iosSimulatorArm64` actuals name neither. Fail on a
      missing actual, and on a third iOS target.
- [x] 7.2 Confirm `ProducerExclusivityTest`, `KeychainContainmentTest`, the extension-safety gate,
      `RuntimeIdentityTest`, `ModuleSetTest`, `FakeHonestyTest` and `LawsDigestTest` are unaffected.
- [x] 7.3 `./gradlew architectureDiagrams` and commit if anything moved.

## 8. Verify on the host (the point of the change)

- [x] 8.1 Build with `-Psnapsync.rig=true` against `snapsync.deployment=local`, `sim-sign`, install, grant
      `photos=YES` with `applesimutils`, seed three ≥3 MP photos, create and join an event **upload-only**.
      Confirm the mechanism resolves `photokit` with **no pin**.
- [x] 8.2 Drove a full happy path: cycle 1 → 3 jobs created; `perform` each → **status 201, succeeded**
      against the destinations the edge-URL builder composed; cycle 2 → `result: completed`; ledger
      `{completed: 3, pending: 0}`; three objects plus their type sidecars under
      `api/.localstore/objects/files/devices/<id>/`. Album placement and notify were NOT observed and are
      not claimed: this run joined with `saveToAlbum=false`, so no album was involved.
- [x] 8.3 Drive the retry chain: fail a job once (surfaces via `fetchRetryJobs`), fail it again (recorded
      `FAILED` and handed back for re-creation), then succeed the re-created job.
- [x] 8.4 Drive `PROCESSING`: leave a created job unperformed and confirm the cycle reports processing with
      the cursor un-advanced; then drive `LIMIT_EXCEEDED` via `jobLimit` and confirm the same.
- [x] 8.5 Drive the states no device produces — `CANCELLED`, `REGISTERED`, and the `else → PENDING` arm the
      mapping's KDoc calls *"a guess"* — and record what the cycle actually does with each.
- [x] 8.6 Drive the registration ritual: a stale record refused with `3202` repaired by the leading
      disable, and a refused enable producing **no** success claim in `debug.log`.
- [x] 8.7 Assert **ZERO Error/Assert lines** across the whole run — the invariant D8 exists to preserve.

## 9. Documentation

- [x] 9.1 Rewrite the `ios-simulator` skill's "No OS-driven PhotoKit upload tier" section: the pin is no
      longer required for `photokit` scenarios; state what the substituted subsystem covers and what stays
      device-only.
- [x] 9.2 Record the two host divergences in the skill's "this is the host, not a fault" voice: the rig's
      transfers use a **default** session and die with the process, and the registration record is the
      rig's, not the OS's.
- [x] 9.3 Add the new verbs to `rig-channel`: the two `/os/photokit-ext/…` triggers, the job
      request/response shape, `/device/upload-jobs/perform`, and the `photokit`-only refusal.
- [x] 9.4 Record the measured facts from `PROBE-FINDINGS.md` where a reader will meet them — the `-1`
      registration refusal and the fatal `createJob` — including that the nil-configuration link is an
      inference and the expiry trigger is the next iOS major.

## 10. Ship

- [x] 10.1 `./gradlew build` green, `openspec validate --specs --strict` green (60/60), diagrams current.
- [ ] 10.2 PR with the `internal` changelog label; `/ship`. **NOT DONE** — the change was synced and
      archived ahead of shipping, at the operator's direction. Nothing here is committed yet.
