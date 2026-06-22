## ADDED Requirements

### Requirement: Compile-time edge host default and override

The iOS workflow SHALL bake the **deployed bunny edge endpoint URL** into the extension's
`BackgroundUploadURLBase` (build setting `BACKGROUND_UPLOAD_URL_BASE`) **by default on every ref**,
including the `main`/TestFlight build — safe because the device carries no storage credential and the
edge endpoint is the production backend. The workflow SHALL retain a `workflow_dispatch` `upload_host`
input that, when set, overrides the baked host (for pointing a development IPA at a local Deno
backend on the LAN); when the input is empty or absent, the deployed edge URL SHALL be used. The
inert `https://dummy.invalid` default is removed.

#### Scenario: Default build bakes the deployed edge URL
- **WHEN** the iOS workflow runs on any ref with no `upload_host` dispatch input
- **THEN** `BACKGROUND_UPLOAD_URL_BASE` is the deployed bunny edge endpoint URL (not `dummy.invalid`)

#### Scenario: TestFlight build targets the live edge endpoint
- **WHEN** the `ios-build` job runs on `refs/heads/main`
- **THEN** the uploaded TestFlight build's `BackgroundUploadURLBase` is the deployed edge endpoint URL

#### Scenario: Dispatch override bakes a local host
- **WHEN** the workflow is dispatched with a non-empty `upload_host`
- **THEN** that host is baked into `BackgroundUploadURLBase` for that run, overriding the default
