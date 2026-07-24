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
left this backend fail-closed at boot for two weeks, with CI green throughout.) **Removing** a secret is
safe in either order, since a value that is no longer read cannot fail validation.

The scheduled cleanup (capability `scheduled-cleanup`) runs **outside** the Edge Script and holds
`BUNNY_STORAGE_ACCESS_KEY` as **its own workflow's** GitHub Actions secret — and nothing else. It makes no
request to the Edge Script, so it needs no credential authorizing one. This does not admit the Bunny
**account** key to CI (the prohibition that keeps config CI-unmanageable is unchanged): it grants only the
storage-zone key to one non-deploy workflow.

There SHALL be **no admin, master, or route-scoped bypass credential** anywhere in the backend. The former
notify admin key existed solely so the sweep could announce an expiring event before deleting it; with
that notify removed, no caller remains and the credential is retired rather than left as a standing
authorization path nobody exercises.

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

#### Scenario: A retired secret left set is simply unread

- **WHEN** the Edge Script environment still carries the former notify admin key after this change
- **THEN** the backend boots and serves, reading it for nothing and authorizing nothing with it

#### Scenario: The sweep holds one credential

- **WHEN** the scheduled cleanup workflow's secrets are enumerated
- **THEN** the storage-zone `AccessKey` is the only one, and no credential grants access to the Edge
  Script

#### Scenario: Configuration is injected, not read per-request

- **WHEN** a request is handled
- **THEN** it uses the config validated at startup and has no per-request configuration failure path
