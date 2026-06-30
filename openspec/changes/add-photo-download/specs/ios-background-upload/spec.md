## ADDED Requirements

### Requirement: Discovery suppresses downloaded assets

The upload cycle's discovery SHALL consult the download store's suppression projection (the set of
`createdLocalId`s of foreign assets this device downloaded and imported) and SHALL drop every
discovered resource whose `assetId` is in that set **before** engine fan-out (no upload job created)
and before `retainAssets`. This prevents the download→import→re-upload echo: an imported foreign asset
gets a fresh local `localIdentifier` that discovery would otherwise treat as a new local asset and
upload back. The suppression read SHALL be read-only and cross-process (the extension reads the
app-written store over WAL). The filter SHALL live in the platform-free upload-cycle core (a injected
suppression port), not in untested platform wiring, so it is exercised in `commonTest`.

#### Scenario: A downloaded-then-imported asset is never re-uploaded

- **WHEN** discovery encounters a resource whose `assetId` is in the suppression set
- **THEN** no upload job is created for it and it is excluded from `retainAssets`

#### Scenario: Suppression is consulted before fan-out

- **WHEN** a discovery cycle runs
- **THEN** suppressed assets are removed from the discovered set before the engine is asked to create
  any upload job

#### Scenario: Non-suppressed assets upload normally

- **WHEN** discovery encounters a resource whose `assetId` is not suppressed
- **THEN** it is handed to the engine and uploaded as before
