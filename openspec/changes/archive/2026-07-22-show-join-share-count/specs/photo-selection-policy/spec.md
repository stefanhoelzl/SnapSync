## ADDED Requirements

### Requirement: The policy scopes the join-time shareable-count preview

The join-time **shareable-count preview** (capability `join-share-count`) SHALL apply the **same**
selection policy — participation direction, capture-date cutoff, and origin exclusions, carried as
`Contribution` — that gates the byte upload, the device manifest, and the own-device status total `N`. The
preview count SHALL be derived from the **same admitted-set logic**, evaluated against the surface's
**candidate** (uncommitted) cutoff rather than the persisted one, so the preview, the upload set, the
manifest, and `N` are **one universe**: the number a member sees before confirming equals the set that
would upload and list for that cutoff. The policy logic SHALL NOT be forked or re-implemented for the
preview.

The preview MAY read the policy over **cheap `PHAsset` properties without the per-asset resource read**
(the resource read builds upload keys, which a count does not need); this is an admissible optimisation of
the **same** policy, exactly as the upload cycle permits a platform fetch to be narrowed as an
optimisation without changing the admitted set — so the count over the cheap-property path SHALL be
identical to the count of the set the cycle would admit for that cutoff.

Because the candidate cutoff is not yet clamped, the preview MAY show the count for a value the join-time
`max(chosen, startsAt)` clamp would raise; the surface renders the resulting instant before confirm
(existing requirement), so the count corresponds to the instant shown.

#### Scenario: The preview counts the same set the cycle would upload
- **WHEN** the shareable-count preview is computed for a candidate cutoff `C` and a contributing direction
- **THEN** it counts exactly the assets the upload cycle would admit for `C` — at or after `C`, not
  origin-excluded — using the same policy, with no separate or looser rule

#### Scenario: A non-contributing candidate previews zero without a walk
- **WHEN** the preview is computed for a candidate direction that excludes upload
- **THEN** the count is `0` and no library enumeration is performed, exactly as `N` reports for
  `Contribution.None`

#### Scenario: The preview does not fork the policy
- **WHEN** an origin exclusion (a screenshot, a sub-floor received image, a denylisted-album member) would
  drop an asset from the upload set
- **THEN** the preview count drops the same asset, the exclusions being the one shared policy rather than a
  preview-specific copy
