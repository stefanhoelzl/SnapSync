## MODIFIED Requirements

### Requirement: Secrets-only environment, fail-closed

The backend SHALL read exactly **three** values from the Edge Script environment, all of them genuine
credentials: the storage-zone `AccessKey` (`BUNNY_STORAGE_ACCESS_KEY`, which doubles as the S3 secret),
the APNs Auth Key PEM (`APNS_PRIVATE_KEY`), and the device-token signing key (`ATTEST_TOKEN_KEY`, which
signs and verifies the bearer tokens of capability `device-attestation`). **No secret SHALL appear in
source.** All SHALL be validated **once at startup**; a missing or blank value SHALL cause startup to fail
(the parse throws), so a misconfigured deployment does not serve and never operates against an
unauthenticated target. The validated config SHALL be injected into the request handlers, which therefore
have no per-request configuration failure path.

Because CI holds only the script-scoped deploy key and **cannot write the script's environment**, a new
secret SHALL be set in the Edge Script environment **before** the code that reads it is merged to `main`.
Merging first makes the script fail to boot on the next deploy — a total outage until the secret is set
by hand. (This ordering is not hypothetical: a change that added required env vars without setting them
left this backend fail-closed at boot for two weeks, with CI green throughout.)

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

#### Scenario: No other variable is required to boot

- **WHEN** the three secrets are present and no other environment variable is set
- **THEN** the backend boots and serves

#### Scenario: Configuration is injected, not read per-request

- **WHEN** a request is handled
- **THEN** it uses the config validated at startup and has no per-request configuration failure path

## ADDED Requirements

### Requirement: Apple's App Attest root CA is a source constant

Apple's App Attest **root certificate** SHALL be a **source constant**, committed in the backend source,
and SHALL NOT be read from the environment. It is the trust anchor every attestation's certificate chain
is verified against (capability `device-attestation`).

It meets the existing criterion exactly: it is a **public fact** (Apple publishes it), so committing it
exposes nothing, and shipping it in the same bundle as the code that reads it means a verification change
cannot be deployed without its trust anchor.

#### Scenario: The trust anchor ships with the code that uses it

- **WHEN** the backend bundle is deployed
- **THEN** Apple's App Attest root CA is present in the bundle, and no environment variable is consulted
  for it

#### Scenario: A platform variable never overrides the trust anchor

- **WHEN** an environment variable naming a root CA is set on the Edge Script
- **THEN** it is ignored; the source constant is used
