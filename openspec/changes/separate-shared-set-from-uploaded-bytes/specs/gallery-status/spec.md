## ADDED Requirements

### Requirement: A deny-everything policy narrows the platform fetch to nothing

The platform read seam SHALL translate a policy that admits no asset into a native query that returns **no**
asset, rather than issuing an unnarrowed fetch and relying on the caller's in-memory admission to discard
every result.

This is a **liveness** requirement, matching the one that already forces the capture-date lower bound into
the query. The whole-library enumeration — a cold start with no discovery cursor — is the path that carries
a predicate, and it is the path where an unnarrowed fetch costs one synchronous platform round-trip per
asset. Without this translation a membership that contributes nothing would pay a full library walk on
every cold start to arrive at the empty set its own configuration already stated.

The translation SHALL be built from a comparison that is never satisfiable on a key the platform is known
to evaluate correctly. It SHALL NOT be built from any query form whose emptiness is an artefact of the
platform's query parser rather than its semantics: such a form returns nothing only for as long as the
platform continues to mis-evaluate it, and would begin returning the **entire library** if the platform ever
evaluated it correctly — the worst possible direction for a membership that shares nothing.

Correctness SHALL NOT depend on this translation. The caller's in-memory admission remains authoritative, so
an untranslated deny-everything rule costs a full walk and never a wrong admitted set — consistent with
every other narrowing being an optimization only.

The paths that carry **no** predicate — the incremental change-feed walk, which fetches by identifiers the
feed supplied, and the partial-grant selection observer, which holds an already-fetched result — are bounded
by construction and SHALL NOT require a separate short-circuit.

#### Scenario: A cold-start enumeration for a non-contributing membership returns nothing
- **WHEN** a whole-library enumeration is performed for a membership whose policy admits no asset
- **THEN** the native query returns no asset, so no per-asset platform round-trip is paid

#### Scenario: The translation does not rest on a parser artefact
- **WHEN** the deny-everything rule is translated into a native query
- **THEN** the query is an unsatisfiable comparison on a key the platform evaluates correctly, so a platform
  release that corrects an unrelated query-parser defect cannot turn it into a query matching every asset

#### Scenario: An untranslated deny-everything rule is slow, not wrong
- **WHEN** a platform cannot express the deny-everything rule in its native query
- **THEN** the enumeration returns assets and the caller's admission rejects all of them, so the admitted
  set is still empty

#### Scenario: The predicate-less paths need no short-circuit
- **WHEN** the incremental change-feed walk or the partial-grant selection observer supplies candidates for
  a membership whose policy admits no asset
- **THEN** the candidates are rejected by the caller's admission, and no additional gate is required,
  because both paths are already bounded to a change delta or a hand-picked selection

### Requirement: The count for a non-contributing membership costs no per-asset read

The own-device status total and the join-time shareable-count preview SHALL both report **zero** for a
membership whose policy admits no asset, and SHALL reach that answer without paying a per-asset platform
round-trip.

The requirement is stated as an outcome rather than as a mechanism, because either a caller-side
short-circuit or a native query that returns nothing satisfies it. What SHALL NOT satisfy it is walking the
library asset by asset to discover that none is admitted.

#### Scenario: The status total is zero without a per-asset walk
- **WHEN** the own-device status total is computed for a membership whose policy admits no asset
- **THEN** it reports zero, and no per-asset platform round-trip is paid

#### Scenario: The shareable-count preview is zero without a per-asset walk
- **WHEN** the join-time preview is computed with sharing off
- **THEN** it reports zero, and no per-asset platform round-trip is paid
