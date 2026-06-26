## MODIFIED Requirements

### Requirement: Compile-time edge host default and override

The extension's `BackgroundUploadURLBase` (build setting `BACKGROUND_UPLOAD_URL_BASE`) SHALL default
to the **deployed HTTPS backend URL** baked from `Config.xcconfig` — the single source of the host
literal — so **every ref**, including the `main`/TestFlight build, targets it (safe because the
device carries no storage credential and the endpoint is the production backend). The iOS workflow
SHALL **not** restate the host: on a plain push or a dispatch with an empty `upload_host`, the
workflow SHALL omit any `BACKGROUND_UPLOAD_URL_BASE` override and let the `Config.xcconfig` default
flow through. The workflow SHALL retain a `workflow_dispatch` `upload_host` input that, when
non-empty, overrides the baked host for that run (for pointing a development IPA at an alternate
**HTTPS** host, e.g. a staging backend). The `upload_host` input SHALL be **HTTPS-only**: a value
that does not begin with `https://` SHALL fail the run before archiving (default ATS forbids
plaintext, so a baked `http://` host would silently fail on device). The inert `https://dummy.invalid`
default is removed. This requirement is the **single owner** of the compile-time upload-host contract;
the development (sideload) IPA and the TestFlight build both inherit whatever host this shared archive
step bakes.

#### Scenario: Default build bakes the deployed host from xcconfig
- **WHEN** the iOS workflow runs on any ref with no `upload_host` dispatch input
- **THEN** the workflow sets no `BACKGROUND_UPLOAD_URL_BASE` override and the archive bakes the
  `Config.xcconfig` default (the deployed HTTPS backend URL, not `dummy.invalid`)

#### Scenario: TestFlight build targets the live endpoint
- **WHEN** the `ios-build` job runs on `refs/heads/main`
- **THEN** the uploaded TestFlight build's `BackgroundUploadURLBase` is the deployed HTTPS backend URL

#### Scenario: Dispatch override bakes a supplied HTTPS host
- **WHEN** the workflow is dispatched with a non-empty `upload_host` beginning with `https://`
- **THEN** that host is baked into `BackgroundUploadURLBase` for that run, overriding the default

#### Scenario: A non-HTTPS dispatch override fails the run
- **WHEN** the workflow is dispatched with an `upload_host` that does not begin with `https://`
- **THEN** the run fails before archiving and bakes no plaintext host

#### Scenario: A dispatch override does not pollute subsequent builds
- **WHEN** a manual dispatch supplies `upload_host` for one run
- **THEN** only that run's archive uses it; subsequent ordinary pushes (including `main`) set no
  override and bake the `Config.xcconfig` default
