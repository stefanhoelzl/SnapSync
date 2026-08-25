## MODIFIED Requirements

### Requirement: Distribution builds use the production APNs environment

Every CI **Release/distribution** archive SHALL be built with `APS_ENVIRONMENT=production` and `APNS_ENV=production`, so the shipped build's `aps-environment` entitlement is `production` and it can receive production APNs pushes. This holds for the `main` TestFlight build produced by `ios-build`/`ios-deliver` — which is also the build the App Store release channel promotes (capability `ios-appstore-release`), so a promoted build is production-APNs by construction. Only builds that are **never distributed** SHALL carry the `development`/`sandbox` values: the **branch-gate Debug archives** (pushes to refs other than `main` — discarded gate artifacts, capability `ios-ci`) and the **ssh-mac** local build loop. The environment is therefore tied to the build configuration: a Release archive is production, a Debug/dev archive is sandbox.

Both values SHALL be **derived from a single build-configuration discriminator** by the deployment renderer (capability `deployment-configuration`), not stated independently and required to agree. They are two faces of one question — is this build distributed? — and stating them separately admits a combination in which they disagree, which today is prevented only by a comment saying they must not. Deriving them makes that combination unrepresentable. The same discriminator SHALL drive the crash-reporting environment, for the same reason.

The `ios.yml` `workflow_dispatch` dev-IPA path is **no longer among the undistributed builds**, because that trigger is removed (capability `ios-ci`): it archived a Debug build that `ios-build` then discarded, so it never produced an installable IPA. The ssh-mac loop is now the only dev-build path, and it is sandbox by the same configuration-tied rule.

Previously neither value was overridden in CI, so every `main` TestFlight build shipped with the `development`/`sandbox` default and could not receive production pushes — contradicting the intent (all TestFlight/App Store builds are production; only dev-sideload is sandbox). Tying both to the discriminator makes that intent true by construction.

#### Scenario: A main TestFlight build is production-APNs
- **WHEN** `ios-build` produces the signed Release archive on `main`
- **THEN** it is built with `APS_ENVIRONMENT=production` and `APNS_ENV=production`, and the delivered build's `aps-environment` entitlement is `production`

#### Scenario: The APNs settings cannot disagree
- **WHEN** any archive is built
- **THEN** `APS_ENVIRONMENT`, `APNS_ENV` and the crash-reporting environment are all derived from one build-configuration discriminator, and no combination exists in which they disagree

#### Scenario: A promoted App Store build is production-APNs
- **WHEN** the App Store release channel promotes a `main` `ios-deliver` build
- **THEN** that build already carries the `production` `aps-environment` entitlement, because it was built as a Release archive on `main`

#### Scenario: An ssh-mac dev build stays sandbox
- **WHEN** the ssh-mac loop builds a Debug archive, with or without a device-facing host override
- **THEN** the discriminator resolves to the undistributed value and the build is `development`/`sandbox`

#### Scenario: A branch-gate archive stays sandbox
- **WHEN** a push to a ref other than `main` produces the Debug gate archive (capability `ios-ci`)
- **THEN** the discriminator resolves to the undistributed value — immaterial to the discarded archive, and consistent with the configuration-tied rule

### Requirement: Release archives bake the crash-reporting DSN; dev builds never receive it

Every CI **Release/distribution** archive SHALL be built with the crash-reporting DSN injected from the
`SENTRY_DSN` repository secret, resolved as a **build-scope value** by the deployment renderer (capability
`deployment-configuration`) alongside the APNs environment (capability `crash-reporting` consumes it from
the bundle at runtime). The value SHALL reach **both** targets — the app and the background-upload
extension. **Undistributed** builds — the **branch-gate Debug archives** (capability `ios-ci`) and the
ssh-mac local build loop — SHALL NOT receive the DSN, leaving it absent so the SDK never starts there.

Absence SHALL be **enforced by the renderer**, which SHALL emit no DSN unless the build-configuration
discriminator names a distributed build. Previously absence rested on CI simply not exporting the secret;
deriving it means a stray export cannot arm crash reporting on an undistributed build. Like the APNs
environment, the reporting channel is tied to the build configuration, with no separate enable flag that
could disagree with it.

The `ios.yml` `workflow_dispatch` dev-IPA path is no longer named here because that trigger is removed
(capability `ios-ci`).

#### Scenario: A main TestFlight build carries the DSN

- **WHEN** `ios-build` produces the signed Release archive on `main`
- **THEN** both the app's and the extension's bundle configuration carry the DSN from the
  `SENTRY_DSN` secret

#### Scenario: An ssh-mac dev build carries no DSN

- **WHEN** the ssh-mac loop builds a Debug archive
- **THEN** no DSN is injected, the bundle value is absent, and crash reporting never initializes in
  that build

#### Scenario: A branch-gate archive carries no DSN

- **WHEN** a push to a ref other than `main` produces the Debug gate archive
- **THEN** no DSN is injected, so a discarded gate build can never report into the production
  crash-reporting project

#### Scenario: A stray secret cannot arm an undistributed build

- **WHEN** the `SENTRY_DSN` value is present in the environment of a build whose discriminator names an
  undistributed build
- **THEN** the renderer emits no DSN, and the built bundle's value is absent
