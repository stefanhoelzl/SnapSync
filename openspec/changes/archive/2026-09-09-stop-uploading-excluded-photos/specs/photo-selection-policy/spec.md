## MODIFIED Requirements

### Requirement: The admitted set is a single derivation every consumer receives

There SHALL be exactly **one** derivation of a membership's **admitted set** — the assets the selection
policy admits — and every consumer SHALL obtain its answer from that one set rather than re-applying the
policy's rules itself. The policy SHALL be expressed as a value (`SelectionPolicy`) carrying its rules,
with a single `admits(facts)` decision; the admitted set is `candidates.filter { policy.admits }`. The
byte upload, the device manifest, the own-device status total `N`, and the join-time shareable-count
preview SHALL each derive from this set — upload uploads the admitted assets' resources, the manifest
lists them, `N` counts them, the preview counts them.

No consumer SHALL restate any of the policy's rules — not the capture-date range, the origin exclusions,
the echo suppression, nor the album denylist. A consumer that re-enumerates a rule is how the set drifts:
`add-event-date-range` added the capture-date **ceiling** to the byte filter and the preview but not the
manifest projection or `N`, so those two silently admitted post-ceiling photos into the manifest and the
status total while their bytes never uploaded — pegging the screen below 100% and offering foreign members
a resource that 404s. Making the admitted set one derivation makes that class of drift unrepresentable:
adding or changing a rule is one edit, and every consumer follows by construction.

Admission SHALL be applied at the point of **query** — when a consumer asks for the set or the count — and
no consumer SHALL treat an upstream-filtered structure as the admitted set. An upstream stage MAY exclude
assets earlier (the cycle drops origin-excluded resources before they reach the ledger, so they never reach
any reader downstream of it), but such a pre-filter enforces only a subset of the rules, so the consumer
still asks the policy. **The ledger's needs-job read is such a structure and SHALL NOT be treated as the
admitted set**: a row records that the policy admitted its asset *when the row was written*, and a
membership's policy changes under it (`reconfigure-membership`), so the set of rows needing a job and the
set of admitted assets are not the same set and diverge the moment a member narrows their scope. The
single authoritative in-memory admission SHALL remain authoritative over every optimization
(platform fetch narrowing included, capability's *Selection filter* requirement) — a narrowing MAY reduce
what a walk returns but SHALL NOT change the admitted set.

#### Scenario: Every consumer admits the same set

- **WHEN** a membership's byte upload, device manifest, status total `N`, and join preview each resolve
  their assets for the same capture-date range
- **THEN** all four resolve the identical admitted set — no consumer includes or excludes an asset the
  others do not

#### Scenario: The ceiling reaches every consumer

- **WHEN** a membership has a capture-date ceiling and the device holds a photo captured after it
- **THEN** that photo is admitted by **no** consumer — it is neither uploaded, nor listed in the device
  manifest, nor counted in `N`, nor counted in the preview

#### Scenario: Adding a rule is one edit

- **WHEN** a new selection rule is introduced
- **THEN** it is defined once on `SelectionPolicy`, and every consumer's admitted set reflects it without
  any per-consumer change — no consumer carries a copy of the rule set

#### Scenario: A consumer cannot re-enumerate the policy

- **WHEN** the codebase is inspected for the capture-date comparison (`creationDate` against a bound)
- **THEN** it appears only inside the single `SelectionPolicy` admission, not at any consumer

#### Scenario: The upload work source is not the admitted set

- **WHEN** the upload cycle reads the ledger rows that need a job
- **THEN** it asks the policy about those rows before resolving or enqueuing any of them, rather than
  treating the read's result as already admitted

### Requirement: One policy gates both byte upload and manifest listing

A membership's **selection policy** SHALL gate **both** which of the device's photo bytes are uploaded
**and** which of its assets are listed in that event's device manifest. Both SHALL derive from the **one
admitted set** (see *The admitted set is a single derivation every consumer receives*): the set uploaded
equals the set listed, because both read the same admitted set rather than each re-applying the policy.
Because the event union exposes each device's manifest-listed assets to other members, the policy thereby
governs both this device's backup scope and what other members can download from it. A photo excluded by
the policy — by capture-date range (**both** the lower cutoff and the upper ceiling), origin, echo, or
album — SHALL neither have its bytes uploaded nor appear in the manifest (and therefore SHALL NOT enter
the event union).

This SHALL hold when the policy **narrows under a live membership** (`reconfigure-membership`), and not
only for a policy that was already in force when a resource was discovered. A resource recorded as
needing an upload job under a wider policy SHALL NOT have its bytes uploaded once the membership's
current policy excludes its asset — the exclusion takes effect on the next cycle, whatever the ledger
already records, and whether or not the discovery cursor was reset.

#### Scenario: Upload and manifest admit the identical set

- **WHEN** a device backs up for an event
- **THEN** the assets whose bytes it uploads and the assets its device manifest lists are the same set,
  each derived from the one admitted set

#### Scenario: A post-ceiling photo is in neither

- **WHEN** the device holds a photo captured after the membership's ceiling
- **THEN** its bytes are not uploaded and it is not listed in the manifest

#### Scenario: Raising the cutoff stops uploading the rows recorded under the wider one

- **WHEN** a membership's walk has recorded rows needing a job for assets across a wide capture-date
  range, the member then raises the cutoff so most of those assets fall outside it, and further cycles run
- **THEN** no excluded asset's bytes are uploaded, and the count the reconfigure surface showed as "will
  be shared" is the count whose bytes actually leave the device

#### Scenario: Excluded rows do not block admitted work

- **WHEN** the ledger holds more rows needing a job than one cycle enqueues, and the rows the current
  policy excludes sort ahead of the admitted ones
- **THEN** the cycle still enqueues admitted work, rather than exhausting its batch on excluded rows and
  making no progress
