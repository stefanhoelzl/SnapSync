## ADDED Requirements

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

Raising the minimum disables every older install at once. That is the single most consequential value in
this configuration, and it SHALL therefore be changeable only by a change to source — a pull request, a
review and a deploy. A value held in the database would let the install base be disabled without any of
them.

The current value SHALL be pinned by a test, so a change to it appears in a diff rather than inside a
configuration object.

#### Scenario: The minimum is not runtime-mutable

- **WHEN** the configured minimum is resolved
- **THEN** it comes from source configuration, and no database or runtime store can alter it

#### Scenario: Changing the minimum is visible in review

- **WHEN** the minimum version is raised
- **THEN** the pinning test fails until it is updated, so the change is explicit in the diff

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
