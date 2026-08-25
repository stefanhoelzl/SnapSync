## ADDED Requirements

### Requirement: The relational store is a deployment-declared, secret-held dependency

The backend's relational store (capability `database`) SHALL be reached through credentials the resolved
deployment declares as **runtime environment references** — the database URL and its access token — exactly
as the storage `AccessKey`, the APNs key, and the device-token signing key are. No connection string or
token SHALL appear in any authored file; the deployment declares the variable's **name**, never its value.

Both SHALL be validated once at startup with every other secret, so a deployment that cannot reach its
store fails to boot rather than serving requests that silently lose relational writes.

Because CI holds only the script-scoped deploy key and cannot write the script's environment, these
variables SHALL be set in the Edge Script environment **before** the code that reads them is merged to
`main`, per this capability's existing ordering rule.

Each deployment SHALL address its **own** database. The `local` deployment SHALL NOT be resolvable against
the production store: a dev run that wrote or deleted rows there would corrupt live events, and unlike the
storage zone there is no per-object blast radius to fall back on.

#### Scenario: A missing database credential fails startup

- **WHEN** the deployment resolves with the database URL or token absent or blank
- **THEN** startup fails and the script does not serve, rather than accepting writes it cannot record

#### Scenario: The credentials are declared, never authored

- **WHEN** the deployment files are inspected
- **THEN** they carry the environment variable **names** for the database URL and token and neither value

## MODIFIED Requirements

### Requirement: Deploy is gated on a post-publish boot probe

After publishing on `main`, the workflow SHALL probe the device-facing origin until it observes the bundle
it just deployed, and SHALL fail the run otherwise. This exists because `POST /code` + `POST /publish`
succeed whether or not the deployed bundle can boot, so a green deploy step is **not** evidence that the
script serves.

The probe SHALL be satisfied only by a response that identifies **the bundle this run deployed**. A bare
success is insufficient: it cannot distinguish the new deployment from the previous one still being served,
which is the failure the probe exists to catch.

The probe SHALL additionally witness that the relational store is reachable **and that foreign-key
enforcement is on**. Measured, `PRAGMA foreign_keys` defaults to enabled on this platform, but a
provisioning change that turned it off would disable every constraint **silently** — no error, no rejected
write, and two staleness classes the schema is designed to make unstateable quietly reachable again
(capability `database`). A measurement is not a guarantee, so the probe asserts the value rather than
trusting it.

The probe SHALL retry only causes that time can resolve — a connection failure, a server error, a
not-found, or a response identifying a *different* bundle — up to a bounded deadline, and SHALL fail
**immediately** on causes that waiting cannot fix, naming which. Retrying a terminal cause until a deadline
turns a specific bug into a timeout. A store that answers with foreign keys **off** is a terminal cause: no
amount of waiting turns it on.

The probe SHALL target the **device-facing origin**, so a green probe also witnesses the DNS, certificate
and pull-zone path a device traverses, per "bunny Edge Scripting is the device-facing runtime". It SHALL
NOT target a runtime-provider hostname: that would report success while the device-facing path was broken.

#### Scenario: A deploy that cannot boot fails the run

- **WHEN** the publish succeeds but the deployed bundle does not serve
- **THEN** the probe exhausts its deadline and the run fails

#### Scenario: A stale bundle does not satisfy the probe

- **WHEN** the origin still serves the previous bundle
- **THEN** the probe keeps retrying rather than passing, because the response does not identify this run's
  bundle

#### Scenario: Foreign keys off fails the deploy immediately

- **WHEN** the probe reaches the store and finds foreign-key enforcement disabled
- **THEN** the run fails immediately, naming that cause, rather than retrying to its deadline

#### Scenario: An unreachable store fails the deploy

- **WHEN** the deployed bundle cannot reach its relational store
- **THEN** the probe fails the run rather than leaving a deployment that accepts requests it cannot record
