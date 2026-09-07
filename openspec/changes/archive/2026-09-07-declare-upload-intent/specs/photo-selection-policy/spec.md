## MODIFIED Requirements

### Requirement: Selection filter over the shared upload cycle

The shared upload cycle SHALL drop from byte upload every discovered resource that the selection policy does
not admit — whose owning asset's `creationDate` **precedes the applicable lower bound**, whose owning
asset's `creationDate` **exceeds the applicable upper bound**, **or** which any origin exclusion rejects —
**before the resource reaches the ledger/engine**. The capture-date test SHALL be the **inclusive range**
`from <= creationDate <= until`: the lower bound admits at or after `from`, and the upper bound admits at or
before `until` (inclusive). Both comparisons SHALL be plain **lexicographic** compares over the canonical
`yyyy-MM-dd'T'HH:mm:ss'Z'` second-precision shape (see *Cutoff string format invariant*), so a differing
shape on either bound compares incorrectly. The filter SHALL be applied to **both** the full enumeration and
the incremental change-token walk, and SHALL be **tier-agnostic** (it governs the OS-driven PhotoKit
extension tier and the app-driven `URLSession` tier alike, since both funnel through the shared cycle). The
applicable lower bound SHALL be expressed as the **minimum** lower bound across the device's current
memberships — so a photo is uploaded when it is in scope for **at least one** joined event — which in v1
(single membership) reduces to that membership's single lower bound. The applicable lower bound is always
non-null. The engine and ledger SHALL remain policy-blind; the exclusion happens entirely in the cycle's
resource selection.

The filter in the cycle's resource selection SHALL remain the **authoritative** exclusion, and SHALL live in
the **platform-free upload-cycle core**, not in untested platform wiring, so it is exercised in `commonTest`.

A platform enumeration MAY additionally narrow its fetch as an optimization. That narrowing SHALL be derived
by **translating the policy's own rules** — the platform receives the policy and pattern-matches the rules it
can express into its native query — rather than by re-stating a bound the caller flattened for it. Rules the
platform cannot express SHALL simply not be translated; an untranslated rule costs performance only, never
correctness, because the authoritative filter runs over whatever the fetch returns. **A platform fetch can
never widen or narrow the admitted set.** Because the rule set is a sealed type, adding a rule SHALL force
each platform translator to state explicitly whether it can express it.

Two narrowings are **required** rather than advisory, and both are **liveness** properties of the walk rather
than correctness properties of admission — every rule is equally load-bearing for what is admitted, but an
unbounded walk is watchdog-killed before the authoritative filter ever runs:

- The capture-date **lower bound** SHALL be pushed into the platform query.
- The **deny-everything** rule SHALL be translated into a query that matches **no** asset, so a
  non-contributing membership's enumeration returns nothing rather than the whole library. That translation
  SHALL be built from a comparison that is simply never satisfiable on a key the platform is known to
  evaluate correctly; it SHALL NOT rely on any query form whose emptiness is an artefact of the platform's
  parser rather than its semantics, because such a form would begin admitting the whole library if the
  platform ever evaluated it correctly.

The origin exclusions SHALL be applied before a resource reaches the ledger, so an origin-excluded asset
never gains a ledger row and therefore cannot appear in any device manifest — the manifest being a
projection of the ledger's rows whatever their upload state, so a row that does not exist is the only
way an asset can be absent from it (capability `device-manifest`). The capture-date bounds SHALL be
applied at **projection** time, against the membership's own policy, exactly as every other consumer applies
it. The projection SHALL receive the **policy**, not the inputs from which one could be derived, and SHALL
NOT take the ledger's contents for the admitted set.

#### Scenario: Pre-lower-bound resources never reach the engine

- **WHEN** the cycle discovers a resource whose asset `creationDate` precedes the lower bound `from`
- **THEN** the resource is dropped before the engine, so no upload job is created and the ledger gains no entry for it

#### Scenario: Post-upper-bound resources never reach the engine

- **WHEN** the cycle discovers a resource whose asset `creationDate` exceeds the upper bound `until`
- **THEN** the resource is dropped before the engine, so no upload job is created and the ledger gains no entry for it

#### Scenario: A resource captured exactly at the upper bound is admitted

- **WHEN** the cycle discovers a resource whose asset `creationDate` equals the upper bound `until` (and is
  at or after `from` and origin-admitted)
- **THEN** it is admitted, because the upper bound is inclusive (`creationDate <= until`)

#### Scenario: Origin-excluded resources never reach the engine

- **WHEN** the cycle discovers a resource whose owning asset an origin rule rejects
- **THEN** the resource is dropped before the engine, so no upload job is created and the ledger gains no
  entry for it

#### Scenario: The filter covers the incremental walk

- **WHEN** the incremental change-token walk surfaces a changed asset the policy does not admit
- **THEN** that asset is excluded, exactly as in the full enumeration

#### Scenario: The admitted set is the minimum across memberships

- **WHEN** the device has memberships with lower bounds `C1` and `C2`
- **THEN** a resource is admitted for upload when its `creationDate >= min(C1, C2)` (in v1 this is the single membership's lower bound)

#### Scenario: A platform fetch narrowed by date or origin does not change the admitted set

- **WHEN** the platform enumeration returns a superset of the admitted assets (for example because its
  predicate was deliberately widened, or because it cannot express an exclusion the policy makes)
- **THEN** the cycle's filter still excludes every non-admitted resource, so the admitted set is identical to
  that of an unnarrowed fetch

#### Scenario: A deny-everything policy narrows the platform query to nothing

- **WHEN** a full platform enumeration is performed for a membership whose rule list carries the
  deny-everything rule
- **THEN** the query returns no asset, so no per-asset platform round-trip is paid to reach the empty
  admitted set

#### Scenario: The deny-everything translation does not rest on a parser artefact

- **WHEN** the deny-everything rule is translated into a platform query
- **THEN** the query is an unsatisfiable comparison on a key the platform evaluates correctly, so a platform
  fixing an unrelated parser defect can never turn that query into one that matches every asset

#### Scenario: A new rule forces a translation decision

- **WHEN** a new selection rule is added to the sealed rule set
- **THEN** each platform translator fails to compile until it states explicitly whether it can express that
  rule, so a rule can never be silently left out of the narrowing

#### Scenario: The manifest lists only the admitted set

- **WHEN** a cycle discovers a screenshot, a pre-lower-bound camera photo, and an in-range camera photo
- **THEN** neither the screenshot nor the pre-lower-bound photo gains a ledger row, and the manifest
  projected from the ledger's rows — admitted by the same membership policy — lists only the in-range
  camera photo, so no consumer downstream can re-derive a different set
