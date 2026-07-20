# photo-selection-policy — delta

## MODIFIED Requirements

### Requirement: Denylisted album membership excludes an asset

The policy SHALL exclude every asset that is a member of an album whose title matches, **case-insensitively
and exactly**, an entry in a denylist of messaging- and social-application album titles. The denylist SHALL be
a `commonMain` constant.

The denylist SHALL match **user albums by title only**. Smart albums SHALL be matched by **subtype**, never by
title, because a smart album's title is system-localized.

Recall here is **known to be poor and is accepted**: on current iOS most messaging applications save directly
to the camera roll and create no album at all, and only WhatsApp is confirmed to create one (and only when its
"Save to Camera Roll" setting is enabled). The rule is retained because it is cheap — its cost is proportional
to the number of albums, not the number of assets — and strictly additive. It SHALL NOT be relied upon as the
primary mechanism for excluding received media; the resolution floors are.

**Under a limited grant the rule is inert.** A partial (`.limited`) grant exposes assets, not the album
structure: the user-album walk returns no albums even when a selected asset is a member of one (measured
on device with a real WhatsApp-album membership — the album was not surfaced and the lookup returned the
empty set, without error). The album seam's decision-free contract already makes an empty album walk an
empty exclusion set, so no code branches on permission — but the consequence SHALL be understood and not
overclaimed: a hand-picked photo in a denylisted album **will upload** under `LIMITED`. This is accepted
for the same reason the poor recall above is: the resolution floors — which read dimensions off the asset
itself and work under any grant — remain the primary received-media exclusion, and a deliberately selected
photo is the strongest admit signal the policy ever sees.

#### Scenario: An asset in a denylisted album is excluded
- **WHEN** a discovered asset is a member of an album titled `WhatsApp`
- **THEN** it is excluded from upload and from the manifest

#### Scenario: Title matching is case-insensitive and exact
- **WHEN** an album is titled `whatsapp`
- **THEN** it matches the denylist entry `WhatsApp`; an album titled `WhatsApp Backup` does not match

#### Scenario: An asset in a non-denylisted album is admitted
- **WHEN** a discovered asset is a member of a user album titled `Holiday 2026`
- **THEN** it is admitted — album membership excludes only against the denylist

#### Scenario: Under a limited grant the denylist excludes nothing
- **WHEN** a member with a `LIMITED` grant selects a photo that is a member of a denylisted album
- **THEN** the album lookup returns no membership (the album structure is not readable), the photo is
  admitted by this rule, and only the capture-date cutoff and the intrinsic origin rules (subtypes,
  resolution floors) can still exclude it
