## Context

The capture-date cutoff (`photo-date-cutoff`) is nullable at three levels, and each level lets an
unbounded scope through:

```
  UI          chosen: LocalDateTime? = null   when createdAt is absent/unparseable
                       │                      (AppDateTimeField renders empty; Join stays enabled)
                       ▼
  Config      EventConfig.minPhotoDate: String? = null
                       │
                       ▼
  Domain      photoCutoff: suspend () -> String?
                       │
                       ├── UploadCycle:                  if (cutoff == null) unfiltered else filter
                       └── OwnDeviceGalleryStatusSource:  if (cutoff == null) …    else filter
```

`null` means whole-library scope, spec'd in `photo-date-cutoff` as *"preserving today's behavior"* — a
sentence written when today's behavior was backing up your own photos to your own bucket. Under event
photo sharing the same default uploads a guest's entire camera roll to a stranger's event.

The cutoff is also applied in the **wrong place** for cost. `RawAssetSource.walkAll()` fetches every
asset (`PHAsset.fetchAssetsWithOptions(null)`) and issues one synchronous XPC round-trip per asset
(`PHAssetResource.assetResourcesForAsset`, a CoreData fetch over `NSXPCStoreConnection` into
`photolibraryd`'s `Photos.sqlite`); `resourcesFrom` maps all of it; and only then does a `commonMain`
filter drop everything before the cutoff. Twelve thousand round-trips to keep forty photos.

That is what killed build 286 (`0x8BADF00D` scene-update watchdog, thread 0 blocked in `mach_msg2_trap`).
Commit `28226ec` hopped the walk to `Dispatchers.Default`, which stops the kill and does nothing about
the storm.

**Constraints.** `commonMain` is limited to the common stdlib (iOS targets are present).
`PhotoLibraryRawAssetSource` is iOS-only and device/simulator-only — its `PHFetchOptions` predicate
cannot be unit-tested on the JVM. `KeychainConfigStore` seeds `config` **synchronously** at construction,
so a decode-time repair cannot consult the network. The app and the upload extension are separate
processes reading the same Keychain item.

## Goals / Non-Goals

**Goals:**

- Make an absent cutoff unrepresentable, at every level, so unbounded upload scope cannot be reached —
  by construction, not by guarding. The failure is invisible on a guest's device; correctness cannot
  depend on observing it.
- Delete the whole-library walk rather than discourage it.
- Reduce the enumeration's cost from `O(library)` to `O(assets since the cutoff)` **without** changing
  which assets are counted or uploaded.
- Keep every sync decision in tested `commonMain`; add no decision to the untestable iOS adapter.

**Non-Goals:**

- Redefining what an *uploadable asset* is. The `resourceRole` filter (originals only) is untouched, as
  is its accidental role as an uploadability predicate.
- Deciding whether iCloud Shared Album assets should upload to an event.
- Engineering a data migration for already-joined installs.
- Restoring the `photoLibraryDidChange` live-count ding, which the current code drops (only the dead
  `PhotoLibraryGalleryStatus` ever registered it). Cheap once the walk is bounded; out of scope here.

## Decisions

### The cutoff is required at the type level, not merely defaulted

Make `EventConfig.minPhotoDate` a non-null `String` and `photoCutoff` a `suspend () -> String`. The two
`if (cutoff == null)` branches are deleted rather than made unreachable.

*Alternative considered: keep the nullable type, default it at the join gate.* Rejected — it leaves the
whole-library code path alive and reachable from `autoConfirm`, the switch dialog, and any future caller.
The point of this change is that the path stops existing.

### A legacy Keychain item reads as **no config**, not as a default

A persisted item written before `minPhotoDate` existed has no such key. With a non-null field and no
default, `kotlinx.serialization` throws `MissingFieldException`; the store surfaces `config = null` and
the device shows the setup gate. The user re-scans the invite. Neither process uploads meanwhile.

*Alternative considered: `val minPhotoDate: String = ""`,* mirroring how `EventConfig.name` defaults to
`""` so a legacy item "decodes non-null, never a decode crash". **Rejected, and it is a trap.** The
cutoff comparison is

```kotlin
filter { (it.metadata[RESOURCE_META_CREATION_DATE] ?: "") >= cutoff }
```

An undated **asset** (`creationDate == ""`) is safely excluded by any real cutoff — which is why `""`
felt safe for `name`, and is the spec'd behavior in `photo-date-cutoff`. An undated **cutoff** is the
mirror image: every string is `>= ""`, so every asset passes. The empty-string sentinel is
*byte-for-byte equivalent* to the `null` being removed, except invisible: the type says the cutoff is
present, and the null check that used to mark the danger is gone. Same sentinel, opposite blast radius,
depending on which side of the `>=` it lands.

*Alternative considered: repair at decode by fetching the event's `createdAt`.* Rejected — the store
seeds synchronously by design, and a network call at construction would break that guarantee for every
launch to serve a case with no production instances.

Accepted cost: an install joined under a pre-cutoff build silently becomes unjoined. One TestFlight
tester, no production users.

### The join gate seeds `now`, so an empty cutoff is unrepresentable

`chosen` was seeded from `phase.defaultCutoff?.let(cutoff::toLocal)` and stayed `null` when the event's
`createdAt` was absent or unparseable (`runCatching { Instant.parse(…) }.getOrNull()`). It is now seeded
on first composition to the loaded default, else to `now`, and its type is non-null `LocalDateTime`.
`JoinPhase.Ready.defaultCutoff` is likewise non-null (`String`). `CutoffRow.onValueChange` is already
`(LocalDateTime) -> Unit`, so the picker cannot clear a seeded value.

`autoConfirm`'s `explicitCutoff ?: load.createdAt` gains the same `?: now` fallback.

**No runtime confirm gate.** An earlier draft also disabled the Join button on a null cutoff, reasoning
that two guards for one hole were prudent. They were not: `nowLocal()` cannot return null, so the seeded
value is provably non-null and the disabled state was unreachable — a dead branch pretending to be a
safeguard. With `chosen` and `defaultCutoff` both non-null, "join without a cutoff" is unrepresentable at
the type level, which is the standard this change is held to (the failure is invisible on a guest's
device, so it must be impossible rather than guarded). The `seeded` flag, both `enabled =` params, and
both `?.let` no-ops went with it.

One nullable remains, and is real: `SwitchDialog` remembers the cutoff from its `Ready` phase to reuse on
a retry, and a dialog mounted straight into `CommitFailed` never saw one. That retry is inert rather than
unbounded.

### The walk takes a bound; the fetch predicate is an inert optimization

`RawAssetSource.walkAll()` is deleted. `walk(since: String)` replaces it, and
`GalleryResourceEnumerator.enumerate(since: String)` threads the bound through `ResourceEnumerator`.

The iOS adapter sets `PHFetchOptions.predicate = NSPredicate("creationDate >= %@", …)`. This is the only
new iOS-only surface, and it is **not** a decision: *what* the bound is stays in `commonMain`, the walk
only receives it. Critically, `resourcesFrom`'s consumers keep their existing pure cutoff filter, which
runs downstream of the fetch. The predicate can therefore only remove work — anything it lets through is
filtered again by JVM- and simulator-tested code. **It cannot produce a wrong count.**

The asymmetry to respect: a predicate that is too *narrow* silently undercounts, because nothing
downstream can add an asset back. Skew between the lexicographic ISO-8601 string compare and `NSDate`
predicate evaluation (fractional seconds, timezone) is the plausible source. Therefore **widen the
predicate by a margin** — fetch from `bound − 1 day` — and let the exact string filter do the real work.
A handful of extra assets is the price of an optimization that cannot be wrong in the dangerous
direction.

*Alternative considered (explored, rejected): drop the resource walk entirely and count assets via
`PHFetchResult.count`.* The dead `PhotoLibraryGalleryStatus` already does exactly this, O(1), and its
KDoc anticipates adopting the cutoff predicate. But `OwnDeviceGalleryStatusSource` counts distinct
`assetId`s **after** `resourcesFrom` drops resources with no role, so an asset whose resources are all
derivatives (`fullSizePhoto`, `adjustmentData`, …) is excluded from the total today — correctly, since
the upload cycle can never upload it. A raw `PHFetchResult.count` would include it and peg progress below
100% forever, on a guest's device, where nobody can see it. Bounding the walk gets the cost win without
answering "what is an uploadable asset", which is a separate question this change explicitly defers.

### The incremental change-token walk is bounded too — a device finding

The first draft bounded only the full enumeration, reasoning that "the incremental walk is already
bounded by the change feed." **On device that is false, and expensively so.**

A change feed reports what *changed*, not what is in *scope*. Seeding an SE2's library to 4001 assets and
letting the extension run its incremental branch produced:

```
← platform.discoverResources = 1503 resource(s) (166247ms)
  discovered 1503 / cutoff dropped 1503
```

166 seconds, to fetch the resources of 1500 decades-old assets and then throw every one away on capture
date — inside a process with a ~3-minute hard OS cap. It came within 14 seconds of a force-kill. The
real-world trigger is not a synthetic seed: it is a guest joining an event and then iCloud syncing a few
thousand old photos onto the device.

`assetResourcesForAsset` is the expensive call (~110 ms per asset here, one synchronous XPC round-trip
into `photolibraryd`). `creationDate` is a plain `PHAsset` property. So the by-identifiers walk takes the
bound as well, and rejects an out-of-scope asset **before** reading its resources. Same 1000-asset change
feed, after: `discovered 0 resource(s) (343ms)`.

That ~110 ms/asset figure also explains the original crash arithmetically: a 10-second scene-update
budget is exhausted by roughly 90 assets.

### `fullEnumeration` reconciliation is unaffected — verified

`IosDiscovery.discover()` returns `fullEnumeration = true` when there is no usable change token, and the
cycle then calls `ledger.retainAssets(...)` to prune rows for assets no longer present. The concern was
that a bounded walk would narrow that live set and prune rows for pre-cutoff assets as though the photos
had been deleted.

**It does not.** `UploadCycle.kt:188` passes `liveResources` to `retainAssets` — the set *after* echo
suppression (`:141`) and *after* the cutoff filter (`:152`), not the raw walk output:

```kotlin
val liveResources = unfiltered.filter { (it.metadata[RESOURCE_META_CREATION_DATE] ?: "") >= cutoff }
…
if (discovery.fullEnumeration) ledger.retainAssets(liveResources.mapTo(mutableSetOf()) { it.assetId })
```

Bounding the walk narrows `discovery.resources`, but the authoritative `commonMain` cutoff filter sits
between the walk and `retainAssets` and is unchanged. The set reaching `retainAssets` is therefore
**identical before and after this change**, and the hazard is vacuous by construction rather than by
argument.

Corollary worth stating: pruning ledger rows for pre-cutoff assets on a full enumeration is **existing
behavior today**, for any membership carrying a cutoff. This change neither introduces nor alters it.

(An earlier draft of this section argued vacuity from a three-claim chain about `null`-cutoff installs
unjoining. That chain was answering the wrong question — it presumed the walk's output feeds
`retainAssets` directly. It does not, and the chain is unnecessary.)

## Risks / Trade-offs

- **The `NSPredicate` disagrees with the lexicographic string compare at the boundary** → widen the fetch
  by one day; the pure `commonMain` filter remains authoritative. Undercounting is the only dangerous
  direction and the margin removes it.
- **`PHFetchOptions.predicate` behavior is untestable from Linux and from the JVM** → the predicate is
  inert by construction (see above), so a wrong predicate degrades performance, not correctness. Behavior
  stays pinned by the existing `commonMain` tests on the pure filter, which run on JVM **and**
  `iosSimulatorArm64`.
- **Legacy installs silently unjoin** → accepted; one tester, zero production users. A log line at the
  decode failure makes it diagnosable rather than mysterious.
- **Scoped `fullEnumeration` prunes pre-cutoff ledger rows** → **resolved, not a risk.** `retainAssets`
  is fed the post-cutoff-filter set, so the bounded walk cannot change its input. See *`fullEnumeration`
  reconciliation is unaffected*.
- **`cutoff = now` when `createdAt` is missing is stricter than the user might expect** — a guest joining
  a party that started an hour ago shares nothing from that hour. Accepted: erring toward sharing *too
  few* photos is recoverable (re-join, pick a date); erring toward too many is not.
- **Threading `since` through the seams touches six modules and both harnesses** → mechanical, compiler
  enforced, no behavior in the fakes.

## Migration Plan

1. Ship the non-null `minPhotoDate`. Installs joined under a pre-cutoff build read `config = null`, show
   the setup gate, and re-scan their invite. No data is written or destroyed; the stale Keychain item is
   overwritten on the next successful join.
2. Rollback is a revert: the nullable field decodes both old and new items, since a written item always
   carries `minPhotoDate` after this change.
3. No backend change. The cutoff is device-local and never sent (`photo-date-cutoff`).

## Open Questions

- ~~**Is the scoped-`fullEnumeration` pruning hazard genuinely vacuous?**~~ **Resolved during apply.**
  `retainAssets` receives `liveResources` (post-suppression, post-cutoff-filter), never the raw walk
  output, so the bounded walk cannot change its input. No chain of claims required.
- **Do any persisted Keychain items in the wild actually lack `minPhotoDate`?** If none do, the migration
  section is theory and the decode-failure path is a latent guard rather than a live behavior.
- **Should `enumerate(since:)` also carry the suppression set**, so the walk skips downloaded assets
  rather than enumerating and then filtering them? Out of scope, but the seam signature changing here is
  the natural moment to consider it.
