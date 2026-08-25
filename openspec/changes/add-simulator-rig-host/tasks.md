## 1. Signing surface

- [x] 1.1 Add `iosApp/Configuration/simulator.entitlements` declaring `com.apple.security.application-groups: [group.app.snapsync]` and nothing else, with a header comment stating why `keychain-access-groups` is absent (measured un-launchability) and that its absence is a decision, not an omission
- [x] 1.2 Extend `RuntimeIdentityTest`'s entitlements-file list so the App-Group id is pinned exactly-once in all three files, and scope the Keychain-access-group cross-check to the two signing files only
- [x] 1.3 Add the assertion that `simulator.entitlements` declares no `keychain-access-groups` key, so the un-launchability cannot be "fixed" by adding it
- [x] 1.4 Add the signing step under `scripts/` — appex signed with the shared plist first, then the `.app`; no `--deep`
- [x] 1.5 Run `./gradlew :test:architecture:test` and confirm both directions of the new pins fail when deliberately broken

## 2. Local backend reachable from a simulator

- [x] 2.1 Mint presigned download URLs from the configured origin instead of a hardcoded `https`. NB the design said this lived in `serve.ts`; it is actually `presignDownloadUrl` in `api/src/app.ts` (shipped code), so the scheme became a `Config` field the dev rig overrides exactly as it already overrides `s3Host` — see the D5 correction
- [x] 2.2 NOT NEEDED — verified rather than assumed: `fs-storage`'s guard is over `config.host` (the native Storage API, server→bunny, always HTTPS), not `config.s3Host` (presigned downloads, device→origin). The two are different constants and only the latter moves. Recorded because the design asserted otherwise
- [x] 2.3 Update `serve.ts`'s startup banner, which currently instructs the operator to `sed` the scheme by hand
- [x] 2.4 Run `deno task test` and `deno task check` in `api/` — 234 passed, and a new pin in `app.test.ts` fails if the scheme is hardcoded again (verified by reverting it)
- [x] 2.5 Correct `Config.xcconfig`'s "Must be HTTPS: default ATS (HTTPS-only) applies" comment to state that ATS exempts loopback, and that a simulator build overrides the host on the `xcodebuild` line

## 3. Identity on a target with no reachable Keychain

- [x] 3.1 Extract the identity-store binding out of `SnapSyncRoot`'s hardcoded construction into an `expect fun deviceIdentityStore(): SecureStore` in `:adapter:ios:ext-safe`'s `iosMain` — landed as a PAIR (`deviceIdPrimaryStore` + `deviceIdLegacyStore`) bound through `KeychainDeviceIdentity`'s defaults, so neither composition root changed at all
- [x] 3.2 Add the `iosArm64Main` actual binding the addressed-Keychain store, so the device target's compiled output is unchanged, and confirm `RuntimeIdentityTest`'s device-id (service, account) pin still matches exactly once
- [x] 3.3 Add the `iosSimulatorArm64Main` actual binding an App-Group-file `SecureStore` — honouring the port's three-state read, so `Absent` on first launch mints and persists and every later read is `Found`
- [x] 3.4 Verified: every test constructs its stores explicitly (`KeychainDeviceIdentityTest`, `IosKeychainTest`), so none picks up a target default; `compileTestKotlinIosSimulatorArm64` is green
- [x] 3.5 Verified: there is no shared `SecureStore` contract suite to extend — `SecureStoreResolveTest` (commonTest, JVM + simulator) covers the resolution ORDER and is target-independent. Added `AppGroupFileSecureStoreTest` in `iosSimulatorArm64Test` for the store itself, with the directory injected (an `xctest` host has no App-Group entitlement). It runs on CI's macos-26, not here
- [x] 3.6 Measured on the klibs, both directions: `iosArm64` carries ZERO `AppGroupFileSecureStore`/`NoSuchStore`; `iosSimulatorArm64` carries both. `IosKeychain` stays in both, correctly — the attest and album stores use it on either target; only the device-id binding differs
- [x] 3.7 `./gradlew build` and `./gradlew compileIosMainKotlinMetadata` pass. Both platform targets also compile from Linux (`compileKotlinIosArm64`, `compileKotlinIosSimulatorArm64`) — the actuals ARE checked here, which `compileIosMainKotlinMetadata` alone would not do

