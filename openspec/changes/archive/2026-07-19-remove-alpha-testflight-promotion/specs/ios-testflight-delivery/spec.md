## ADDED Requirements

### Requirement: Tag refs fire only the release workflow

`ios.yml` triggers on a `push` that carries **no branch path filter**, so docs-only and backend-only merges to `main` produce iOS builds too and are delivered like any other. A **branch** path filter on the trigger SHALL NOT be added: `ios-build` and `ios-test` are **required** status checks (capability `ios-ci`), so a push that skips the workflow never posts them and merges would freeze. **Tag** refs, however, SHALL be excluded from the `push` triggers of both `build.yml` and `ios.yml`, so a `vX.Y` tag fires only the release workflow (capability `ios-appstore-release`) and not a redundant `main` build/delivery — excluding tags does not affect branch/PR pushes, so the required checks are still posted on every branch and no merge can freeze.

#### Scenario: The workflow trigger carries no branch path filter
- **WHEN** `ios.yml` is configured
- **THEN** its `push` trigger carries no branch path filter, so the required `ios-build` and `ios-test` checks are posted on every branch and no merge can freeze

#### Scenario: A tag fires only the release workflow
- **WHEN** a `vX.Y` tag is pushed
- **THEN** the `push` triggers of `build.yml` and `ios.yml` exclude it, so neither runs on the tag and no `main` build or delivery is produced for it

## MODIFIED Requirements

### Requirement: Delivery never blocks merges, and never fails silently

Delivery SHALL be decoupled from the merge gates **structurally**: it lives in a separate `main`-only job (`ios-deliver`) that posts **no required status check** (capability `branch-protection` requires `build`, `ios-build` and `ios-test`, and SHALL NOT require `ios-deliver` — a job that never runs on a pull-request branch would, if required, freeze every merge). Because it can block nothing, `ios-deliver` SHALL NOT use `continue-on-error`: a failed export or a failed App Store Connect upload SHALL conclude the job as **failure (red)**, so a broken delivery is visible rather than hidden inside an otherwise-green run.

This replaces the previous `continue-on-error` convention, under which a transient delivery failure left the run green and could pass unnoticed.

#### Scenario: A delivery flake is red but blocks nothing
- **WHEN** both gates are green on `main` but the export or the TestFlight upload fails
- **THEN** the `ios-deliver` job concludes as failure (red) and the failure is plainly visible, while no merge is blocked (the commit is already merged and `ios-deliver` is not a required check)

#### Scenario: A compile failure still fails the gate
- **WHEN** the signed archive fails to compile
- **THEN** the `ios-build` status check concludes as failure (red)

### Requirement: Monotonic build numbers from the CI run

The app's `CURRENT_PROJECT_VERSION` (CFBundleVersion) SHALL be injected at build time from `github.run_number`, and `MARKETING_VERSION` SHALL be a fixed pre-release fallback **committed in `Config.xcconfig`** (e.g. `0.1.0`) rather than in `project.pbxproj`. This fallback is the version every `main` build carries. The tag-driven App Store release channel (capability `ios-appstore-release`) **overrides** `MARKETING_VERSION` per release on the `xcodebuild` command line without editing committed source, so `main` is never bumped and never triggers a first-of-version Beta App Review. Because `github.run_number` is globally monotonic across all refs, each uploaded build — regardless of branch — SHALL carry a unique, strictly increasing build number for the marketing version, so TestFlight never rejects a duplicate and builds from different branches never collide.

#### Scenario: Two pushes produce two increasing build numbers
- **WHEN** two commits are pushed in sequence (to the same or different branches)
- **THEN** each produces a TestFlight build whose `CFBundleVersion` equals its `github.run_number`, and the second is strictly greater than the first

#### Scenario: The marketing-version fallback lives in Config.xcconfig
- **WHEN** a `main` build is produced with no version override
- **THEN** its `MARKETING_VERSION` resolves from `Config.xcconfig` (inherited by both the app and extension targets), and no `MARKETING_VERSION` is pinned in `project.pbxproj`

### Requirement: Distribution builds use the production APNs environment

Every CI **Release/distribution** archive SHALL be built with `APS_ENVIRONMENT=production` and `APNS_ENV=production`, so the shipped build's `aps-environment` entitlement is `production` and it can receive production APNs pushes. This holds for the `main` TestFlight build produced by `ios-build`/`ios-deliver` **and** for the tag release build (capability `ios-appstore-release`). Only **dev/sideload** builds — the `ios.yml` `workflow_dispatch` dev-IPA path (Debug, `upload_host` override) and the ssh-mac local build loop — SHALL keep the `Config.xcconfig` `development`/`sandbox` default. The environment is therefore tied to the build configuration: a Release archive is production, a Debug/dev archive is sandbox.

Previously neither value was overridden in CI, so every `main` TestFlight build shipped with the `Config.xcconfig` `development`/`sandbox` default and could not receive production pushes — contradicting the intent (all TestFlight/App Store builds are production; only dev-sideload is sandbox). Injecting the override in the shared archive path makes that intent true.

#### Scenario: A main TestFlight build is production-APNs
- **WHEN** `ios-build` produces the signed Release archive on `main`
- **THEN** it is built with `APS_ENVIRONMENT=production` and `APNS_ENV=production`, and the delivered build's `aps-environment` entitlement is `production`

#### Scenario: A dev-IPA build stays sandbox
- **WHEN** the `ios.yml` `workflow_dispatch` dev-IPA path builds a Debug archive with an `upload_host` override
- **THEN** it uses the `Config.xcconfig` `development`/`sandbox` default and is not overridden to production

## REMOVED Requirements

### Requirement: Every main build is promoted to the public alpha channel
**Reason**: The project is going App-Store-only; automatic public promotion is removed. The `ios-promote` job is deleted, so no `main` build is ever added to the `alpha` external group.
**Migration**: Distribute to real users via the dispatch-driven App Store release channel (`gh workflow run ios-release.yml`, capability `ios-appstore-release`). `ios-deliver` still uploads each `main` build to TestFlight, where it reaches the internal `development` group only.

### Requirement: The promoted build is identified by the CI run number
**Reason**: Promotion is removed, so there is no promoted build to locate.
**Migration**: None. Build numbering itself is unchanged (see *Monotonic build numbers from the CI run*).

### Requirement: No alpha tester is ever notified
**Reason**: Promotion is removed. The `autoNotifyEnabled=false` suppression lived in `ios-promote`; with the job gone there is no alpha group to notify. Internal `development`-group testers (effectively the developer) may again receive a per-build TestFlight notification — an accepted consequence.
**Migration**: None.

### Requirement: Every main build is promoted, unfiltered
**Reason**: Promotion is removed. The non-promotion half of this requirement — that the `ios.yml` trigger carries no branch path filter and that tag refs are excluded — is preserved in the new *Tag refs fire only the release workflow* requirement.
**Migration**: See *Tag refs fire only the release workflow*.

### Requirement: Promotion is idempotent
**Reason**: Promotion is removed; there is no promotion to re-run.
**Migration**: None.

### Requirement: The newest main build wins a review pile-up
**Reason**: Promotion is removed. `ios-deliver` only uploads; it does not submit to Beta App Review or expire queued submissions, so a review pile-up is no longer reachable on `main`.
**Migration**: None. Beta App Review sequencing for App Store releases is owned by capability `ios-appstore-release`.

### Requirement: A cancelled promotion is benign
**Reason**: Promotion is removed. `ios.yml` retains `concurrency: ios-${{ github.ref }}` with `cancel-in-progress: true` for the remaining jobs, but there is no promotion poll to cancel.
**Migration**: None.
