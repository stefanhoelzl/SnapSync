## ADDED Requirements

### Requirement: Library resource enumeration seam

The gallery domain SHALL define, in `:domain:gallery`, a resource-enumeration seam that returns the
current library as a list of resources, each carrying `(filename, assetId, version)` — where
`filename` is the upload key (the reinstall-stable identity, `<assetId>-<kind>.<ext>`), `assetId`
groups a photo's resources, and `version` is the content-identity proof (the asset modification
timestamp). This is the **single shared derivation** of those fields: the iOS background-upload
producer's full-enumeration path SHALL delegate to it, so the same `(filename, version)` is computed
wherever enumeration happens (the join seed and the producer agree byte-for-byte). The iOS
implementation SHALL be PhotoKit-backed; `:domain:gallery` SHALL also provide a settable in-memory
implementation for the JVM harness and tests. The seam SHALL remain in `:domain:gallery` so its types
never reach `:domain:presentation`'s compile classpath (per "Module placement keeps the seam off
presentation").

#### Scenario: Enumeration yields per-resource identity and version
- **WHEN** a consumer enumerates a library with photos that each have one or more resources
- **THEN** it receives one entry per resource, each carrying that resource's `filename`, its photo's
  `assetId`, and the asset's `version`

#### Scenario: The producer and the seed derive identical keys and versions
- **WHEN** the upload producer enumerates a resource and the join seam enumerates the same resource
- **THEN** both yield the same `filename` and the same `version` (one shared derivation)

#### Scenario: Fake enumeration is settable
- **WHEN** a test sets the in-memory enumeration to a list of resources
- **THEN** enumerating returns exactly those resources, on JVM and on the iOS simulator
