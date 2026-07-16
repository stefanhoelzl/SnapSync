## ADDED Requirements

### Requirement: Participation direction is a selection input, carried as Contribution

The membership's participation **direction** SHALL be an input to the selection policy, alongside the
capture-date cutoff and the origin exclusions. The policy answers *what does this member contribute?* — the
cutoff bounds **when** a photo was taken, the origin exclusions bound **what it is**, and the direction bounds
**whether at all**. A `DownloadOnly` membership contributes the **empty set**.

The three inputs SHALL be carried to both policy consumers as a single value, `Contribution`, defined in
`:domain:gallery` (the only module both `:capability:upload` and `:domain:status` can see):

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

## MODIFIED Requirements

### Requirement: One policy gates both byte upload and manifest listing

A membership's **selection policy** SHALL gate **both** which of the device's photo bytes are uploaded
**and** which of its assets are listed in that event's device manifest — the policy being its participation
direction, its capture-date cutoff, and the origin exclusions, carried together as `Contribution`. The two
SHALL use the **same** admitted set, so the set uploaded equals the set listed. Because the event union exposes each
device's manifest-listed assets to other members, the policy thereby governs both this device's backup scope
and what other members can download from it. A photo excluded by the policy SHALL neither have its bytes
uploaded nor appear in the manifest (and therefore SHALL NOT enter the event union).

A photo excluded by the policy but listed in the manifest would be strictly worse than one merely uploaded:
it would enter the event union and every other member would attempt to download bytes that were never
uploaded.

A `Contribution.None` membership admits nothing, so it SHALL upload no bytes **and** write no manifest
listing any asset. Publishing a non-contributor's assets to the manifest would be the same failure in its
most complete form: the member's entire library would enter the event union, be offered to every other
member, and 404 for all of them — while the member was told they would share nothing.

#### Scenario: An in-scope photo is both uploaded and shared
- **WHEN** a photo's `creationDate` is at or after the membership's cutoff and no origin exclusion applies
- **THEN** its bytes are uploaded and it is listed in the device manifest (eligible for the event union)

#### Scenario: An out-of-scope photo is neither uploaded nor shared
- **WHEN** a photo's `creationDate` precedes the membership's cutoff
- **THEN** its bytes are not uploaded and it is not listed in the manifest, so no other member can download it

#### Scenario: An origin-excluded photo is neither uploaded nor shared
- **WHEN** a photo is at or after the cutoff but is excluded by an origin rule (for example a screenshot)
- **THEN** its bytes are not uploaded **and** it does not appear in the device manifest, so it never enters
  the event union and no other member downloads it

#### Scenario: A non-contributing membership uploads nothing and lists nothing
- **WHEN** the membership is `Contribution.None` and the library holds admitted-looking photos
- **THEN** no bytes are uploaded and no device manifest listing any asset is written, so nothing enters the
  event union

### Requirement: The policy scopes the own-device status total

The own-device upload **total** `N` SHALL count only the device's own assets that the **selection policy
admits** — a contributing membership, at or after the cutoff, **and** not origin-excluded — the same set the
upload cycle admits (`N` is the count driving the joined screen's sync health, capability `sync-status`). An
asset the policy excludes SHALL NOT count toward `N`, because it is never uploaded; counting it would peg
completeness permanently below 100% and hold the screen at "pending" forever.

A `Contribution.None` membership SHALL report `N = 0`, computed **without enumerating the library**. `N` is
otherwise a parallel computation that no upload gate feeds, so without this the total would report a library
the cycle will never upload, and the joined screen would show an upload arrow that never settles.

The status total and the upload cycle SHALL apply the **same** policy, from the **same** `Contribution`
value. They are computed by different components (`OwnDeviceGalleryStatusSource` in the app process,
`UploadCycle` in the upload path), so this identity is a requirement, not an implementation coincidence.

#### Scenario: A pre-cutoff asset does not inflate the total
- **WHEN** the library holds a pre-cutoff asset and an in-scope asset, and the in-scope asset is uploaded
- **THEN** the total counts only the in-scope asset, so the joined screen reaches "in sync" (not a
  perpetual "pending")

#### Scenario: An origin-excluded asset does not inflate the total
- **WHEN** the library holds a screenshot taken after the cutoff, and every admitted asset is uploaded
- **THEN** the screenshot does not count toward `N`, and the joined screen reaches "in sync" rather than
  holding permanently below 100%

#### Scenario: A non-contributing membership totals zero without a walk
- **WHEN** the own-device total is computed for a `Contribution.None` membership whose library holds photos
- **THEN** `N` is `0` and no library enumeration is performed

#### Scenario: There is no unscoped whole-library total
- **WHEN** the own-device total is computed for any joined membership
- **THEN** it derives from that membership's `Contribution` — a contributing one counted against its
  non-null cutoff, a non-contributing one reported as `0` — and there is no unscoped whole-library branch

#### Scenario: The total and the cycle admit the same set
- **WHEN** the own-device total and the upload cycle are computed for the same membership
- **THEN** both are derived from the same `Contribution` value, so the counted set equals the admitted set
