## 1. The plist rendering

- [x] 1.1 Add `PLIST = "plist"` to the rendering constants and to `BAKED`; add it to the docstring's rendering map beside the four existing ones
- [x] 1.2 Move `sentryDsn` from `[JSON, XCCONFIG]` to `[JSON, PLIST]`; move `channel`'s and `domain`'s plist-bound derivations so `uploadBase`, `apnsEnv` and `sentryEnvironment` are emitted from `PLIST` (the `.xcconfig` keeps `ASSOCIATED_DOMAIN` and `APS_ENVIRONMENT`)
- [x] 1.3 Implement `render_plist(flat)` — an XML property list keyed by the inventory's own names (`uploadBase`, `apnsEnv`, `sentryEnvironment`, `sentryDsn`), with `sentryDsn` emitted only for a distributed channel (absence stays the off-switch) and the DSN's `//` surviving intact
- [x] 1.4 Add `iosApp/Configuration/Deployment.plist` to `emit()`'s target map, so it is written by the same atomic invocation as the other four
- [x] 1.5 In `render_xcconfig`, delete `SENTRY_DSN`, `SENTRY_ENVIRONMENT`, `APNS_ENV` and `BACKGROUND_UPLOAD_URL_BASE`, and **delete the `$()` comment guard** — no hand-rolled escape remains
- [x] 1.6 Document in the inventory which renderings interpolate raw and which escape, and record the rule that an environment-sourced value may not name a raw one (spec `deployment-configuration`)
- [x] 1.7 Add `iosApp/Configuration/Deployment.plist` to `.gitignore` beside `Deployment.xcconfig`, under the same generated-renderings comment

## 2. Resolver tests

- [x] 2.1 Replace `test_the_dsn_is_present_on_release` / `test_the_dsn_is_absent_off_release` with equivalents over the emitted **plist**, parsed with `plistlib`, asserting the DSN round-trips byte-identically including its `//`
- [x] 2.2 Assert the emitted plist carries exactly the four keys, under the inventory's names, for a distributed channel — and the same three minus `sentryDsn` for an undistributed one
- [x] 2.3 Delete `test_the_upload_base_carries_the_version_prefix`'s `$()` expectation and re-assert the upload base out of the plist
- [x] 2.4 Assert no value in the rendered `.xcconfig` contains `//`, and that every one of its values traces to a literal rather than an environment reference
- [x] 2.5 `python3 scripts/resolve_deployment_test.py` green

## 3. Xcode wiring

- [x] 3.1 Remove `BackgroundUploadURLBase`, `APNS_ENV`, `SENTRY_DSN` and `SENTRY_ENVIRONMENT` from `iosApp/iosApp/Info.plist`, moving their load-bearing comments onto the corresponding inventory entries
- [x] 3.2 Remove `BackgroundUploadURLBase`, `SENTRY_DSN` and `SENTRY_ENVIRONMENT` from `iosApp/BackgroundUploadExtension/Info.plist`, same treatment
- [x] 3.3 Update `Config.xcconfig`'s header comment: the generated fragment now carries build settings and entitlement inputs only, and the device-facing values live in the generated plist
- [x] 3.4 Add `Deployment.plist` as a file reference and to **Copy Bundle Resources** on the `iosApp` target and on the `BackgroundUploadExtension` target (not `SnapSyncForge`, which carries no deployment values)

## 4. The single reader

- [x] 4.1 Add the deployment-plist reader to `:adapter:ios:ext-safe` — reads the bundled plist once into a map, caches it, returns `null` for an absent or blank value (the `bundleValue` contract it replaces)
- [x] 4.2 Point `bakedUploadBase()` at `uploadBase`, preserving the `?: ""` collapse and its stated reason verbatim
- [x] 4.3 Point `SentryDiagnosticsReporter`'s `isConfigured`/`start` at `sentryDsn` and `sentryEnvironment`, keeping the `?: "development"` fallback and the process-wide idempotence
- [x] 4.4 Point `DeviceDiagnosticEnvironment`'s `reporterEnvironment` at `sentryEnvironment` (`CFBundleShortVersionString`/`CFBundleVersion` stay Info.plist reads — they are Apple's keys, not deployment values)
- [x] 4.5 Move `SnapSyncRoot`'s inline `APNS_ENV` read and its `?: "sandbox"` behind the reader, so `:app:ios` holds no absent-key defaulting decision
- [x] 4.6 Rewrite `UploadBaseTest`'s KDoc: "a test binary carries no such key, which makes this the one place that branch is reachable" is **false** after this change — the branch is now reachable in a shipped bundle whose resource failed to copy, which is what the archive assertion exists to prevent
- [x] 4.7 `./gradlew compileIosMainKotlinMetadata` green (the Linux-runnable iOS proxy)

## 5. CI verification

- [x] 5.1 Extend `ios.yml`'s "Verify the archive baked the resolved deployment" to read `Deployment.plist` out of `SnapSync.app` **and** `SnapSync.app/Extensions/BackgroundUploadExtension.appex`, failing loud when the file is absent from either
- [x] 5.2 Compare `uploadBase`, `apnsEnv` and `sentryEnvironment` against the resolver's own output in both bundles; keep the existing `CFBundleIdentifier` check
- [x] 5.3 Compare `sentryDsn` against `$SENTRY_DSN` on a release channel and assert it absent otherwise — **without echoing the value**
- [x] 5.4 Rewrite the step's comment: the justification is the four-way silent failure of a bundle missing its resource, not the three values it happened to check before

## 6. Docs

- [x] 6.1 CLAUDE.md — correct both crash-reporting paragraphs: the DSN is baked into the generated deployment plist, and the on-device path is a branch dispatch, not a DSN injected on the ssh-mac `xcodebuild` line
- [x] 6.2 `.claude/skills/bugsink/SKILL.md` — same correction to the "hand-injected DSN" note
- [x] 6.3 `./gradlew build` green (including `:test:architecture`'s `RuntimeIdentityTest` — the OS-held literals moved)

## 7. Device verification

- [ ] 7.1 `gh workflow run ios.yml --ref <branch>`; confirm the archive-verification step passed for both bundles in the run log
- [ ] 7.2 Install the delivered build, double-tap the "SnapSync" label, send a diagnostic dump
- [ ] 7.3 Confirm the dump in Bugsink via `/bugsink`, with `data.dist` equal to that run number — the first positive evidence since build 644
- [ ] 7.4 Confirm the extension reports too: force an upload cycle and check the run's `dsyms-<build>` artifact exists and that extension-process events carry the `process` tag for the `.appex` bundle id
