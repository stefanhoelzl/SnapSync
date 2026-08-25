## ADDED Requirements

### Requirement: A delivering archive is verified to carry the resolved deployment

Before an archive is handed on for delivery, the workflow SHALL read the deployment values back out of the
**built bundles** and SHALL fail the run when any of them disagrees with the resolution that produced it.
The check SHALL cover the app bundle **and** the nested background-upload extension bundle, and SHALL
cover the device-facing upload base, the APNs environment, the crash-reporting environment and the
crash-reporting DSN, in addition to the bundle identifier.

Reading the built bundle is what distinguishes this from a check on the generator's output. A renderer test
proves the generator emitted the intended bytes; it cannot see a grammar that reinterprets them, nor a
resource that failed to reach a bundle. Both have shipped mute builds.

The extension is a separately-built nested bundle with its own resources phase, and the on-device
verification path — sending a diagnostic dump — exercises only the **app** process. A resource present in
the app and absent from the extension would therefore look like a complete success, while the extension
uploads nowhere, registers the wrong APNs environment, and reports nothing.

The DSN SHALL be compared without being echoed into the build log.

#### Scenario: A delivering run verifies both bundles

- **WHEN** `ios-build` produces the signed Release archive
- **THEN** it reads the deployment values from the app bundle and from the extension bundle and compares
  each against the resolver's output, failing the run on any mismatch

#### Scenario: A truncated or mangled value fails the run

- **WHEN** a rendered value does not survive its grammar and reaches the bundle altered
- **THEN** the comparison fails and no archive is handed on for delivery

#### Scenario: A resource missing from one bundle fails the run

- **WHEN** the generated deployment rendering reaches the app bundle but not the extension bundle
- **THEN** the check fails naming the bundle, rather than delivering a build whose extension silently
  uploads nowhere and reports nothing

#### Scenario: An undistributed build is verified to carry no DSN

- **WHEN** the archive's discriminator names an undistributed build
- **THEN** the check asserts the DSN value is absent in both bundles

## MODIFIED Requirements

### Requirement: Release archives bake the crash-reporting DSN; dev builds never receive it

Every CI **Release/distribution** archive SHALL be built with the crash-reporting DSN injected from the
`SENTRY_DSN` repository secret, resolved as a **build-scope value** by the deployment renderer (capability
`deployment-configuration`) alongside the APNs environment (capability `crash-reporting` consumes it from
the bundle at runtime). The DSN SHALL be carried in the generated **property-list** rendering, never in the
build-settings rendering: a DSN contains `//`, which opens a comment in the build-settings grammar and
truncates the value to an unusable prefix that is nevertheless non-empty — so the SDK never starts while
the in-app bug-report dialog still opens and silently loses every dump. The value SHALL reach **both**
targets — the app and the background-upload extension. **Undistributed** builds — the **branch-gate Debug archives** of non-delivering pushes (capability
`ios-ci`) and the ssh-mac local build loop — SHALL NOT receive the DSN, leaving it absent so the SDK
never starts there.

Absence SHALL be **enforced by the renderer**, which SHALL emit no DSN unless the build-configuration
discriminator names a distributed build. Previously absence rested on CI simply not exporting the secret;
deriving it means a stray export cannot arm crash reporting on an undistributed build. Like the APNs
environment, the reporting channel is tied to the build configuration, with no separate enable flag that
could disagree with it.

A **dispatched** run is a distributed build and SHALL therefore receive the DSN (capability `ios-ci`).
This is the whole reason the dispatch is marked distributed rather than left on the branch defaults: a
build with no DSN opens no in-app bug-report dialog at all (capability `diagnostic-logging`), so a probe
build that could not send its dump would defeat the purpose of dispatching it. The retired dev-IPA
dispatch, which was undistributed, is gone and is not what this names.

#### Scenario: A main TestFlight build carries the DSN

- **WHEN** `ios-build` produces the signed Release archive on `main`
- **THEN** both the app's and the extension's bundle configuration carry the DSN from the
  `SENTRY_DSN` secret, byte-identical to the secret's value

#### Scenario: An ssh-mac dev build carries no DSN

- **WHEN** the ssh-mac loop builds a Debug archive
- **THEN** no DSN is injected, the bundle value is absent, and crash reporting never initializes in
  that build

#### Scenario: A DSN cannot be injected into a local build by an xcodebuild override

- **WHEN** an operator needs an on-device build that can report
- **THEN** they dispatch the workflow on the branch, because the DSN reaches a generated bundle resource
  that no `xcodebuild` build-setting override can substitute into

#### Scenario: A branch-gate archive carries no DSN

- **WHEN** a push to a ref other than `main` produces the Debug gate archive
- **THEN** no DSN is injected, so a discarded gate build can never report into the production
  crash-reporting project

#### Scenario: A stray secret cannot arm an undistributed build

- **WHEN** the `SENTRY_DSN` value is present in the environment of a build whose discriminator names an
  undistributed build
- **THEN** the renderer emits no DSN, and the built bundle's value is absent
