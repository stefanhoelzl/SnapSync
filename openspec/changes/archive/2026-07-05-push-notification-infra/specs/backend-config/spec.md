## ADDED Requirements

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
