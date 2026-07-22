## ADDED Requirements

### Requirement: The browser-facing site is deployed by a second, account-key-free path

The `site/` build (per `web-site`) SHALL be deployed to the storage `site/` prefix by a CI path that
authenticates with the **storage-zone password only** — the same secret the nightly sweep already holds —
and SHALL NOT require or hold the bunny **account key**. This preserves the standing least-privilege rule:
CI ships code (the api bundle) and now also site assets, but never holds the account key that owns the
storage zone and DNS.

#### Scenario: The site deploy holds no account key

- **WHEN** the site build+deploy runs in CI
- **THEN** it authenticates to storage with the storage-zone password, and the bunny account key is absent
  from the job

#### Scenario: Code and site are separate CI deploys

- **WHEN** the api bundle is deployed and the site is deployed
- **THEN** the api bundle ships via the script-scoped deploy key and the site ships via the storage
  password, and neither requires the account key

### Requirement: Static-site routing is source-owned; no pull-zone edge rules

The static-site routing SHALL live **in the deployed api bundle as source-owned code** — the closed
static-path allowlist and its proxy of the storage `site/` prefix (per `web-site`). The site SHALL be
reachable with **no pull-zone edge rule** and **no account-key configuration** of the pull zone. Disaster recovery therefore remains "redeploy the bundle and repoint DNS": no out-of-band
routing state exists that a rebuilt environment could lose.

#### Scenario: No edge rule is required for the site to be reachable

- **WHEN** the api bundle is deployed and the pull zone points at it (default configuration)
- **THEN** `/`, `/join`, and the site's assets are reachable with no pull-zone edge rule configured

#### Scenario: Routing is recoverable from source

- **WHEN** the runtime is rebuilt from the repo and DNS is repointed
- **THEN** the static-site routing is present because it shipped in the bundle, with no manual pull-zone
  configuration step

### Requirement: The storage zone hosts a public `site/` prefix co-tenant with private data

The backend storage zone SHALL host a `site/` prefix holding the built browser-facing site, co-tenant with
the private `files/`, `events/`, and `devices/` prefixes. Public reachability SHALL be scoped by the api
proxy, which serves **only** the `site/` prefix publicly; the private prefixes SHALL NOT be publicly
reachable through the api and SHALL continue to be accessed as before (presigned URLs for photos, the
account/sweep for management).

#### Scenario: Only site/ is publicly reachable

- **WHEN** a public request arrives for a path outside the static-site allowlist (e.g. a `files/` object)
- **THEN** the api does not proxy it from storage; private prefixes are not publicly served

### Requirement: The nightly sweep is prefix-scoped and never touches `site/`

The nightly cleanup sweep SHALL enumerate and reclaim **only** the `events/`, `files/devices/`, and
`devices/` prefixes; it SHALL NOT enumerate or delete anything under the `site/` prefix, and it SHALL NOT
be changed into a whole-zone walk. Hygiene of the `site/` prefix is owned by the mirror deploy (per
`web-site`), not the sweep.

#### Scenario: The sweep ignores site/

- **WHEN** the nightly sweep runs against the storage zone
- **THEN** it lists and reclaims only `events/`, `files/devices/`, and `devices/`, and leaves every
  `site/` object untouched
