## ADDED Requirements

### Requirement: The admitted set is a single derivation every consumer receives

There SHALL be exactly **one** derivation of a membership's **admitted set** — the assets the selection
policy admits — and every consumer SHALL obtain its answer from that one set rather than re-applying the
policy's rules itself. The policy SHALL be expressed as a value (`SelectionPolicy`) carrying its rules,
with a single `admits(asset)` decision; the admitted set is `candidates.filter { policy.admits }`. The
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

Admission SHALL be applied at the point of **query** (when a consumer asks for the set/count), never
pre-applied at ingest into any cache or accumulator, so no upstream stage can pre-filter by a subset of
the rules. The single authoritative in-memory admission SHALL remain authoritative over every optimization
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

## MODIFIED Requirements

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

#### Scenario: Upload and manifest admit the identical set

- **WHEN** a device backs up for an event
- **THEN** the assets whose bytes it uploads and the assets its device manifest lists are the same set,
  each derived from the one admitted set

#### Scenario: A post-ceiling photo is in neither

- **WHEN** the device holds a photo captured after the membership's ceiling
- **THEN** its bytes are not uploaded and it is not listed in the manifest

### Requirement: The policy scopes the own-device status total

The own-device upload **total** `N` SHALL count exactly the assets in the membership's **admitted set** —
the same set the upload cycle admits — and SHALL NOT re-apply the policy's rules independently. `N` SHALL
respect the capture-date **range** (both bounds), the origin exclusions, the echo suppression, and the
album denylist by deriving from that set, so an asset the policy excludes never counts toward `N`.
Counting an excluded asset would peg completeness permanently below 100% and hold the screen at "pending"
forever — which is the concrete failure a floor-only `N` produced.

A `Contribution.None` (non-contributing) membership SHALL report `N = 0` without enumerating the library.

#### Scenario: N counts the admitted set, ceiling included

- **WHEN** the device holds photos both within and after the membership's capture-date range
- **THEN** `N` counts only those within the full range `[cutoff, until]` — a post-ceiling photo is not
  counted, so completeness can reach 100%

#### Scenario: N derives from the same set as upload

- **WHEN** the upload cycle admits a set and `N` is computed
- **THEN** `N` equals the size of that admitted own-device set, not a separately-filtered count

### Requirement: The policy scopes the join-time shareable-count preview

The join surface's shareable-count preview SHALL count exactly the assets the candidate membership's
**admitted set** contains for the candidate capture-date range — the same admission every other consumer
applies, computed purely locally with no backend call. It SHALL respect **both** capture-date bounds, the
origin exclusions, the echo suppression, and the album denylist by deriving from that set. Because the
preview evaluates a **candidate** range (the one the member is choosing, before commit), it constructs the
policy over the candidate bounds; it SHALL NOT re-implement the rules — only supply the candidate bounds to
the one admission.

#### Scenario: The preview and the committed membership admit consistently

- **WHEN** a member picks a capture-date range on the join surface and then confirms it unchanged
- **THEN** the count the preview showed equals the size of the admitted own-device set the committed
  membership produces (echo/album state permitting), because both apply the one admission

### Requirement: The event's end date is a ceiling on every membership's upper bound

An event SHALL carry an **end date** (`endsAt`), creator-chosen at creation and immutable. It SHALL act as
a **ceiling** on every membership's capture-date upper bound: a membership's effective upper bound SHALL be
`min(chosen, endsAt)`, computed and persisted at join. The upper bound's default SHALL be `endsAt` (the
full event window).

The ceiling SHALL be a **required** value on a persisted membership — there is no unbounded ceiling. A
membership always carries a concrete upper bound (persisted from `min(chosen, endsAt)` at join), and every
consumer applies it via the admitted set. (The prior "absent upper bound treated as unbounded" allowance
for pre-ceiling configs is removed; see `join-event` and `event-rejoin-reconciliation` — a pre-ceiling
config is reconciled by `decouple-event-window-from-lifetime` before this change's strict decode.)

The ceiling SHALL apply to **every** admitting consumer with **no exemption**, because they all derive from
the one admitted set — the byte upload, the device manifest, the status total `N`, and the preview alike.
A host cannot cause any photo captured after `endsAt` to be uploaded, listed, counted, or previewed.

#### Scenario: The chosen upper bound is clamped to the event end

- **WHEN** a member joins an event and chooses an upper bound later than `endsAt`
- **THEN** the persisted upper bound is `endsAt`, and photos captured after it are excluded from every
  consumer

#### Scenario: The ceiling is required, not unbounded

- **WHEN** a membership is persisted
- **THEN** it carries a concrete capture-date upper bound; no membership is unbounded above
