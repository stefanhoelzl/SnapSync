# backend-deployment Delta

## MODIFIED Requirements

### Requirement: Secrets-only environment, fail-closed

The backend SHALL read exactly **four** values from the Edge Script environment, all of them genuine
credentials: the storage-zone `AccessKey` (`BUNNY_STORAGE_ACCESS_KEY`, which doubles as the S3 secret),
the APNs Auth Key PEM (`APNS_PRIVATE_KEY`), the device-token signing key (`ATTEST_TOKEN_KEY`, which
signs and verifies the bearer tokens of capability `device-attestation`), and the notify **admin key**
(`ADMIN_NOTIFY_KEY`, which authorizes the scheduled cleanup's silent-push notify of an expiring event's members;
capabilities `event-notify-endpoint`, `scheduled-cleanup`). **No secret SHALL appear in source.** All
SHALL be validated **once at startup**; a missing or blank value SHALL cause startup to fail (the parse
throws), so a misconfigured deployment does not serve and never operates against an unauthenticated
target. The validated config SHALL be injected into the request handlers, which therefore have no
per-request configuration failure path.

Because CI holds only the script-scoped deploy key and **cannot write the script's environment**, a new
secret SHALL be set in the Edge Script environment **before** the code that reads it is merged to `main`.
Merging first makes the script fail to boot on the next deploy — a total outage until the secret is set
by hand. (This ordering is not hypothetical: a change that added required env vars without setting them
left this backend fail-closed at boot for two weeks, with CI green throughout.)

The scheduled cleanup (capability `scheduled-cleanup`) runs **outside** the Edge Script and holds
`BUNNY_STORAGE_ACCESS_KEY` and `ADMIN_NOTIFY_KEY` as **its own workflow's** GitHub Actions secrets. This does
not admit the Bunny **account** key to CI (the prohibition that keeps config CI-unmanageable is
unchanged) — it grants only the storage-zone key and the notify-only admin key to one non-deploy
workflow.

#### Scenario: Missing storage AccessKey fails the boot

- **WHEN** `BUNNY_STORAGE_ACCESS_KEY` is absent or blank at startup
- **THEN** config parsing throws, the backend does not start, and no request is ever served

#### Scenario: Missing APNs private key fails the boot

- **WHEN** `APNS_PRIVATE_KEY` is absent or blank at startup
- **THEN** config parsing throws, the backend does not start, and no request is ever served

#### Scenario: Missing token signing key fails the boot

- **WHEN** `ATTEST_TOKEN_KEY` is absent or blank at startup
- **THEN** config parsing throws, the backend does not start, and no request is ever served — the gate
  can never be silently absent

#### Scenario: Missing admin key fails the boot

- **WHEN** `ADMIN_NOTIFY_KEY` is absent or blank at startup
- **THEN** config parsing throws, the backend does not start, and no request is ever served

#### Scenario: No other variable is required to boot

- **WHEN** the four secrets are present and no other environment variable is set
- **THEN** the backend boots and serves

#### Scenario: Configuration is injected, not read per-request

- **WHEN** a request is handled
- **THEN** it uses the config validated at startup and has no per-request configuration failure path
