# ios-testflight-delivery Delta

## ADDED Requirements

### Requirement: Release archives bake the crash-reporting DSN; dev builds never receive it

Every CI **Release/distribution** archive SHALL be built with the crash-reporting DSN injected from the
`SENTRY_DSN` repository secret through the same shared archive seam that injects `APS_ENVIRONMENT`
(capability `crash-reporting` consumes it from the bundle at runtime). The value SHALL reach **both**
targets — the app and the background-upload extension. **Dev/sideload** builds — the `ios.yml`
`workflow_dispatch` dev-IPA path and the ssh-mac local build loop — SHALL NOT receive the DSN, leaving
it absent so the SDK never starts there: like the APNs environment, the reporting channel is tied to the
build configuration, with no separate enable flag that could disagree with it.

#### Scenario: A main TestFlight build carries the DSN

- **WHEN** `ios-build` produces the signed Release archive on `main`
- **THEN** both the app's and the extension's bundle configuration carry the DSN from the
  `SENTRY_DSN` secret

#### Scenario: A dev-IPA build carries no DSN

- **WHEN** the `ios.yml` `workflow_dispatch` dev-IPA path or the ssh-mac loop builds a Debug archive
- **THEN** no DSN is injected, the bundle value is absent, and crash reporting never initializes in
  that build

### Requirement: main delivery retains the build's dSYMs keyed by build number

On `main`, the delivery pipeline SHALL publish the Release archive's dSYMs as a workflow artifact
whose name carries the build number (`CFBundleVersion`), so an address-only crash report from any
delivered build can be symbolicated offline (the Bugsink instance ingests no dSYMs — see capability
`crash-reporting`). On other refs no dSYM artifact is published (consistent with the pure-gate posture
of `ios-build`).

#### Scenario: A main build's dSYMs are retrievable

- **WHEN** a `main` push delivers build `N` to TestFlight
- **THEN** a workflow artifact keyed by `N` contains that archive's dSYMs

#### Scenario: Non-main refs publish no dSYM artifact

- **WHEN** a push to any other ref produces its gate archive
- **THEN** no dSYM artifact is published for it