## 4. Channel addressability

- [x] 4.1 `RigServer` starts with `wait = false`, awaits `resolvedConnectors()` (the suspend point that answers "did it actually bind"), then publishes the bound port through a new `RigHooks.publishBoundPort` verb. The path decision lives in `:test:rig` (`rigPortFilePath`); the hook supplies only a branch-free write, so the shell gate still sees no decision
- [x] 4.2 `18099` and the device path are untouched; the bind-failure log now also states that NO port file was published, and names the simulator-specific cause (another instance holding the shared loopback would answer a curl aimed here). `detektAppShell` and `:test:architecture:test` pass

- [x] 4.3 FIXED A PRE-EXISTING BREAKAGE found by being the first thing to compile a rig build: `ReceiptDeadlines.URL_SESSION_EVENTS` was renamed `BACKGROUND_EVENTS` and the hook kept the old name. Nothing caught it because nothing in CI sets `-Psnapsync.rig=true` — the containment that keeps rig source out of production builds keeps it out of every build. Added that one Linux-runnable compile to `build.yml`

## 5. Bring one simulator up

- [x] 5.1 Built with `snapsync.rig=true` + the host override, signed, installed. **Run detached on the Mac** — a dropped cloudflared tunnel killed the first attempt mid-Gradle, and the surviving xcodebuild then collided with the retry on the build database
- [x] 5.2 `terminate → shutdown → boot → privacy grant all → launch` worked first time; no `mode=deferred` stall, no modal alert
- [x] 5.3 `xcrun simctl addmedia` with three 2400×2000 JPEGs; landed dated now, in the event window
- [x] 5.4 `/health` → `rig=up port=18101 · compositionMode=Live(tier=PHOTOKIT) · uploadBase=http://127.0.0.1:8080/api/v1`; `/state` → `ready.configResolved=true`. Identity resolved through the new binding: `device identity: id=6E2D0983-… via=minted`, then `via=read(protection=BACKGROUND_READABLE)` on relaunch. ZERO Error/Assert lines
- [x] 5.5 Event `7d429e1a-166a-40bf-97c8-db03dbf57d3b` created and auto-joined against the local backend; `.localstore` holds its metadata and a device manifest keyed by the simulator's own minted id

## 6. Measurements

- [x] 6.1 RUN, and the answer is that it is NOT ANSWERABLE on this host — recorded as such rather than as a result. Relaunch needs a background transfer that outlives the process, and 6.2 shows none can exist here. The attempt was made anyway (kick downloads, terminate after 2s, poll 90s): no relaunch, `handleBackgroundUrlSession` never fired. Confounded negative — the precondition was never established, so it is not evidence that the OS does not relaunch
- [x] 6.2 ANSWERED: the background session RUNS (3 assets planned, tasks created, `didCompleteWithError` fired) but every transfer fails instantly with `NSURLErrorDomain/-1` — on loopback AND on the LAN address — while the default session in the same process reaches the same server, and curl fetches the identical presigned URL with 200 and the exact byte count. Inert in outcome, alive in mechanism
- [x] 6.3 Supersession recorded in the design record on those exact terms — D5's closing limitation holds in OUTCOME, its premise ("cannot run background sessions") remains false. Archive untouched
- [x] 6.4 SETTLED, decisively against the hopeful reading: an unprovisioned `associated-domains` makes the app UN-LAUNCHABLE with the same `SBMainWorkspace` refusal as `keychain-access-groups`. `openurl` was accepted but no link entry point fired (0 occurrences). So the 2026-08-09 negative is explained rather than overturned — a simulator cannot carry the entitlement at all, and SNAPSYNC-3 gains no repro path here. Variant discarded; working signature restored and verified

