## ADDED Requirements

### Requirement: Resource asset identity
`Resource` SHALL carry `assetId: String` — an opaque grouping identifier for the asset a resource
belongs to (several resources of one photo share it). The engine SHALL carry `assetId` through to
the ledger (via the record operations) but SHALL NOT interpret it — like `filename`, it is pure
identity whose meaning belongs to the platform (iOS: the asset's `localIdentifier`, normalized;
tests/console: any string). It plays no part in the decision: a `ResourceChanged` is still decided
solely from the ledger entry for `filename` and the `version`.

#### Scenario: assetId is carried into the recorded entry
- **WHEN** a resource with `assetId = "A"` is uploaded and the platform reports `UploadStarted`
- **THEN** the ledger entry for its key has `assetId == "A"`

#### Scenario: assetId does not change the decision
- **WHEN** a `ResourceChanged` is handled for a resource whose key is absent from the ledger
- **THEN** the answer is `Upload` regardless of the resource's `assetId` (the decision reads only
  `filename` and `version`)
