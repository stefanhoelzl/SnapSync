## ADDED Requirements

### Requirement: The client declares its version on every versioned request

Every request the device issues to a version-gated route SHALL carry the calling build's **marketing
version** in the header the gate reads. The declaration SHALL be applied at a **single point per transport**
rather than at each call site, so a route added later inherits it and cannot be forgotten.

There are exactly **two** transports, and both SHALL declare it:

- the **shared HTTP client**, which carries every metadata seam — event creation, event details, join,
  manifest publish, the per-device listing, the event union, push registration, leave — and the ungated
  attest bootstrap; and
- the **composed byte-upload request**, because the platform's background-upload subsystem issues it later,
  outside any client this app controls. A header added by the shared client cannot reach a request the OS
  performs, so this one is set where the request is composed (capability `edge-upload-provider`).

#### Scenario: Metadata requests declare the version

- **WHEN** any request is issued through the shared HTTP client
- **THEN** it carries the app-version header, including the attest requests that carry no token

#### Scenario: The OS-performed upload declares the version

- **WHEN** the byte-upload request is composed and handed to the platform to perform
- **THEN** the request itself carries the app-version header

#### Scenario: A route added later inherits the declaration

- **WHEN** a new seam is added over the shared HTTP client
- **THEN** it declares the version without any change at its call site

### Requirement: A refusal is surfaced to the user as an actionable state

A `426` refusal SHALL be recognised as a **distinct, permanent** outcome and SHALL NOT be absorbed into a
generic non-2xx failure. It SHALL be detected at the **one** place the transport already inspects responses
for a credential refusal, so every seam is covered by construction rather than per call site.

The refusal SHALL reduce to a **top-level** user-facing state carrying the minimum version from the
response body — not a variant of the sync-health state. An obsolete build can neither create an event, join
one, nor sync, because the gate precedes the token check and applies to every route; a state nested inside
"joined and syncing" could not represent that.

The state SHALL clear on the next successful response, so a device recovers automatically once the user
updates, with no restart and no durable flag to reconcile.

The refusal SHALL be reported at a severity that reaches crash reporting, because it is permanent: unlike a
transport failure it will not heal by retrying, and a device in this state performs no upload and no
download until the app is updated.

**Known coverage gap, accepted:** the byte-upload request is performed by the OS and never passes through
the shared client, so a `426` on it surfaces as an upload failure rather than as this state. The metadata
seams run every cycle and detect the condition within seconds, so the state is reached regardless; this is
recorded so the gap is known rather than discovered.

#### Scenario: A refusal reduces to the update state

- **WHEN** any request through the shared client is refused `426`
- **THEN** the UI reduces to the top-level update-required state, carrying the minimum version the response
  named

#### Scenario: The refusal is not mistaken for a transient failure

- **WHEN** a request is refused `426`
- **THEN** it is not reported as a generic failed request, and the condition is recorded at a severity that
  reaches crash reporting

#### Scenario: Updating clears the state without a restart

- **WHEN** a device in the update-required state issues a request that succeeds
- **THEN** the state clears and the app resumes its normal surface

#### Scenario: The state supersedes the others

- **WHEN** the device is refused `426` while joined to an event
- **THEN** the update-required state is shown rather than a sync-health variant, because create, join and
  sync are all refused

## MODIFIED Requirements

### Requirement: The minimum lives in source, and raising it costs a review

The minimum version SHALL be part of the application's non-secret configuration **in source**, alongside
the other non-secret values, and SHALL NOT be read from a mutable runtime store.

Raising the minimum disables, at once, every install that speaks the gated version. While an earlier
unGated version is still served, that set is only the builds that speak the gated one — so raising the
minimum to the first version that speaks it refuses nothing that exists. That distinction SHALL NOT be
relied on once the earlier version is withdrawn, at which point the blunt reading becomes true again. It is
in either case the single most consequential value in this configuration, and it SHALL therefore be
changeable only by a change to source — a pull request, a review and a deploy. A value held in the database
would let the install base be disabled without any of them.

The minimum SHALL NOT exceed the **marketing-version floor** the client builds carry when no version is
computed for them. Development and sideload builds bake that floor rather than a release-derived version,
so a minimum above it refuses every such build — including the ones used to test the gated version on a
device, against a local backend as well as a deployed one, since the minimum is a single source constant
shared by every deployment. The relationship SHALL be asserted mechanically, so that **raising the minimum
requires raising the floor** is a build failure rather than folklore. Asserting the minimum against the
*computed* release version instead is insufficient: it admits a minimum that breaks every development
build while passing.

The current value SHALL be pinned by a test, so a change to it appears in a diff rather than inside a
configuration object.

#### Scenario: The minimum is not runtime-mutable

- **WHEN** the configured minimum is resolved
- **THEN** it comes from source configuration, and no database or runtime store can alter it

#### Scenario: Changing the minimum is visible in review

- **WHEN** the minimum version is raised
- **THEN** the pinning test fails until it is updated, so the change is explicit in the diff

#### Scenario: Raising the minimum to the first gated-version build refuses nothing

- **WHEN** the minimum is raised to the first released version that speaks the gated API version, while the
  earlier version is still served
- **THEN** every existing install continues to be served, because none of them speak the gated version

#### Scenario: A minimum above the build floor fails the build

- **WHEN** the minimum is set above the marketing-version floor that development and sideload builds bake
- **THEN** the assertion fails, rather than shipping a configuration that refuses every development build
