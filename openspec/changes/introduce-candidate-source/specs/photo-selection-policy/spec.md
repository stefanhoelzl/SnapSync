## REMOVED Requirements

### Requirement: Screenshots, screen recordings and animated images are excluded

**Reason**: The GIF clause is the **only** rule in the policy that cannot be decided from an asset's own
cheap properties — it reads the MIME content type of a *resource*, which costs a ~110 ms platform
round-trip per asset. That single rule is why admission needed a resource read at all, and therefore why
two consumers of "the admitted set" could disagree: the join preview walks facts-only, so it cannot see
the MIME and admits a GIF on doubt, while the status total walks eagerly, sees it, and excludes it. The
preview over-counts relative to `N` for any library holding an in-scope GIF — the same divergence class as
the capture-date ceiling bug.

Removing it makes every rule decidable on facts alone, so every consumer resolves the identical set
without reading a single resource, and resources are read only for assets already admitted.

**Migration**: The **3 megapixel image floor already excludes every ordinary GIF** — a Giphy or messenger
GIF is ~0.13 MP, and a Live-Photo→GIF export is downsized by the exporting app. What is no longer excluded
is an **edited** GIF (the floor is skipped for `hasAdjustments`) or one at **≥3 MP**; both are rare, and
both land on the side the policy already declares acceptable: *"a stray uploaded meme is harmless and
visible, while an event photo that silently fails to upload is invisible and unfixable."* The screenshot
and screen-recording exclusions are unchanged and restated below.

## ADDED Requirements

### Requirement: Screenshots and screen recordings are excluded

The policy SHALL exclude every asset whose `mediaSubtypes` carries the **screenshot** bit (`1 << 2`) or the
**screen-recording** bit (`1 << 19`).

Neither is a camera capture under any reading. Screenshots are the highest-frequency non-captured asset in a
typical library, and both bits are exact — this is the rule with perfect recall. Both are plain properties of
the asset, so the rule is decidable without reading any resource.

#### Scenario: A screenshot is excluded

- **WHEN** a discovered asset's `mediaSubtypes` has the screenshot bit set
- **THEN** it is excluded from upload and from the manifest, whatever its capture date

#### Scenario: A screen recording is excluded

- **WHEN** a discovered asset's `mediaSubtypes` has the screen-recording bit set
- **THEN** it is excluded from upload and from the manifest

#### Scenario: A camera photo carrying other subtypes is admitted

- **WHEN** a discovered asset carries only non-excluded subtype bits (for example panorama, HDR, live photo,
  or depth effect)
- **THEN** it is admitted — those subtypes are all camera captures

#### Scenario: An ordinary animated image is excluded by the resolution floor

- **WHEN** a messenger or Giphy GIF (well below 3 megapixels, unedited) is discovered
- **THEN** it is excluded by the image resolution floor, without any rule reading its resources

### Requirement: Admission is decidable on asset facts alone

Every selection rule SHALL be decidable from an asset's **neutral facts** — properties readable without a
per-asset platform resource round-trip — or from an identifier set supplied to the policy. No rule SHALL
require reading an asset's resources to decide whether it is admitted.

This is what makes the admitted set **one** set rather than a family of approximations. A rule needing a
resource read forces every consumer to choose between paying for it (expensive, and pointless for a count)
and admitting on doubt (cheap, and a different answer) — so the same policy yields different sets at
different consumers, which is the drift class the *single derivation* requirement exists to close.

It also inverts the cost of the walk: because admission is settled before any resource is read, resources
SHALL be read only for assets the policy has **already admitted**. The previous ordering read every
in-scope asset's resources and then discarded the excluded ones, paying the round-trip for exactly the
assets it was about to throw away.

#### Scenario: A count reads no resources

- **WHEN** any consumer resolves only the size of the admitted set
- **THEN** it issues no per-asset resource read, because no rule needs one

#### Scenario: The preview and the status total agree exactly

- **WHEN** the join preview and the own-device status total are computed over the same library and the same
  membership bounds
- **THEN** they report the same count — neither admits an asset the other excludes

#### Scenario: An excluded asset costs no resource read

- **WHEN** the walk encounters an asset the policy excludes
- **THEN** the asset's resources are never fetched

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

Exactly one narrowing is **required** rather than advisory: the capture-date **lower bound** SHALL be pushed
into the platform query. That is a **liveness** property of the walk, not a correctness property of
admission — every rule is equally load-bearing for what is admitted, but an unbounded walk is watchdog-killed
before the authoritative filter ever runs.

The origin exclusions and the capture-date range SHALL both be applied before the device-manifest hook: the
hook receives the **admitted set**, not the inputs from which one could be derived (capability
`device-manifest`).

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
- **THEN** the resource is dropped before the engine and before `retainAssets`, so no upload job is created
  and the ledger gains no entry for it

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

#### Scenario: A new rule forces a translation decision

- **WHEN** a new selection rule is added to the sealed rule set
- **THEN** each platform translator fails to compile until it states explicitly whether it can express that
  rule, so a rule can never be silently left out of the narrowing

#### Scenario: The manifest receives the admitted set

- **WHEN** a cycle discovers a screenshot, a pre-lower-bound camera photo, and an in-range camera photo
- **THEN** the device-manifest hook is fed only the in-range camera photo — both exclusions are applied
  before the hook, so no consumer downstream can re-derive a different set
