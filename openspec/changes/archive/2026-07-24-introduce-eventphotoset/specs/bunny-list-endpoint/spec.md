## ADDED Requirements

### Requirement: The union byte-presence check is defense-in-depth

The event union's completeness cross-check SHALL remain, but as **defense-in-depth** rather than the
primary completeness mechanism — an asset is included in the union only when every resource its device
manifest names is present in the device's byte partition. Because the device manifest now lists only COMPLETED
(uploaded) resources (capability `device-manifest`), a named resource's bytes are, by construction,
already present; the sweep additionally protects manifest-referenced bytes from collection (capability
`scheduled-cleanup`). The check therefore catches only a residual COMPLETED-but-absent edge and SHALL NOT
be relied upon to filter merely-discovered assets.

#### Scenario: A named-but-absent byte is still excluded

- **WHEN** the manifest names a resource whose byte object is nonetheless absent from the partition
- **THEN** the union still excludes that asset — the check remains as a safety net
