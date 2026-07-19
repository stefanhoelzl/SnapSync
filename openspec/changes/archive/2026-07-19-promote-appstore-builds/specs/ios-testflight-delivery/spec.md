## MODIFIED Requirements

### Requirement: Monotonic build numbers from the CI run

The app's `CURRENT_PROJECT_VERSION` (CFBundleVersion) SHALL be injected at build time from `github.run_number`. The `MARKETING_VERSION` SHALL be **computed by `ios.yml`** as `max(floor, latest vX.Y tag with its minor incremented by one)`, where the **floor** is a two-part `X.Y` value **committed in `Config.xcconfig`** (seeded `0.1`) rather than in `project.pbxproj`, and `max` is a numeric `(major, minor)` **tuple** comparison. The minor increment SHALL be an **integer bump** (`v0.9 → 0.10`), never a decimal addition, so a **major jump** (`→ 1.0`) is reachable **only** by committing a higher floor. With no `vX.Y` tag present, the computed version is the floor. The App Store release channel no longer overrides `MARKETING_VERSION` — it builds nothing and derives the store version from the promoted build (capability `ios-appstore-release`). Because `github.run_number` is globally monotonic across all refs, each uploaded build SHALL carry a unique, strictly increasing `CFBundleVersion`, so a marketing-version train never contains a duplicate build number.

#### Scenario: Two pushes produce two increasing build numbers
- **WHEN** two commits are pushed in sequence (to the same or different branches)
- **THEN** each produces a build whose `CFBundleVersion` equals its `github.run_number`, and the second is strictly greater than the first

#### Scenario: The marketing version is computed from the floor and the last tag
- **WHEN** a `main` build is produced and the latest release tag is `v0.2` with a committed floor of `0.1`
- **THEN** its `MARKETING_VERSION` is `max(0.1, 0.3)` = `0.3`, baked into both the app and extension targets, and no `MARKETING_VERSION` is pinned in `project.pbxproj`

#### Scenario: A major jump requires a floor bump, not a decimal carry
- **WHEN** the latest release tag is `v0.9`
- **THEN** the computed minor bump is `0.10` (integer), and reaching `1.0` requires committing a floor of `1.0`; a decimal `0.9 + 0.1 = 1.0` is never performed

### Requirement: Distribution builds use the production APNs environment

Every CI **Release/distribution** archive SHALL be built with `APS_ENVIRONMENT=production` and `APNS_ENV=production`, so the shipped build's `aps-environment` entitlement is `production` and it can receive production APNs pushes. This holds for the `main` TestFlight build produced by `ios-build`/`ios-deliver` — which is also the build the App Store release channel promotes (capability `ios-appstore-release`), so a promoted build is production-APNs by construction. Only **dev/sideload** builds — the `ios.yml` `workflow_dispatch` dev-IPA path (Debug, `upload_host` override) and the ssh-mac local build loop — SHALL keep the `Config.xcconfig` `development`/`sandbox` default. The environment is therefore tied to the build configuration: a Release archive is production, a Debug/dev archive is sandbox.

Previously neither value was overridden in CI, so every `main` TestFlight build shipped with the `Config.xcconfig` `development`/`sandbox` default and could not receive production pushes — contradicting the intent (all TestFlight/App Store builds are production; only dev-sideload is sandbox). Injecting the override in the shared archive path makes that intent true.

#### Scenario: A main TestFlight build is production-APNs
- **WHEN** `ios-build` produces the signed Release archive on `main`
- **THEN** it is built with `APS_ENVIRONMENT=production` and `APNS_ENV=production`, and the delivered build's `aps-environment` entitlement is `production`

#### Scenario: A promoted App Store build is production-APNs
- **WHEN** the App Store release channel promotes a `main` `ios-deliver` build
- **THEN** that build already carries the `production` `aps-environment` entitlement, because it was built as a Release archive on `main`

#### Scenario: A dev-IPA build stays sandbox
- **WHEN** the `ios.yml` `workflow_dispatch` dev-IPA path builds a Debug archive with an `upload_host` override
- **THEN** it uses the `Config.xcconfig` `development`/`sandbox` default and is not overridden to production
