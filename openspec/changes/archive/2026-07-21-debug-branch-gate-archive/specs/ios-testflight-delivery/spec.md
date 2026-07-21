## MODIFIED Requirements

### Requirement: Distribution builds use the production APNs environment

Every CI **Release/distribution** archive SHALL be built with `APS_ENVIRONMENT=production` and `APNS_ENV=production`, so the shipped build's `aps-environment` entitlement is `production` and it can receive production APNs pushes. This holds for the `main` TestFlight build produced by `ios-build`/`ios-deliver` — which is also the build the App Store release channel promotes (capability `ios-appstore-release`), so a promoted build is production-APNs by construction. Only builds that are **never distributed** SHALL keep the `Config.xcconfig` `development`/`sandbox` default: the **branch-gate Debug archives** (pushes to refs other than `main` — discarded gate artifacts, capability `ios-ci`), the `ios.yml` `workflow_dispatch` **dev-IPA** path (Debug, `upload_host` override), and the **ssh-mac** local build loop. The environment is therefore tied to the build configuration: a Release archive is production, a Debug/dev archive is sandbox.

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

#### Scenario: A branch-gate archive stays sandbox
- **WHEN** a push to a ref other than `main` produces the Debug gate archive (capability `ios-ci`)
- **THEN** it uses the `Config.xcconfig` `development`/`sandbox` default — immaterial to the discarded archive, and consistent with the configuration-tied rule

### Requirement: Release archives bake the crash-reporting DSN; dev builds never receive it

Every CI **Release/distribution** archive SHALL be built with the crash-reporting DSN injected from the
`SENTRY_DSN` repository secret through the same shared archive seam that injects `APS_ENVIRONMENT`
(capability `crash-reporting` consumes it from the bundle at runtime). The value SHALL reach **both**
targets — the app and the background-upload extension. **Undistributed** builds — the **branch-gate
Debug archives** (capability `ios-ci`), the `ios.yml` `workflow_dispatch` dev-IPA path, and the ssh-mac
local build loop — SHALL NOT receive the DSN, leaving it absent so the SDK never starts there: like the
APNs environment, the reporting channel is tied to the build configuration, with no separate enable flag
that could disagree with it.

#### Scenario: A main TestFlight build carries the DSN

- **WHEN** `ios-build` produces the signed Release archive on `main`
- **THEN** both the app's and the extension's bundle configuration carry the DSN from the
  `SENTRY_DSN` secret

#### Scenario: A dev-IPA build carries no DSN

- **WHEN** the `ios.yml` `workflow_dispatch` dev-IPA path or the ssh-mac loop builds a Debug archive
- **THEN** no DSN is injected, the bundle value is absent, and crash reporting never initializes in
  that build

#### Scenario: A branch-gate archive carries no DSN

- **WHEN** a push to a ref other than `main` produces the Debug gate archive
- **THEN** no DSN is injected, so a discarded gate build can never report into the production
  crash-reporting project
