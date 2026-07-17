# photo-selection-policy — delta for move-features-download-album-creation

## MODIFIED Requirements

### Requirement: Participation direction is a selection input, carried as Contribution

The membership's participation **direction** SHALL be an input to the selection policy, alongside the
capture-date cutoff and the origin exclusions. The policy answers *what does this member contribute?* — the
cutoff bounds **when** a photo was taken, the origin exclusions bound **what it is**, and the direction bounds
**whether at all**. A `DownloadOnly` membership contributes the **empty set**.

The three inputs SHALL be carried to both policy consumers as a single value, `Contribution`, defined in `:domain`'s `model/` zone (package `app.snapsync.model`, seated there by migration step 3a —
visible to both policy consumers, `feature/upload` and `feature/status`):

- `Contribution.None` — the membership contributes nothing (`DownloadOnly`). It carries **no** cutoff,
  because a non-contributor has no cutoff to speak of.
- `Contribution.Since(cutoff)` — the membership contributes every admitted asset captured at or after
  `cutoff`.

`Contribution` SHALL be a **required** argument on both consumers, with **no default value**. A default is
prohibited in both polarities: a permissive default (`Since("")`) uploads the entire library from the
beginning of time, and a fail-closed default (`None`) makes a contributing member silently share nothing
while the screen reads "In sync" — the invisible failure this capability exists to prevent. The two states
being distinct constructors, rather than a cutoff plus a boolean, SHALL make "contributes nothing, and here
is the cutoff it is not using" unrepresentable.

The direction is a **per-membership** input, not a per-resource rule: it SHALL be applied **before** any
library walk, never as a per-asset filter within one. The walk costs one synchronous PhotoKit round-trip per
asset, so a non-contributor must never begin one to conclude it contributes nothing.

#### Scenario: A download-only membership contributes the empty set
- **WHEN** the membership's participation direction excludes upload
- **THEN** the selection policy admits no asset, regardless of any asset's capture date or origin

#### Scenario: The non-contributing case carries no cutoff
- **WHEN** a membership contributes nothing
- **THEN** it is expressed as `Contribution.None`, which carries no cutoff — the combination "contributes
  nothing, with a cutoff" cannot be constructed

#### Scenario: A non-contributor never walks the library
- **WHEN** the selection policy is applied for a `Contribution.None` membership
- **THEN** no library enumeration is performed — the empty result is reached before any per-asset walk begins

