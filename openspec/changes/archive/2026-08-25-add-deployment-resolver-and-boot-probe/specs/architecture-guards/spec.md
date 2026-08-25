## MODIFIED Requirements

### Requirement: The event-link domain agrees across the app and the backend

The event link's domain SHALL be **single-sourced from one resolved deployment** (capability
`deployment-configuration`) in every place it appears: the app's `applinks:` associated-domains
entitlement, the app's `LINK_ORIGIN` constant, the Apple App Site Association document the backend serves,
the compile-time device-facing upload host, and the browser-facing site's canonical URLs.

This supersedes the previous position that the backend's copy **cannot** be single-sourced. That reasoning
held only while `api/` was reachable by nothing but a code-only deploy pipeline: with the domain resolved
from a deployment that every toolchain reads, generating each copy no longer couples two pipelines — it
gives them one shared input. The guard's own purpose said as much, that single-sourcing is preferable and
the guard existed only for the seam it could not close.

Two consequences follow. Agreement is no longer *asserted* across hand-written literals but *constructed*,
so a copy cannot drift. And the guarantee now reaches copies the previous guard never inspected — the
compile-time upload host and the site's canonical URLs were both unpinned.

A test-only JVM guard SHALL remain, reduced to a **staleness check**: it SHALL assert that each generated
artifact matches the deployment it derives from, and SHALL fail loudly rather than vacuously — if a file it
inspects has moved, been renamed, or no longer contains the marker it expects, it SHALL fail rather than
silently scanning nothing.

The guard exists because drift here is **silent**. A stale entitlement or a mismatched AASA does not
raise, log, or fail a build: iOS simply declines to match the link, and every event link opens a browser
instead of the app — indistinguishable, from the outside, from a user who has not installed SnapSync.

#### Scenario: A stale generated artifact fails the build

- **WHEN** a generated artifact carrying the domain no longer matches the deployment it derives from
- **THEN** the guard test fails, naming the artifact and the disagreeing values

#### Scenario: Every copy is constructed, not restated

- **WHEN** the entitlement's `applinks:` domain, the app's `LINK_ORIGIN`, the served AASA's domain, the
  compile-time upload host, and the site's canonical URLs are inspected
- **THEN** each is derived from the resolved deployment, and none is a hand-written host literal

#### Scenario: The guard is not vacuous

- **WHEN** a file the guard inspects is absent, renamed, or no longer contains the marker it expects
- **THEN** the guard fails, rather than passing while inspecting nothing

#### Scenario: Agreeing artifacts pass

- **WHEN** every generated artifact matches the resolved deployment
- **THEN** the guard passes
