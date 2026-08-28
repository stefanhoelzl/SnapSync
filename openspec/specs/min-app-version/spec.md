# min-app-version Specification

## Purpose

The version a request must declare to be served, how a request that is too old is refused so the answer is
**actionable** rather than ambiguous, and where the minimum lives so raising it costs a review.

Until this capability there was no refusal a client could act on. An obsolete build met the same `401`,
`404` or `502` a transient failure produces, so it retried forever and reported nothing — the user saw an
app that quietly did not work, with no sentence anywhere telling them the remedy was to update. A backend
that serves more than one API version needs the opposite: a build that can no longer be served must be
told **so**, distinguishably, and told **what to install**.

The gate is therefore three small commitments held together. Every `/api/v2` request declares the calling
build's **marketing version** — the only version a user can act on — in a header, on every route including
the ungated attest bootstrap, so an obsolete build learns it is obsolete at first contact rather than one
call later. A version below the configured minimum is refused **`426`** with the required minimum in the
body: a status that cannot be mistaken for an authentication failure or a transient upstream error, and a
body that lets the client name the version to install. And the check runs **before** the device-token gate
— a deliberate inversion of the token-first rule, safe because it reads nothing the token gate protects
(no storage, no database, no user data), and necessary because an old build holding an expired token would
otherwise be told it has an authentication problem when its actual remedy is to update the app.

Two smaller commitments keep the gate from lying. Versions compare **numerically, part by part**, because
string ordering places `0.10` below `0.9` and would silently invert the gate at the tenth release. And the
minimum lives **in source** alongside the other non-secret configuration, never in a mutable runtime store,
because raising it disables every older install at once — the single most consequential value in this
configuration, and one that SHALL cost a pull request, a review and a deploy.

Decision record: `changes/archive/add-v2-device-api` (D11, D12).

## Requirements
### Requirement: Every v2 request declares the app version

Every request to a `/api/v2` route SHALL carry the calling build's **marketing version** in a request
header. The header SHALL be required on **every** v2 route, including the ungated attest bootstrap.

The marketing version is chosen over the build number deliberately. The build number is monotone and
unique per build, which makes it the better sort key — but every build between two releases shares one
marketing version, and the marketing version is the only version a user can act on. This gate exists so a
client can tell its user which version to install; a build number cannot serve that sentence.

Covering the attest routes is likewise deliberate: an obsolete build that can still mint a token discovers
it is obsolete only on its next call, which is a worse first contact than being told immediately.

#### Scenario: A current build is served

- **WHEN** a v2 request carries a version header at or above the configured minimum
- **THEN** the request proceeds to its route

#### Scenario: The attest bootstrap is version-checked too

- **WHEN** a v2 attest request carries a version below the minimum
- **THEN** it is refused by this gate, even though the route requires no token

### Requirement: A too-old request is refused with an actionable answer

A v2 request declaring a version below the configured minimum SHALL be refused with **`426`**, and the
response body SHALL carry the **required minimum version**.

The status is chosen to be unmistakable: it must not be confusable with an authentication failure or a
transient upstream error, because the entire value of this gate is that the refusal is *distinguishable*.
A client that cannot tell "you are too old" from "try again later" retries forever and reports nothing,
which is the failure this capability exists to prevent.

Carrying the minimum in the body is what makes the refusal actionable rather than merely legible — the
client can name the version to install rather than saying only that something is wrong.

#### Scenario: A too-old build is told what to install

- **WHEN** a v2 request declares a version below the minimum
- **THEN** the response is `426` and its body carries the required minimum version

#### Scenario: The refusal is not an authentication failure

- **WHEN** a v2 request is refused for its version
- **THEN** the status is `426` and never `401`, whether or not the request carried a valid token

### Requirement: An absent or unreadable version collapses into too-old

A v2 request whose version header is **absent**, empty, or not parseable SHALL receive the same `426` as a
request declaring a version below the minimum.

This is a deliberate collapse of two causes into one answer, and it is safe for both: a request that
cannot state its version and a request that states one too old both mean the caller cannot be trusted to
speak v2, and in both cases the remedy is identical — install a build that can. No consequence
distinguishes them, so nothing is lost by giving them one answer.

#### Scenario: A missing header is refused like an old version

- **WHEN** a v2 request carries no version header
- **THEN** it is refused `426` with the required minimum

#### Scenario: An unparseable header is refused like an old version

- **WHEN** a v2 request carries a version header that is empty or not a valid version
- **THEN** it is refused `426` with the required minimum

### Requirement: Versions compare numerically, part by part

Version comparison SHALL parse the marketing version into its numeric parts and compare them **part by
part**. It SHALL NOT compare versions as strings.

String ordering places `0.10` **below** `0.9`, so a string comparison would admit builds the gate is meant
to refuse and refuse builds it is meant to admit — silently, and only after the tenth release. This
codebase already carries one bug of that family, recorded in the database module: `…+00:00` sorts before
`…Z` for the same instant.

The comparison SHALL be pinned by a test that includes at least one case where string and numeric ordering
disagree.

#### Scenario: A two-digit minor is ordered correctly

- **WHEN** the configured minimum is `0.9` and a request declares `0.10`
- **THEN** the request is served, because `0.10` is the later version

#### Scenario: An earlier version is refused

- **WHEN** the configured minimum is `0.12` and a request declares `0.11`
- **THEN** the request is refused `426`

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

### Requirement: The version check precedes authorization and touches nothing upstream

The version check SHALL run **before** the device-token gate, and SHALL make no storage, database, or
other upstream request.

This inverts the ordering rule that a gated route's token check comes first, and the inversion is
deliberate for three reasons. The check reads nothing the token gate exists to protect, so it cannot grow
the bill or reach user data — the same property that makes an unmatched path's `404` safe ahead of
authorization. A build below the minimum cannot be helped by a valid token, so verifying one first spends
work on a request that is refused either way. And an old build holding an expired token would otherwise be
told `401`, reporting an authentication problem to a user whose actual remedy is to update the app.

#### Scenario: A too-old request is refused without an upstream call

- **WHEN** a v2 request declares a version below the minimum
- **THEN** it is refused `426` and no storage or database request is made

#### Scenario: Version is decided before the token

- **WHEN** a v2 request carries both a below-minimum version and an invalid or expired token
- **THEN** the response is `426`, not `401`

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
