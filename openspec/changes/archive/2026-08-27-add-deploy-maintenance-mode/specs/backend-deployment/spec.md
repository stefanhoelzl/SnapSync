## ADDED Requirements

### Requirement: A migrating deploy serves maintenance while the schema and the bundle disagree

When a deploy would apply one or more schema migrations, the workflow SHALL publish a **maintenance
bundle** — the same commit, resolved from a deployment that sets the maintenance flag — and SHALL confirm
it is serving **before** applying any migration. Only after the migration succeeds SHALL it publish the
real bundle and lift the window.

This exists because the migrate-then-publish ordering, which is correct and stays, leaves an interval in
which the **previous** bundle answers requests against the **migrated** store, with statements written for
a schema shape that no longer exists.

The workflow SHALL determine whether any migration is pending **before** opening the window, and SHALL
leave the pipeline otherwise unchanged when none is. A deploy that changes no schema SHALL cost one publish
and no window.

The pending check SHALL distinguish **no migrations pending**, **migrations pending**, and **the check
failed** as three outcomes, and SHALL treat the third as fatal. Collapsing a failed check into "none
pending" would publish the new bundle onto an un-migrated store — the exact outcome this requirement
exists to prevent.

The maintenance flag SHALL reach the bundle as **resolved configuration shipped inside it** (capability
`deployment-configuration`), never as an Edge Script environment value: CI holds only the script-scoped
deploy key and cannot write the script's environment, so what code is published is the only lever it has.

#### Scenario: A deploy with no pending migration opens no window

- **WHEN** the pending check reports that every migration is already applied
- **THEN** the workflow publishes once and probes once, with no maintenance bundle and no window

#### Scenario: A migrating deploy gates the window on the maintenance bundle serving

- **WHEN** a migration is pending
- **THEN** the maintenance bundle is published and observed serving before any migration statement runs

#### Scenario: A failed pending check fails the run

- **WHEN** the pending check exits with any outcome other than "none pending" or "pending"
- **THEN** the run fails without publishing, rather than proceeding as if no migration were pending

#### Scenario: The window is lifted only by publishing the real bundle

- **WHEN** the migration succeeds
- **THEN** the real bundle is published and observed serving with the maintenance flag clear

### Requirement: Every successful deploy archives its bundle, and a failed migrating deploy republishes the previous one

Each successful deploy SHALL archive the published bundle, keyed by the commit it was built from, in a
store **independent of the deploy target**. A migrating deploy SHALL capture the commit currently serving
before opening the window, and on failure SHALL republish that commit's archived bundle.

Before this, the pipeline had no rollback at all: bunny's release re-publish needs the account key, which
CI is forbidden to hold (measured — the deploy key answers `401` on the release endpoints). Without an
archive, a failed migrating deploy would leave the maintenance bundle live with nothing able to lift it.

The archive SHALL NOT live in the storage zone. `BUNNY_STORAGE_ACCESS_KEY` SHALL NOT be granted to the
deploy workflow (see "Deploy with secret-held, script-scoped credentials"): that key owns the zone holding
every user's photos, and the deploy path already reaches the relational store. Independence also matters on
its own terms — a rollback must work when the deploy target is the thing misbehaving.

There SHALL be no rebuild fallback. When the archived bundle for the captured commit is absent or expired,
the workflow SHALL fail **loudly, naming that commit**, leaving a bounded manual recovery rather than an
unexplained outage.

#### Scenario: A failed migration lifts the window by republishing

- **WHEN** the migration fails while the maintenance bundle is live
- **THEN** the workflow republishes the archived bundle for the commit that was serving before the window
  opened

#### Scenario: A missing archive fails loudly

- **WHEN** the archived bundle for the captured commit cannot be retrieved
- **THEN** the run fails naming that commit, rather than leaving the API serving maintenance silently

#### Scenario: The deploy workflow holds no storage credential

- **WHEN** the archive is written or read
- **THEN** no storage-zone credential is used, and none is present in the deploy workflow

### Requirement: The maintenance guarantee is bounded by what one probe can witness

The guarantee this pipeline provides SHALL be stated as **"very likely no request met a bundle whose
schema assumptions did not match the store"**, and SHALL NOT be stated as a certainty.

The reason is structural: the probe observes the device-facing origin, which resolves to **one** of the
runtime's points of presence. Nothing available to CI observes the others, and the platform publishes no
propagation contract — its own statements range from seconds to minutes. A future
measurement — observing how long the origin reports a split bundle identity from several networks — is what
would tighten it.

Relatedly, `migrate()` is atomic **per migration**, not per run. A run applying several migrations in which
a later one fails leaves the store at an intermediate version that no bundle is written against. This is
**roll-forward only**, repaired by hand, and SHALL be stated rather than implied.

#### Scenario: The residual is documented, not implied

- **WHEN** the maintenance window's guarantee is described
- **THEN** it names the single-observation limit rather than claiming every request was covered

