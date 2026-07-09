# backend-config Specification

## Purpose

How the backend reads its runtime configuration: **environment variables only, fail-closed**. The storage
zone name and host, the storage `AccessKey`, the public origin (`PUBLIC_BASE_URL`), and the APNs provider
credentials all arrive from the Edge Script environment; **no secret appears in source**, and a missing
required value stops the backend rather than degrading it silently.

This matters because the backend is the only holder of the storage credential — the device deliberately holds
none. A fail-open config would turn a deploy mistake into either a dead upload path or, worse, a leaked key.
`PUBLIC_BASE_URL` gets its own requirement because the backend must mint absolute URLs that name the
device-facing origin, which is a domain we control rather than whichever runtime is currently active.

Decision record: `changes/archive/2026-06-27-add-image-download`.

## Requirements
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

### Requirement: APNs provider credentials, fail-closed

The backend SHALL read the APNs provider credentials exclusively from Edge Script environment
variables — `APNS_KEY_ID` (the Auth Key id), `APNS_TEAM_ID` (the Apple team id), `APNS_PRIVATE_KEY`
(the `.p8` PEM contents, not a path), and `APNS_TOPIC` (the push topic, the app bundle id
`app.snapsync`) — validated **once at startup** alongside the storage configuration. A missing or blank
required APNs variable SHALL cause startup to fail (the parse throws), so a deployment that cannot send
pushes never boots. These are **runtime configuration** in the same category as the storage `AccessKey`
(set as platform environment variables), **not** CI/deploy-workflow secrets, and SHALL NOT appear in
source. The validated APNs config SHALL be injected into the request handlers like the rest of the
config (no per-request configuration failure path). The APNs host is **not** configured — it is chosen
per push from the token's `env` (capability `apns-push-sender`).

#### Scenario: Missing APNs credential fails the boot

- **WHEN** any of `APNS_KEY_ID`, `APNS_TEAM_ID`, `APNS_PRIVATE_KEY`, or `APNS_TOPIC` is absent or blank
  at startup
- **THEN** config parsing throws, the backend does not start, and no request is ever served

#### Scenario: APNs credentials are runtime env, not deploy secrets

- **WHEN** the backend is deployed
- **THEN** the APNs credentials are supplied as Edge Script environment variables (runtime config, the
  `AccessKey` category), not as deploy-workflow secrets, and never appear in source

#### Scenario: APNs config is injected, not read per-request

- **WHEN** a notify request is handled
- **THEN** it uses the APNs config validated at startup and has no per-request configuration failure
  path