## 7. Prove the gate

- [x] 7.1 Both instances reachable independently: `:18101 → rig=up port=18101`, `:18102 → rig=up port=18102`
- [x] 7.2 Distinct ids with no operator input — A `6E2D0983-…`, B `034A8007-…`, each minted in its own container; A's survived a relaunch as `via=read`
- [x] 7.3 Both `/state`s report the same `eventId`, and the backend holds TWO device manifests under that one event
- [x] 7.4 Deliberate collision on 18101: B published NO port file, logged the `Error` line naming the simulator cause, and `:18101` still answered — as **A** (`bootedAt` matches A's launch), which is exactly the confident-wrong-answer the file makes detectable

## 8. Documentation

- [x] 8.1 Write `.claude/skills/ios-simulator/SKILL.md`, leading with "no device lease", and covering build/sign/install, `SIMCTL_CHILD_` (argv vs environment), the permission sequence and the screenshot-first rule, `addmedia` seeding, erase-as-wipe, the reverse-forwarded backend, and reading the port file
- [x] 8.2 Added the `CLAUDE.md` Runbooks pointer; `RunbookSkillsTest` passes (pointer resolves, frontmatter `name` equals the directory). NB the launch-trigger index guard is unaffected — this change adds no `SNAPSYNC_*` literal
- [x] 8.3 Add the simulator port line to the `rig-channel` skill
- [x] 8.4 State the non-goals in the skill — no `LIMITED`, no APNs, no OS-driven tier — so a later change does not write a scenario for a host that cannot run it

## 8b. Rebase onto main and remove the competing plant

- [x] 8b.1 Rebased onto `main` after `triggers-into-channel` landed (109 files). Resolved 4 conflicts; took main's `build.yml` fix, which independently found the same rig-hook rot and fixes it better (both properties, metadata-only)
- [x] 8b.2 Removed main's plant: `SuppliedDeviceIdentity.kt`, `IdentityPlant.kt`, the `/device/identity` command, and the rig-channel doc line. `SnapSyncRoot` reverts to `KeychainDeviceIdentity(MINTING)`, which now resolves its store from the compilation target
- [x] 8b.3 Added the `device-identity` `REMOVED Requirements` delta, recording what both designs agree on as well as where they differ
- [x] 8b.4 KEPT `DeviceIdentityRetryTest` with its rationale rewritten: the no-memoized-failure property is load-bearing for the LOCKED-DEVICE case, which predates the supplier it was written for
- [x] 8b.5 Rewrote the `ios-simulator` runbook against the channel verbs — the `SNAPSYNC_CREATE_EVENT`/`_EVENT_LINK` examples named triggers that no longer exist. `SNAPSYNC_RIG_PORT` survives (hook-read) and is now the only variable
- [x] 8b.6 `./gradlew build` green; `architectureDiagrams` regenerated (one line in `di.md`) and committed

## 9. Close out

- [x] 9.1 `./gradlew build` green; `architectureDiagrams` regenerated and produced NO change (the new files are adapter/test sources the diagrams do not project)
- [x] 9.2 `openspec validate --specs --strict` → 62 passed, 0 failed
- [x] 9.3 CONFIRMED, no change needed: `UploadExtensionRoot` constructs `KeychainDeviceIdentity(READ_ONLY)` with DEFAULT stores, and `:app:ios:extension` declares both targets — so on `iosSimulatorArm64` it links the same App-Group file store the app writes, and both processes observe one id. NB `READ_ONLY` never mints, so the app must resolve first; on this host it always does, since the OS never invokes the extension there at all
- [x] 9.4 PR #207 opened with the `internal` label. Body states plainly that 6.1/6.2 are not taken, so a reviewer is not left to discover it
