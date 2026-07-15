## ADDED Requirements

### Requirement: Distribution builds use the production APNs environment

Every CI **Release/distribution** archive SHALL be built with `APS_ENVIRONMENT=production` and `APNS_ENV=production`, so the shipped build's `aps-environment` entitlement is `production` and it can receive production APNs pushes. This holds for the `main`/alpha TestFlight build produced by `ios-build`/`ios-deliver` **and** for the tag release build (capability `ios-appstore-release`). Only **dev/sideload** builds — the `ios.yml` `workflow_dispatch` dev-IPA path (Debug, `upload_host` override) and the ssh-mac local build loop — SHALL keep the `Config.xcconfig` `development`/`sandbox` default. The environment is therefore tied to the build configuration: a Release archive is production, a Debug/dev archive is sandbox.

Previously neither value was overridden in CI, so every `main`/alpha TestFlight build shipped with the `Config.xcconfig` `development`/`sandbox` default and could not receive production pushes — contradicting the intent (all TestFlight/App Store builds are production; only dev-sideload is sandbox). Injecting the override in the shared archive path makes that intent true.

#### Scenario: A main TestFlight build is production-APNs
- **WHEN** `ios-build` produces the signed Release archive on `main`
- **THEN** it is built with `APS_ENVIRONMENT=production` and `APNS_ENV=production`, and the delivered build's `aps-environment` entitlement is `production`

#### Scenario: A dev-IPA build stays sandbox
- **WHEN** the `ios.yml` `workflow_dispatch` dev-IPA path builds a Debug archive with an `upload_host` override
- **THEN** it uses the `Config.xcconfig` `development`/`sandbox` default and is not overridden to production

## MODIFIED Requirements

### Requirement: Monotonic build numbers from the CI run

The app's `CURRENT_PROJECT_VERSION` (CFBundleVersion) SHALL be injected at build time from `github.run_number`, and `MARKETING_VERSION` SHALL be a fixed pre-release fallback **committed in `Config.xcconfig`** (e.g. `0.1.0`) rather than in `project.pbxproj`. This fallback is the version every `main`/alpha build carries. The tag-driven App Store release channel (capability `ios-appstore-release`) **overrides** `MARKETING_VERSION` per release on the `xcodebuild` command line without editing committed source, so `main` is never bumped and never triggers a first-of-version Beta App Review. Because `github.run_number` is globally monotonic across all refs, each uploaded build — regardless of branch — SHALL carry a unique, strictly increasing build number for the marketing version, so TestFlight never rejects a duplicate and builds from different branches never collide.

#### Scenario: Two pushes produce two increasing build numbers
- **WHEN** two commits are pushed in sequence (to the same or different branches)
- **THEN** each produces a TestFlight build whose `CFBundleVersion` equals its `github.run_number`, and the second is strictly greater than the first

#### Scenario: The marketing-version fallback lives in Config.xcconfig
- **WHEN** a `main`/alpha build is produced with no version override
- **THEN** its `MARKETING_VERSION` resolves from `Config.xcconfig` (inherited by both the app and extension targets), and no `MARKETING_VERSION` is pinned in `project.pbxproj`

### Requirement: Every main build is promoted, unfiltered

`ios-promote` SHALL promote **every** build produced by `main`, and SHALL NOT filter on changed paths, on commit type, or on whether the compiled binary actually differs from the previously promoted one. `ios.yml` triggers on a `push` that carries **no branch path filter**, so docs-only and backend-only merges produce iOS builds too; those builds SHALL be promoted like any other, even though they are binary-identical to their predecessor apart from the build number.

This is deliberate. Every filter considered — a path allowlist, a binary hash comparison, a conventional-commit-type gate — fails toward *"a real fix silently never reaches testers"*, which is the worst outcome an alpha channel can have. Promoting everything fails only toward tester-visible noise, which is merely annoying. The noisy option is therefore the correct one.

A **branch** path filter on the workflow **trigger** SHALL NOT be used to achieve this: `ios-build` and `ios-test` are **required** status checks, so a push that skips the workflow never posts them and merges would freeze. **Tag** refs, however, SHALL be excluded from the `push` triggers of both `build.yml` and `ios.yml`, so a `vX.Y` tag fires only the release workflow (capability `ios-appstore-release`) and not a redundant alpha build/promotion — excluding tags does not affect branch/PR pushes, so the required checks are still posted on every branch and no merge can freeze.

#### Scenario: A docs-only merge is still promoted
- **WHEN** a docs-only or backend-only commit is merged to `main`, producing a build binary-identical to the last one apart from its build number
- **THEN** that build is promoted to the `alpha` group like any other

#### Scenario: The workflow trigger carries no branch path filter
- **WHEN** `ios.yml` is configured
- **THEN** its `push` trigger carries no branch path filter, so the required `ios-build` and `ios-test` checks are posted on every branch and no merge can freeze

#### Scenario: A tag fires only the release workflow
- **WHEN** a `vX.Y` tag is pushed
- **THEN** the `push` triggers of `build.yml` and `ios.yml` exclude it, so neither runs on the tag and no alpha build or promotion is produced for it
