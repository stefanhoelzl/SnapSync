## MODIFIED Requirements

### Requirement: Environment-only configuration, fail-closed

The backend SHALL read all runtime configuration exclusively from Edge Script environment variables;
no secret SHALL appear in source. The required inventory is the storage zone name, the storage host
(native API), the storage zone `AccessKey`, the **S3 region** and **S3 endpoint host** (used to mint
presigned download URLs), and the public base URL `PUBLIC_BASE_URL`. The storage zone name doubles as
the S3 **Access Key ID** and bucket, and the `AccessKey` doubles as the S3 **secret**, so no additional
S3 credential is configured. Configuration SHALL be validated **once at startup**; a missing or blank
required variable SHALL cause startup to fail (the parse throws), so a misconfigured deployment does not
serve and never operates against a wrong or unauthenticated target, and never emits a listing carrying a
blank or unsignable download URL. The validated config is injected into the request handlers, which
therefore have no configuration failure path. This contract is shared by every endpoint (create,
upload, list); it is not owned by any single endpoint.

#### Scenario: Missing storage config fails the boot

- **WHEN** a required value (zone, native host, `AccessKey`, S3 region, or S3 host) is absent or blank
  at startup
- **THEN** config parsing throws, the endpoint does not start, and no request is ever served

#### Scenario: Missing public base URL fails the boot

- **WHEN** `PUBLIC_BASE_URL` is absent or blank at startup
- **THEN** config parsing throws and the endpoint does not start

#### Scenario: Configuration is injected, not read per-request

- **WHEN** a request is handled
- **THEN** it uses the config validated at startup and has no per-request configuration failure path

### Requirement: PUBLIC_BASE_URL is the backend's public origin

`PUBLIC_BASE_URL` SHALL be the backend's public origin (scheme and host) with any trailing slash
removed — the origin clients reach the backend at for **upload, event-creation, and list** requests. It
is a runtime environment variable in the same category as the storage `AccessKey` (the endpoint's
runtime config), not a CI/deploy credential. It SHALL **no longer** appear in any download URL:
downloads are presigned S3 GET URLs at the S3 endpoint (capability `bunny-list-endpoint`), not
`<PUBLIC_BASE_URL>`-composed paths.

#### Scenario: Origin for upload/event/list traffic

- **WHEN** the app issues an upload, event-creation, or list request
- **THEN** it targets `PUBLIC_BASE_URL` (the backend origin), with any trailing slash normalized away

#### Scenario: Not part of any download URL

- **WHEN** a listing composes a download `url`
- **THEN** the `url` is a presigned S3 endpoint URL and does not contain `PUBLIC_BASE_URL`

#### Scenario: Runtime variable, not a CI secret

- **WHEN** the backend is deployed
- **THEN** `PUBLIC_BASE_URL` is supplied as an Edge Script environment variable (its runtime config),
  not as a deploy-workflow secret
