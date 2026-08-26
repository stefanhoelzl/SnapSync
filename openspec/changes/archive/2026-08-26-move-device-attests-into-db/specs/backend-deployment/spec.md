## MODIFIED Requirements

### Requirement: Deploy with secret-held, script-scoped credentials

The workflow SHALL deploy the bundled file to the configured Edge Scripting app using a
**script-scoped deploy key** and the **script id**, each supplied **only** as a GitHub Actions secret.
The Bunny **account API key** SHALL NOT be used by the deploy workflow (it is needed only to provision
the zone/app and to set the two runtime secrets). No Bunny credential SHALL appear in source or in the
workflow file.

This is load-bearing, not hygiene. Bunny issues **no scoped API keys**: the account key that could
write an Edge Script's environment variables also owns the storage zone holding every user's photos and
the DNS zone serving the device-facing origin. Keeping it out of CI is therefore why CI cannot manage
platform config at all — which is why non-secret config lives in source. A future change that admits
the account key to CI to "fix config drift" SHALL be understood to be trading that blast radius away.

The two runtime secrets (`BUNNY_STORAGE_ACCESS_KEY`, `APNS_PRIVATE_KEY`) SHALL be configured as Edge
Script environment values, **not** as deploy-workflow secrets — they are the endpoint's runtime config,
not CI credentials.

The **database** credentials are a third category and SHALL be held by the deploy workflow, because CI —
not the endpoint — is what applies schema migrations (capability `database`). The migration step SHALL run
**before** the bundle is published and SHALL fail the run without publishing if it fails, so a bundle is
never served against a store it does not expect. Holding them widens what a compromised deploy path can
reach to the relational store; it SHALL NOT be widened further to the storage access key, which would
extend that reach to every user's photos and is what the exclusion above exists to prevent.

#### Scenario: Deploy uses secret-held, script-scoped credentials

- **WHEN** the deploy step runs
- **THEN** it authenticates using a script id and deploy key sourced from GitHub Actions secrets, and
  no credential is present in the repository or workflow file

#### Scenario: The account API key is absent from CI

- **WHEN** the deploy workflow is inspected
- **THEN** it holds no bunny account API key, and performs no platform-configuration write

#### Scenario: The storage access key is absent from the deploy workflow

- **WHEN** the deploy workflow is inspected
- **THEN** it holds no storage access key, and performs no read or write against the storage zone

#### Scenario: A failed migration publishes nothing

- **WHEN** the migration step fails
- **THEN** the run fails and the bundle is not published
