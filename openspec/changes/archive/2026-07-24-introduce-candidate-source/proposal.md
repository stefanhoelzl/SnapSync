## Why

`introduce-eventphotoset` made the admitted set a thing consumers *receive*, and it fixed the ceiling
bug. But it left the seam it was built on unused: `EventPhotoSet` takes a
`suspend (SelectionPolicy) -> List<Candidate>`, and **all nine call sites ignore the parameter** — every
consumer fetches first and hands over a finished list.

Because the fetch happens *outside* the abstraction, each consumer must flatten the policy to a bare
cutoff string and relay it by hand:

```
UploadCycle → BackgroundTransfer.discoverResources(token, since: String)
                └→ IosDiscovery.discover(token, since)
                     └→ PhotoLibrary.enumerate(since)
                          └→ ResourceEnumerator
                               └→ RawAssetSource.walkSince/walk/factsSince(since)
                                    └→ fetchOptionsSince(since)   ★ the only consumer
```

Seven layers; six of them pure pass-through. That relay is why the platform's native fetch narrows by only
two of the policy's rules — the floor arrives as a parameter, the subtype mask is hardcoded — and why
`introduce-eventphotoset`'s task 4.2 (have the platform translate the sealed rules) costs ~45 edits across
25 files. The design already had a direct route; the wiring never used it.

Two live consequences, neither hypothetical:

- **The walk pays for assets it then discards.** `walkSince` calls `assetResourcesForAsset` (~110 ms/asset
  on an SE2) for *every* date-passing asset, before any origin rule runs. Sub-floor images, edited assets
  and post-ceiling photos each cost a full round-trip and are dropped immediately after. `N` re-pays this
  on every foreground, for a count that needs no resources at all.
- **The preview and `N` disagree.** The preview walks facts-only, so `isGif` is unknown and admits on
  doubt; `N` walks eagerly, so it knows and excludes. Both claim to be "the admitted set". For a library
  with an in-scope GIF, the preview over-counts — the same shape as the bug the previous change removed.

## What Changes

- **One read seam.** `CandidateSource.candidates(policy)` replaces `RawAssetSource` (3 methods),
  `PhotoLibrary` (2 methods) and `ResourceEnumerator`. The platform receives the policy directly and
  pattern-matches its sealed rules into a native fetch predicate. Every `since: String` parameter is
  deleted. **This supersedes `introduce-eventphotoset` task 4.2.**
- **The cycle keeps its own seam.** `BackgroundTransfer.discoverResources(sinceToken, policy)` still
  returns `Discovery`, because a resumable incremental walk carries cursor state (`nextToken`,
  `removedAssetIds`, `fullEnumeration`) that a count has no use for. Both seams take the policy; neither
  takes a string.
- **BREAKING (policy): the GIF exclusion is removed.** It is the only rule not decidable on cheap asset
  facts — the only reason a resource read was ever needed to *decide* — and therefore the only reason the
  preview and `N` can disagree. The 3 MP image floor already excludes every ordinary GIF (a Giphy meme is
  ~0.13 MP; a Live-Photo→GIF export is downsized by the exporting app). What is no longer excluded is an
  **edited** GIF (the floor is skipped for `hasAdjustments`) or one at **≥3 MP** — both rare, and both
  precisely the trade the policy already declares: *"a stray uploaded meme is harmless and visible, while
  an event photo that silently fails to upload is invisible and unfixable."*
- **Admission becomes facts-only and single-phase.** Every rule now reads a plain `PHAsset` property or a
  supplied id set, so `count()` is exact *and* cheap at every consumer, and the preview and `N` agree for
  the first time.
- **Resources are read lazily, per admitted asset.** `Candidate.resources()` becomes a genuine cost ladder
  with no correctness role: filter-then-fetch instead of fetch-then-drop.
- **The permission split moves into the source.** A permission-aware `CandidateSource` (full walk under
  `GRANTED`, snapshot under `LIMITED`) replaces the branch in each consumer:
  `OwnDeviceGalleryStatusSource.refreshFrom` is deleted and `ShareableCountSource` loses its
  `GRANTED`/`LIMITED` arm. The `DENIED`/`NOT_DETERMINED` check stays with the consumer — that is a
  different question ("can we answer at all", which must render no row rather than zero).

## Capabilities

### New Capabilities

None. This is a restructuring of how existing capabilities read the library; the one observable contract
change is the GIF removal, which belongs to the capability that owns it.

### Modified Capabilities

- `photo-selection-policy`: the GIF exclusion is **removed** — admission is decidable on asset facts alone,
  so every consumer resolves the identical set without reading resources. The platform's native narrowing
  is restated as translating the policy's own rules rather than a hardcoded predicate.
- `gallery-status`: the decision-free walk seam becomes one policy-taking `CandidateSource` yielding
  candidates that carry neutral facts and fetch their own resources on demand; the eager `RawAsset` walk
  and the `Resource`-returning enumerator are removed.
- `limited-photo-access`: the fed selection snapshot is restated as a `CandidateSource` impl. The
  sanctioned-read discipline is **unchanged** — the snapshot is still read eagerly at the cold-launch
  baseline and observer emissions, which is what keeps every library *fetch* in-flow.
- `join-share-count`: the preview no longer admits a GIF on doubt, because there is no GIF rule to be in
  doubt about; its count and the status total now agree exactly.

## Impact

**`:domain`** — new `ports/CandidateSource`; `RawAssetSource`, `PhotoLibrary` and `compose/ResourceEnumerator`
deleted; `SelectionRule.ExcludeGif` and `MIME_GIF` deleted; `EventPhotoSet` takes a source rather than a
lambda; `OwnDeviceGalleryStatusSource.refreshFrom` and `ShareableCountSource`'s permission branch deleted;
`BackgroundTransfer.discoverResources` takes a policy. `resourcesFrom`/`uploadKey`/`normalizeAssetId` stay
in `model/`, called per-asset, still covered on JVM + simulator.

**`:adapter:ios:ext-safe`** — `PhotoLibraryRawAssetSource` becomes the iOS `CandidateSource`, translating
the sealed rules into `PHFetchOptions` (the three device-verified predicate constraints are unchanged:
the `NOT ((mediaSubtypes & N) != 0)` form, no predicate arithmetic, no `hasAdjustments` key). `IosDiscovery`
builds candidates from its own full or id-scoped fetch.

**`:adapter:generic:fake`** — `InMemoryRawAssetSource`/`InMemoryPhotoLibrary` collapse into one honest
in-memory `CandidateSource`.

**`:test:world`, `:app:desktop`, `:app:ios`** — wiring follows the port collapse; the world's gallery
lever and the inspector's policy badge read the new seam.

**Unchanged deliberately:** `SelectionScopedTransfer` survives. Its `fullEnumeration = false` is what stops
`retainAssets` pruning the ledger down to the current selection — which would strip the `COMPLETED` row
from every photo not presently selected and re-upload it. That is a cursor decision, not a candidate one.

**Backend** — none.
