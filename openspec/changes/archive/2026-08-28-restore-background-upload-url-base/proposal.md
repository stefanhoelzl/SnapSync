## Why

`main` ships an iOS background-upload extension that **cannot register with the OS**, so every device
on the OS-driven tier uploads nothing at all. Commit `1d5d7a85` deleted `BackgroundUploadURLBase` from
both `Info.plist`s on the reasoning that deployment values belong in the generated `Deployment.plist`.
That reasoning is right for the Sentry pair and wrong for this key: `assetsd` reads
`BackgroundUploadURLBase` out of the **bundle's own `Info.plist`** when it validates the registration
insert, and without it `setUploadJobExtensionEnabled(true)` fails with a bare `PHPhotosErrorDomain -1`.

Measured on device 2026-08-28 (SE2, iOS 26.6, same archive, same re-sign, same full grant, five minutes
apart, one variable): key absent → enable fails `-1` with empty `userInfo`, disable fails `3201`; key
present → both succeed, read-back `true`. Daemon side, same change ids: absent →
`PHPerformChangesRequest failed to execute … Code=-1 … inserts: [AssetResourceUploadJobConfiguration: 1]`;
present → `success: Y error: (null)`. Xcode 26.6's own `Background Resource Upload.xctemplate` declares
the option `Required => true`. Last recorded upload success precedes the commit; Bugsink `SNAPSYNC-37`
holds 18 `extension enable FAILED: PHPhotosErrorDomain:-1` events, all iOS 26.6, on production TestFlight
builds 675/687.

## What Changes

- **Restore `BackgroundUploadURLBase` to both `Info.plist`s**, composed from build settings rather than
  carried as one URL-valued setting: `$(UPLOAD_SCHEME)://$(UPLOAD_HOST)/api/v1`. A single URL-valued
  build setting would reproduce the truncation bug `1d5d7a85` fixed — `//` opens a comment anywhere on an
  xcconfig line — and the per-site `$()` escape that commit deleted is not coming back.
- **`render_xcconfig` emits two new derived settings**: `UPLOAD_SCHEME` (the enum `upload_scheme()`
  already chooses, so the loopback/ATS rule stays derived) and `UPLOAD_HOST` (the bare `domain` literal).
  Neither can contain `//`, so the build-settings rendering keeps the property that makes it safe.
  Both are *derived renderings of an existing key*, like `ASSOCIATED_DOMAIN` and `APS_ENVIRONMENT`
  already are — no new inventory key, no new rendering.
- **The archive readback in `ios.yml` gains one comparison**: each bundle's `Info.plist`
  `BackgroundUploadURLBase` must be non-empty and **exactly equal** to that bundle's `Deployment.plist`
  `uploadBase`. Because the two carriers compose the same fact by different routes — Python into an
  escaping grammar, xcconfig into a commenting one — the comparison is a differential test that catches
  deletion, an unresolved substitution, a truncated `UPLOAD_HOST`, and a resource that reached only one
  bundle. It runs inside `ios-build`, which archives on every branch push and is a required merge gate.
- **Four documents that assert the deleted state are corrected** — the resolver's `domain` inventory
  doc, both `Info.plist` comments, and the `Config.xcconfig` header. They are why the key was deleted:
  the inventory said the subsystem validates against "the extension bundle's baked value", which reads
  exactly as "the plist we bake into the extension bundle". `app/ios/CLAUDE.md:194-197`, which documented
  the key as present throughout and was correct, is updated to the composed form.

Not in this change, deliberately: no source-level pin, no `//` assertion in the renderer, no `domain`
shape validator. The readback above catches the same failures earlier in the same push, from the built
product rather than from source.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `ios-photokit-upload`: "Background upload extension target" currently requires that **the extension
  `Info.plist` SHALL declare no deployment value of its own** and states that `Deployment.plist`'s
  `uploadBase` is "the compile-time edge host the system permits". The first clause forbids the fix; the
  second is measurably false — the system permits nothing on the basis of `Deployment.plist`. The
  requirement now states that the extension `Info.plist` SHALL carry `BackgroundUploadURLBase`, that
  `assetsd` validates the registration against it, and what the measurement does and does not establish.
- `ios-testflight-delivery`: "A delivering archive is verified to carry the resolved deployment"
  **enumerates** what the readback covers — upload base, APNs environment, crash-reporting environment,
  DSN, bundle identifier. The fifth assertion this change adds is not in that list, so trimming the check
  back to four would be spec-compliant while re-opening this regression. The requirement now also covers
  each bundle's `Info.plist` `BackgroundUploadURLBase`, and states why the comparison is an equality
  rather than a prefix test.
- `deployment-configuration`: "Device-facing baked values are rendered to a bundled property list"
  admits a third consumer of the build-settings rendering — an **`Info.plist` substitution** — alongside
  build settings and entitlement substitutions, and records that a value an **external OS reader**
  consumes must live in the file that reader opens, which no rendering the resolver owns can be.

## Impact

- `scripts/resolve-deployment.py` (`render_xcconfig`) and `scripts/resolve_deployment_test.py`.
- `iosApp/iosApp/Info.plist`, `iosApp/BackgroundUploadExtension/Info.plist`,
  `iosApp/Configuration/Config.xcconfig` (comment only).
- `.github/workflows/ios.yml` — the existing "Verify the archive baked the resolved deployment" step.
- `app/ios/CLAUDE.md`.
- No Kotlin changes, no module changes, no new dependency. Changelog label: `bug`.

**Known and deliberately out of scope**, each a separate change:

- `resolveUploadMechanism` takes no input for whether registration succeeded, so on ≥26.1 under a full
  grant it answers `PHOTOKIT` forever while `UploadArm.switchTo` has already stopped the app-driven tier
  and `OsDrivenUploadMechanism.start()` discards the `RegistrationOutcome`. That is what turned a config
  slip into "uploads nothing on either tier, silently". Feeding `RegistrationOutcome.Failed` back into
  resolution is the durable fix and touches `ios-photokit-upload` + `upload-lifecycle`.
- Two claims known wrong: `UploadExtensionRegistry.kt`'s KDoc calls `-1` "the simulator's refusal"
  (measured 2026-08-26, the day *after* the regression — the simulator substitute may be working around
  this same missing key), and `ios-photokit-upload` reads `3202` as "a stale record" where it is
  `PHPhotosErrorMultipleIdentifiersFound`, "a configuration already exists".
