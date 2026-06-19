## ADDED Requirements

### Requirement: Optional compile-time upload host via workflow_dispatch

The `ios` workflow SHALL additionally support a `workflow_dispatch` trigger carrying an **optional**
`upload_host` input. The signed archive step SHALL set the build setting
`BACKGROUND_UPLOAD_URL_BASE` from that input, defaulting to `https://dummy.invalid` when the input
is empty or absent (`${{ inputs.upload_host || 'https://dummy.invalid' }}`). Because a plain `push`
supplies no input, every push (including to `main`, the TestFlight source) SHALL continue to bake
the inert `dummy.invalid` host; only a **deliberate** manual dispatch that supplies `upload_host`
SHALL bake a real host into that run's development IPA. This keeps `main`/TestFlight builds inert
while letting an operator produce a dev IPA pre-baked for a specific (e.g. local MinIO) upload host
without editing tracked files.

#### Scenario: Plain push bakes the inert default host
- **WHEN** a commit is pushed to any ref with no `workflow_dispatch` input
- **THEN** the archive bakes `BACKGROUND_UPLOAD_URL_BASE = https://dummy.invalid` and the resulting
  IPAs (dev artifact, and on `main` the TestFlight build) target the inert dummy host

#### Scenario: Manual dispatch bakes the supplied host
- **WHEN** the workflow is dispatched manually with `upload_host = http://<lan-ip>:9000`
- **THEN** the archive bakes `BACKGROUND_UPLOAD_URL_BASE = http://<lan-ip>:9000`, and the development
  IPA artifact from that run targets that host

#### Scenario: Dispatched host does not reach the gate or pollute main
- **WHEN** a manual dispatch supplies `upload_host`
- **THEN** only that dispatched run's archive uses it; subsequent ordinary pushes (including `main`)
  are unaffected and continue to bake `https://dummy.invalid`
