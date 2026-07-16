## ADDED Requirements

### Requirement: The event-link domain agrees across the app and the backend

A test-only JVM guard SHALL assert that the event link's domain agrees across every place it appears:
the app's `applinks:` associated-domains entitlement, the app's `LINK_ORIGIN` constant, the Apple App
Site Association document the backend serves, and the backend's own domain constant (capability
`event-link`). No compiler and no module boundary can hold those four together.

Two of the four SHALL be **single-sourced** rather than merely guarded: `LINK_ORIGIN` SHALL be generated
from one Gradle property, and the entitlement's value SHALL be supplied from `Config.xcconfig`. The
backend's copy **cannot** be: `backend/` is a Deno tree deployed by a separate, path-scoped workflow that
ships code only and never config (capability `backend-deployment`), so nothing in the Gradle build can
reach it, and generating it would couple two deliberately independent pipelines. The guard therefore
exists to hold exactly the seam that single-sourcing cannot close.

The guard exists because drift here is **silent**. A stale entitlement or a mismatched AASA does not
raise, log, or fail a build: iOS simply declines to match the link, and every event link opens a browser
instead of the app — indistinguishable, from the outside, from a user who has not installed SnapSync.

The guard SHALL fail loudly rather than vacuously: if a file it inspects has moved or been renamed, it
SHALL fail rather than silently scanning nothing.

#### Scenario: A drifted domain fails the build

- **WHEN** any one of the entitlement's `applinks:` domain, the app's `LINK_ORIGIN`, the served AASA's
  domain, or the backend's domain constant names a different host than the others
- **THEN** the guard test fails, naming the disagreeing values

#### Scenario: The guard is not vacuous

- **WHEN** a file the guard inspects is absent, renamed, or no longer contains the marker it expects
- **THEN** the guard fails, rather than passing while inspecting nothing

#### Scenario: Agreeing domains pass

- **WHEN** all four locations name the same host
- **THEN** the guard passes
