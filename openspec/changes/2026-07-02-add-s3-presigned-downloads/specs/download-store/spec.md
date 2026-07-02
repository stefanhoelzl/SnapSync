## ADDED Requirements

### Requirement: Pending resource URL is refreshed on re-plan

When the app re-plans an asset already recorded in the store (re-reading the union on join or
foreground), the store SHALL **refresh** the stored `url` of each of that asset's resources whose
`stagedPath IS NULL` (not yet downloaded) to the value from the latest read, while leaving a resource
that is already **staged**, and any **terminal (imported)** asset, entirely untouched. A resource's
other fields (`role`, `contentType`, `originalFilename`) are immutable per `resourceKey` and SHALL NOT
change on re-plan. This lets a freshly-minted presigned download URL supersede an earlier, now-expiring
one for work still pending, without disturbing completed staging or re-downloading already-staged
bytes.

#### Scenario: Re-plan updates a pending resource's url

- **WHEN** a resource is planned with url A while unstaged, then the asset is re-planned with url B for
  the same `resourceKey`
- **THEN** the resource's stored `url` becomes B and it appears in `pendingDownloads()` with url B

#### Scenario: A staged resource keeps its url and staging

- **WHEN** a resource has been staged (its `stagedPath` is set) and its asset is re-planned with a
  different url
- **THEN** the resource's `url` and `stagedPath` are unchanged (it is not re-queued or re-downloaded)

#### Scenario: A terminal asset is untouched by re-plan

- **WHEN** an imported asset is re-planned
- **THEN** none of its resources' urls change and the asset stays terminal (never downgraded)
