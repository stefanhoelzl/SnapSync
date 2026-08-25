## Why

Crash reporting has been dead in every TestFlight build since **644** (`a5ec5e53`, the first delivering
run after the deployment resolver landed). `//` opens a comment **anywhere** on an `.xcconfig` line, so
the renderer's `SENTRY_DSN = https://…@…/1` was read by Xcode as `SENTRY_DSN = https:`. That value is
non-blank, so `isConfigured` stayed **true** — the in-app bug-report dialog opened and silently lost every
dump — while `Sentry.init` received an unparseable DSN and never started. Builds 644, 648, 662 and 673
report nothing, from either process, and nothing was queued for later delivery.

Nothing caught it. The renderer's own unit test asserted the *rendered string*, which was correct; the
break was entirely downstream. The CI archive check verifies the upload base, the bundle id and the APNs
environment — not the DSN. And the failure is invisible by construction: **absence of crash reports is the
healthy value**, so no monitor on event volume could ever have signalled it.

The deeper cause is not a forgotten escape. The renderer emits into five grammars and models the escaping
rules of none; two are safe only because `json.dumps` builds them. Of the six values reaching the
`.xcconfig`, **exactly one is environment-sourced and written verbatim — the DSN — and it is the one that
broke.** Every other value is a literal in a committed JSON file that a human read in a pull request. Nobody
has ever seen the DSN's contents in the context of the file it lands in, and nobody ever will.

## What Changes

- **A new `plist` rendering.** `scripts/resolve-deployment.py` emits `iosApp/Configuration/Deployment.plist`
  alongside its four existing renderings, from the same single resolution, gitignored like the others.
- **Four keys move out of the `.xcconfig`**, renamed to the inventory's own key names:
  `BACKGROUND_UPLOAD_URL_BASE` → `uploadBase`, `APNS_ENV` → `apnsEnv`, `SENTRY_ENVIRONMENT` →
  `sentryEnvironment`, `SENTRY_DSN` → `sentryDsn`. Both `Info.plist`s lose their `$(…)` substitutions for
  these; the plist is copied into the app and the background-upload extension bundles.
- **The `//` hazard is eliminated structurally, not escaped.** What remains in the `.xcconfig` is only
  build settings and entitlement inputs — a product name, a bundle id, a team id, `applinks:<domain>`, and
  three derived enums — none environment-sourced. The hand-rolled `$()` comment guard on the upload base is
  **deleted**, not generalised.
- **One reader** in `:adapter:ios:ext-safe` replaces four scattered `objectForInfoDictionaryKey` calls,
  preserving each key's existing absence default exactly. This relocates `SnapSyncRoot`'s bare
  `?: "sandbox"` out of a shell gated to hold no decisions — the same reasoning that already seated
  `bakedUploadBase()` there.
- **The archive assertion covers all four values in BOTH bundles.** This is not defence in depth: the
  migration makes the absent-key branch reachable in a *shipped* build for the first time, and all four
  values fail together (see design). It is the only check that reads what the device reads.
- **BREAKING (developer workflow):** the hand-injected-DSN sideload path retires. An `xcodebuild`
  build-setting override cannot reach a generated resource file. On-device DSN work goes through
  `gh workflow run ios.yml --ref <branch>`.

## Capabilities

### New Capabilities

None. This changes the mechanism of existing contracts.

### Modified Capabilities

- `deployment-configuration`: gains a fifth rendering; the rendering set of four keys changes; the
  inventory documents which grammars interpolate raw.
- `ios-testflight-delivery`: the DSN's bake mechanism changes from an `.xcconfig` build setting to a
  bundled resource, and archive verification is extended from three values in one bundle to four values in
  both.
- `ios-photokit-upload`: the extension no longer declares `BackgroundUploadURLBase = $(…)` in its
  `Info.plist`; it reads `uploadBase` from the bundled `Deployment.plist`.
- `ios-app-shell`: the APNs environment is no longer "baked from `Config.xcconfig`", and the app's
  baked-value reads move behind one adapter-side reader.
- `push-registration`: the illustrative `Config.xcconfig`-baked `APNS_ENV` example is stale.

## Impact

**Code** — `scripts/resolve-deployment.py` (+ its test suite), `iosApp/Configuration/Config.xcconfig`,
`iosApp/iosApp/Info.plist`, `iosApp/BackgroundUploadExtension/Info.plist`, `iosApp/iosApp.xcodeproj`
(Copy Bundle Resources on two targets), `.gitignore`,
`adapter/ios/ext-safe/.../config/UploadBase.kt` (+ `UploadBaseTest`),
`adapter/ios/ext-safe/.../logging/SentryDiagnosticsReporter.kt`,
`adapter/ios/ext-safe/.../logging/DeviceDiagnosticEnvironment.kt`,
`app/ios/.../SnapSyncRoot.kt`, `.github/workflows/ios.yml`.

**Docs** — `CLAUDE.md` (the two crash-reporting paragraphs naming the sideload DSN path),
`.claude/skills/bugsink/SKILL.md` (the same path).

**Not affected** — the `SnapSyncForge` target (its `Info.plist` carries no deployment keys today and keeps
falling back to the same defaults), the JSON/site/properties/metadata renderings, and every backend
capability.

**Verification** — a branch `workflow_dispatch` of `ios.yml`, then a diagnostic dump from the installed
build, confirmed present in Bugsink with `data.dist` equal to that run number.