#### Scenario: A partially applied run is a manual repair

- **WHEN** a run applies one migration and a later one fails
- **THEN** the store remains at the intermediate version and is repaired by rolling forward, not by the
  pipeline

## MODIFIED Requirements

### Requirement: Deploy is gated on a post-publish boot probe

After publishing on `main`, the workflow SHALL probe the device-facing origin until it observes the bundle
it just deployed, and SHALL fail the run otherwise. This exists because `POST /code` + `POST /publish`
succeed whether or not the deployed bundle can boot, so a green deploy step is **not** evidence that the
script serves.

The probe SHALL be satisfied only by a response that identifies **the bundle this run deployed**. A bare
success is insufficient: it cannot distinguish the new deployment from the previous one still being served,
which is the failure the probe exists to catch.

Because the maintenance bundle and the real bundle are built from the **same commit**, bundle identity
alone no longer distinguishes them. The probe SHALL therefore also be told which **maintenance state** it
expects, and SHALL be satisfied only by a response reporting that state: the probe before a migration
expects the window **open**, and the probe after the publish that lifts it expects the window **closed**.
Without the second assertion a run that failed to lift the window would report success.

The probe SHALL additionally witness that the deployed bundle can reach both of its dependencies — the
relational store and the storage zone. Storage reachability is new coverage, and it closes the half of the
2026-07 outage this probe previously could not see: a `BUNNY_STORAGE_ZONE` that is present but names a zone
that does not exist boots and probes green.

The probe SHALL retry only causes that time can resolve — a connection failure, a server error, a
not-found, a response identifying a *different* bundle, or a response reporting the wrong maintenance state
— up to a bounded deadline, and SHALL fail **immediately** on causes that waiting cannot fix, naming which.
Retrying a terminal cause until a deadline turns a specific bug into a timeout.

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

#### Scenario: The right bundle in the wrong maintenance state does not satisfy the probe

- **WHEN** the origin serves this run's bundle but reports a maintenance state other than the one expected
- **THEN** the probe keeps retrying rather than passing

#### Scenario: A run that fails to lift the window fails

- **WHEN** the final probe observes this run's bundle still reporting the window open
- **THEN** the run fails rather than reporting success with the API serving maintenance

#### Scenario: An unreachable dependency fails the deploy

- **WHEN** the deployed bundle cannot reach its relational store or its storage zone
- **THEN** the probe fails the run rather than leaving a deployment that accepts requests it cannot serve
  or record

### Requirement: A health route reports the deployed bundle's identity

The backend SHALL serve an **unauthenticated** health route at the **root**, answering `GET` and `HEAD`
only, and carrying the listings' no-cache directives so the pull zone cannot answer from a previous
deployment's copy.

On success it SHALL report the identity of the bundle serving it, and SHALL report that the maintenance
window is open **when, and only when, it is**. Both facts are needed because the two bundles a migrating
deploy publishes carry the **same** identity, so identity alone cannot tell them apart.

The window's absence from the response SHALL mean the window is **closed**. This collapse is deliberate and
it is safe because every cause it absorbs is the same answer: a bundle whose flag is off, and a bundle
built before the flag existed — which was built before maintenance mode existed at all, and is therefore
necessarily serving the device API. Nothing else can produce the absence, because a response that is not
this backend's fails the identity check first.

The route SHALL verify that the bundle can reach both of its dependencies — the relational store and the
storage zone — and SHALL answer a **non-success status** when either is unreachable. Distinguishing a
retryable cause from a terminal one is no longer the body's job: the only dependency condition it now
reports is unreachability, which is retryable, and a non-success status already carries that.

The route SHALL NOT require authentication. Serving it unauthenticated is accepted although it now performs
real upstream work, because the alternative is worse in kind: the probe carries no credential the backend
accepts, so any route it can reach is equally ungated, and moving the checks elsewhere would relocate the
exposure rather than remove it. What it discloses remains only an identifier that is already public.

#### Scenario: The health route is served without a token

- **WHEN** the health route is requested with no bearer token
- **THEN** it is answered successfully, carrying the identity of the bundle serving it

#### Scenario: The health route reports an open maintenance window

- **WHEN** the maintenance bundle is serving
- **THEN** the health route reports that the window is open, alongside the bundle's identity

#### Scenario: A response that does not mention the window means it is closed

- **WHEN** the health route's response carries no window field
- **THEN** the probe reads the window as closed, rather than as an answer it cannot interpret

#### Scenario: An unreachable dependency is a non-success status

- **WHEN** the relational store or the storage zone cannot be reached
- **THEN** the health route answers a non-success status rather than a success carrying a state description

#### Scenario: The health route is not cached by the pull zone

- **WHEN** the health route is requested through the pull zone after a new bundle is published
- **THEN** the response is not served from a previous deployment's cached copy

#### Scenario: A mutating method is not served

- **WHEN** the health route is requested with a method other than `GET` or `HEAD`
- **THEN** it is not served by that route
