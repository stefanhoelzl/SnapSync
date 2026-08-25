## MODIFIED Requirements

### Requirement: Participation direction is a selection input on the policy

The membership's participation **direction** SHALL be an input to the selection policy, alongside the
capture-date range and the origin exclusions. The policy answers *what does this member contribute?* — the
range bounds **when** a photo was taken, the origin exclusions bound **what it is**, and the direction bounds
**whether at all**. A `DownloadOnly` membership contributes the **empty set**.

All three SHALL be carried to every policy consumer as one already-decided value, `SelectionPolicy`, defined
in `:domain`'s `model/` zone (package `app.snapsync.model` — the only zone visible to every consumer,
`feature/upload` and `feature/status` being mutually blind). It is the rules, not the inputs from which rules
could be derived: a consumer receives the decision, never the material to re-decide (see *The admitted set is
a single derivation every consumer receives*).

`SelectionPolicy` SHALL be **a conjunction of selection rules and nothing else**: it carries a list of rules
and answers `admits(facts)` as "every rule admits". It SHALL NOT special-case any individual rule, and it
SHALL NOT itself assert that any particular rule is present. The direction is expressed **as a rule**: a
membership that contributes nothing carries the `DenyAll` rule, which admits no asset.

This replaces the prior two-variant formulation (a non-contributing variant carrying no rules, and a
contributing variant carrying the capture-date lower bound as a non-null field). The invariant that formerly
lived in the type now lives at the **single derivation** (see *One derivation builds a membership's rule
list*): because the persisted membership's lower-bound field is non-null, the derivation always emits the
lower-bound rule, so a contributing membership always carries its floor.

`SelectionPolicy` SHALL be a **required** argument on every consumer, with **no default value**. This is a
privacy requirement, not an ergonomic one: there SHALL be no value, and no absent-argument fallback, under
which a membership admits the whole library. A default is prohibited in both polarities: a permissive
default admitting every capture date uploads the entire library from the beginning of time, and a
fail-closed default makes a contributing member silently share nothing while the screen reads "In sync" —
the invisible failure this capability exists to prevent.

The policy SHALL NOT expose an accessor that answers "what is the capture floor" with an absent value. Such
an accessor collapses "this membership contributes nothing" and "this policy has no floor" into one answer
whose two causes have opposite consequences, and it invites a consumer to branch on the floor before checking
the direction. A consumer needing a capture-date bound in order to scope a platform query SHALL take it from
the **membership** it already holds, not from the policy.

The direction SHALL be resolved **before** any port read the derivation would otherwise perform: a
non-contributing membership SHALL NOT pay a download-store read or a platform album lookup in order to learn
that it contributes nothing.

#### Scenario: A download-only membership contributes the empty set
- **WHEN** the membership's participation direction excludes upload
- **THEN** the selection policy admits no asset, regardless of any asset's capture date or origin

#### Scenario: Contributing nothing is expressed as a rule
- **WHEN** a membership contributes nothing
- **THEN** its rule list carries the `DenyAll` rule, and `admits` returns false for every asset because that
  rule is part of the conjunction — not because the policy is a distinct kind of value

#### Scenario: The derivation always emits the capture floor
- **WHEN** the single derivation builds the rule list for a contributing membership
- **THEN** it emits the capture-date lower-bound rule from the membership's persisted lower bound, which is
  non-null, so no contributing rule list produced by the derivation lacks a floor

#### Scenario: No accessor offers an absent floor
- **WHEN** a consumer needs a capture-date bound to scope a platform query
- **THEN** it takes that bound from the membership it already holds, and the policy offers no accessor whose
  absent value both the contributing and non-contributing cases could produce

#### Scenario: A non-contributor pays no I/O to learn it contributes nothing
- **WHEN** the rule list is derived for a membership whose direction excludes upload
- **THEN** neither the download-store read nor the platform album lookup is performed

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
projection of the ledger's `COMPLETED` rows (capability `device-manifest`). The capture-date bounds SHALL be
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
  projected from the ledger's `COMPLETED` rows — admitted by the same membership policy — lists only the
  in-range camera photo, so no consumer downstream can re-derive a different set

### Requirement: The policy scopes the own-device status total

The own-device upload **total** `N` SHALL count exactly the assets in the membership's **admitted set** —
the same set the upload cycle admits — and SHALL NOT re-apply the policy's rules independently. `N` SHALL
respect the capture-date **range** (both bounds), the origin exclusions, the echo suppression, and the
album denylist by deriving from that set, so an asset the policy excludes never counts toward `N`.
Counting an excluded asset would peg completeness permanently below 100% and hold the screen at "pending"
forever — which is the concrete failure a floor-only `N` produced.

A **non-contributing** membership — one whose rule list carries the deny-everything rule — SHALL report
`N = 0`. It reaches that answer through the same admission as every other, and the platform query is
narrowed to match no asset (capability `gallery-status`), so no per-asset round-trip is paid for it.

#### Scenario: N counts the admitted set, ceiling included

- **WHEN** the device holds photos both within and after the membership's capture-date range
- **THEN** `N` counts only those within the full range `[cutoff, until]` — a post-ceiling photo is not
  counted, so completeness can reach 100%

#### Scenario: N derives from the same set as upload

- **WHEN** the upload cycle admits a set and `N` is computed
- **THEN** `N` equals the size of that admitted own-device set, not a separately-filtered count

## ADDED Requirements

### Requirement: One derivation builds a membership's rule list

There SHALL be exactly **one** derivation from a membership to a selection-policy rule list. It SHALL take
the persisted membership and the two effectful exclusion sources — the download store's imported-asset ids
(echo suppression) and the platform album lookup (denylisted-album membership) — and return the complete
rule list. There SHALL NOT be a second construction step that completes a partially-built policy, because a
partially-built policy is a value a consumer can hold and act on.

Rule construction MAY be asynchronous, because two of the rules are read from ports. **Policy** construction
SHALL NOT be: a policy is a value over an already-finished rule list.

The derivation SHALL resolve the participation direction **first**, and SHALL return the deny-everything rule
without consulting either exclusion source when the direction excludes upload.

The derivation SHALL always emit the capture-date lower-bound rule for a contributing membership, taken from
the membership's persisted lower bound. This is where the requirement *A lower bound `from` SHALL be
required* is enforced: the persisted field is non-null, so the derivation cannot produce a contributing rule
list without a floor.

Because the policy type no longer asserts the presence of the lower-bound rule, the system SHALL
mechanically enforce that a rule list reaching a consumer came from this derivation — by constructor
visibility, or by a build-gating check that no policy is constructed elsewhere. A rule list assembled by hand
is otherwise an unbounded scope, and an unbounded scope is the failure this capability exists to prevent.

#### Scenario: A contributing membership's rule list always carries its floor
- **WHEN** the derivation runs for a membership whose direction includes upload
- **THEN** the returned rule list contains the capture-date lower-bound rule built from the membership's
  persisted lower bound

#### Scenario: The direction is resolved before any port read
- **WHEN** the derivation runs for a membership whose direction excludes upload
- **THEN** it returns the deny-everything rule and neither exclusion source is consulted

#### Scenario: Completing a policy in a second step is not possible
- **WHEN** a consumer holds a selection policy
- **THEN** that policy is complete — there is no operation that adds further rules to an existing policy, so
  no consumer can hold or act on a partially-built one

#### Scenario: A policy built outside the derivation is rejected
- **WHEN** a selection policy is constructed anywhere other than the single derivation
- **THEN** the build fails, so a hand-assembled rule list with no capture floor cannot reach a consumer
