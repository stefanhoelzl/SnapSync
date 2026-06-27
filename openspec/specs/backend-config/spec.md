# backend-config Specification

## Purpose
TBD - created by archiving change add-image-download. Update Purpose after archive.
## Requirements
### Requirement: Environment-only configuration, fail-closed

The backend SHALL read all runtime configuration exclusively from Edge Script environment variables;
no secret SHALL appear in source. The required inventory is the storage zone name, the storage host,
the storage zone `AccessKey`, and the public base URL `PUBLIC_BASE_URL`. Configuration SHALL be
validated **once at startup**; a missing or blank required variable SHALL cause startup to fail (the
parse throws), so a misconfigured deployment does not serve and never operates against a wrong or
unauthenticated target, and never emits a listing carrying a blank or broken download URL. The
validated config is injected into the request handlers, which therefore have no configuration failure
path. This contract is shared by every endpoint (create, upload, list, download); it is not owned by
any single endpoint.

#### Scenario: Missing storage config fails the boot

- **WHEN** a required storage value (zone, host, or `AccessKey`) is absent or blank at startup
- **THEN** config parsing throws, the endpoint does not start, and no request is ever served

#### Scenario: Missing public base URL fails the boot

- **WHEN** `PUBLIC_BASE_URL` is absent or blank at startup
- **THEN** config parsing throws and the endpoint does not start (it never serves a listing with a
  blank or broken download URL)

#### Scenario: Configuration is injected, not read per-request

- **WHEN** a request is handled
- **THEN** it uses the config validated at startup and has no per-request configuration failure path

### Requirement: PUBLIC_BASE_URL is the backend's public origin

`PUBLIC_BASE_URL` SHALL be the backend's public origin (scheme and host) with any trailing slash
removed, so that a download URL composed as `<PUBLIC_BASE_URL>/event/<id>/file/<name>` contains no
double slash. It is the origin clients reach the backend at — a runtime environment variable in the
same category as the storage `AccessKey` (the endpoint's runtime config), not a CI/deploy credential.

#### Scenario: Trailing slash normalized

- **WHEN** `PUBLIC_BASE_URL` is configured with a trailing slash
- **THEN** the composed download URL contains no double slash between the origin and the `/event/...`
  path

#### Scenario: Runtime variable, not a CI secret

- **WHEN** the backend is deployed
- **THEN** `PUBLIC_BASE_URL` is supplied as an Edge Script environment variable (its runtime config),
  not as a deploy-workflow secret

