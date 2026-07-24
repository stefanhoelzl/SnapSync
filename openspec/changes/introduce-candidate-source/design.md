## Context

`introduce-eventphotoset` gave `EventPhotoSet` the seam its own design (D2/D5) called for:

```kotlin
class EventPhotoSet(
    private val policy: SelectionPolicy,
    private val candidates: suspend (SelectionPolicy) -> List<Candidate>,
)
```

and then wired it to eager lists. **All nine call sites ignore the parameter.** Every consumer fetches
first and hands over a finished collection, so the policy never reaches the platform that could act on it.

Because the fetch sits outside the abstraction, each consumer flattens the policy to a cutoff string and
relays it by hand through three stacked ports:

```
UploadCycle ─→ BackgroundTransfer.discoverResources(token, since: String)
                 └→ IosDiscovery.discover(token, since)
                     └→ PhotoLibrary.enumerate(since)
                         └→ ResourceEnumerator
                             └→ RawAssetSource.walkSince / walk / factsSince(since)
                                 └→ fetchOptionsSince(since)     ★ the only consumer
```

Seven layers; six pure pass-through. Three ports for one need — *read the photo library, bounded* — where
`PhotoLibrary` is `RawAssetSource` composed with a pure mapping and `ResourceEnumerator` is that
composition and nothing else.

Two consequences are live in production:

**The walk pays for what it discards.** `walkSince` issues `assetResourcesForAsset` (~110 ms/asset on an
SE2) for every date-passing asset *before any origin rule runs*. Sub-floor images, edited assets and
post-ceiling photos each cost a full round-trip and are dropped immediately after. `N` re-pays this on
every foreground for a count that needs no resources at all.

**The preview and `N` disagree.** `isGif` is read from a *resource's* MIME. The preview walks facts-only,
cannot see it, and admits on doubt; `N` walks eagerly, sees it, and excludes. Both claim to be "the
admitted set", and for a library with an in-scope GIF the preview over-counts — the same divergence class
as the ceiling bug the previous change removed.

This design was settled by interview; the decisions below record what was chosen and what was rejected.

## Goals / Non-Goals

**Goals:**

- The policy reaches the platform **directly**, so native narrowing is derived by translating the policy's
  own sealed rules rather than by re-stating a bound someone flattened.
- One read seam, not three. `since: String` disappears from the codebase.
- Every consumer resolves the *identical* admitted set, cheaply — no consumer trades accuracy for cost.
- Resources are read only for assets already admitted (filter-then-fetch, not fetch-then-drop).
- The measured `LIMITED` read discipline is preserved exactly: every library **fetch** stays in-flow.

**Non-Goals:**

- No change to *which* assets the policy admits, beyond the deliberate GIF removal recorded below.
- No backend change; the manifest and union wire shapes are untouched.
- Not building multi-event membership; the single-membership contract is unchanged.
- Not reworking the change-token cursor or the ledger.

## Decisions

### D1 — Remove the GIF exclusion; admission becomes facts-only

`ExcludeGif` is the **only** rule not decidable from an asset's own cheap properties, and therefore the
only reason admission ever needed a resource read. It is also the sole cause of the preview/`N` divergence.

The 3 MP image floor already excludes every ordinary GIF (a messenger GIF is ~0.13 MP; a Live-Photo→GIF
export is downsized by the exporting app). What is no longer excluded is an **edited** GIF — the floor is
skipped for `hasAdjustments` — or one at **≥3 MP**. Both are rare, and both fall on the side the policy
already declares: *a stray uploaded meme is harmless and visible; an event photo that silently fails to
upload is invisible and unfixable.*

Removing one rule of modest recall buys: single-phase admission, `count()` exact **and** cheap at every
consumer, preview and `N` in agreement for the first time, and `Candidate.resources()` demoted to a pure
cost ladder with no correctness role.

*Rejected — keep it and make admission two-phase* (facts, then resources, then re-admit). Correct, but it
makes facts-admitted a **superset** of fully-admitted, which every consumer must then reason about. Had
`count()` answered phase 1 alone, `N` would over-count GIFs and the status screen would peg below 100%
forever — the exact failure this project has now hit twice.

*Rejected — derive "animated" from `PHAsset.playbackStyle == .imageAnimated`*, keeping the rule as a fact.
The best outcome if it holds, and worth revisiting: Live Photos report `.livePhoto`, so no camera capture
should be caught. But it is an unverified platform claim, and the laws require a forcing proof before
anything is built on one. It would need a device spike this change does not want to block on.

### D2 — Two seams, both taking the policy

```kotlin
interface CandidateSource {                                  // app side: N, preview
    suspend fun candidates(policy: SelectionPolicy): List<Candidate>
}

interface BackgroundTransfer {                               // cycle side
    suspend fun discoverResources(sinceToken: ByteArray?, policy: SelectionPolicy): Discovery
}
```

A resumable incremental walk carries cursor state — `nextToken`, `removedAssetIds`, `fullEnumeration` —
that a count has no use for. Ports are named for the need, and these are two needs.

The obvious objection was checked: the app side *looks* like it wants the cursor, since `N` re-enumerates
the whole in-scope library on every foreground. But a cursor yields **changes** and `N` needs a **count of
the current set**; maintaining that from a change feed requires durable state — which is what the ledger
already is. The app side cannot use a cursor without becoming a second ledger.

*Rejected — one source returning a batch with optional cursor fields.* It makes an invalid state
representable: a ledger projection or a selection snapshot holding a `nextToken` it must invent.

### D3 — `RawAssetSource`, `PhotoLibrary` and `ResourceEnumerator` collapse into `CandidateSource`

An earlier draft kept `RawAssetSource` beneath the new port, to hold the pure fan-out mapping
(`resourcesFrom`, `uploadKey`, `resourceRole`, `normalizeAssetId`) on the JVM test loop. **That was wrong**:
the goal needs a pure *function* in `model/`, not a *port*. The adapter calls it per-asset from
`Candidate.resources()`, and it stays covered on JVM and simulator exactly as now.

Keeping it would also have been actively harmful. A `RawAssetSource` returning plain facts gives
`Candidate.resources()` nothing to close over but an id — forcing a re-fetch by local identifier at read
time, which is the off-flow fetch pattern the `limited-photo-access` probe measured as storming. A
candidate that closes over the platform asset reference avoids that by construction.

### D4 — Resources are read lazily, and only for admitted assets

With D1 in place, nothing after the resource read can reject an asset, so laziness is safe as well as
cheaper. This is the cost ladder `introduce-eventphotoset` documented and never delivered on the walking
path: `count()` and `assets()` cost facts only; `resources()` pays the round-trip, for admitted assets
only.

### D5 — The `LIMITED` snapshot is a candidate source, read eagerly, and `SelectionScopedTransfer` survives

Two independent questions were being conflated: *how* the snapshot is read, and *what interface* it is
expressed through. They resolve differently.

**Interface:** the app-side snapshot becomes a `SnapshotCandidateSource`. That is D5 of the previous
design, and it removes `OwnDeviceGalleryStatusSource.refreshFrom` and the `GRANTED`/`LIMITED` arm of
`ShareableCountSource`.

**Read discipline: unchanged, eager.** The measured storm is caused by an autonomous library **fetch**, not
by reading an already-fetched asset's resources. The eager read at the sanctioned points is the mechanism
that keeps every fetch in-flow. Deferring it would mean either holding platform references across the
snapshot cell — making storm-safety rest on an invariant no type expresses, whose regression is an
app-killing storm that survives process death and which no test can catch — or re-fetching by identifier
off-flow, which is the storm itself. The saving would be negligible: a limited selection is hand-picked and
small.

**`SelectionScopedTransfer` survives**, on the cycle seam. Its `fullEnumeration = false` is what stops
`retainAssets` pruning the ledger down to the current selection, which would strip the `COMPLETED` row from
every photo not presently selected and re-upload it. Under `LIMITED` the mode switch means three things —
*these candidates*, *don't prune*, *don't advance the cursor* — and only the first is candidate production.
A `CandidateSource` returning `List<Candidate>` carries the first and silently drops the other two.

### D6 — The permission split moves into the source

A permission-aware `CandidateSource` is assembled in `compose/` and delegates to the full walk under
`GRANTED` or the snapshot under `LIMITED`. Consumers stop branching: `refreshFrom` is deleted and
`ShareableCountSource` loses its permission arm — *"the mode difference is one source impl, not a branch in
the policy or its consumers"* (`limited-photo-access` D10), which until now was true of the policy and
false of the consumers.

The `DENIED` / `NOT_DETERMINED` check **stays** with the consumer. "Where do candidates come from" and "can
we answer at all" are different questions, and the second has a distinct rendering (no row, not zero).

### D7 — Consumers hold the source; `EventPhotoSet` is built per query

Consumers take a `CandidateSource` by constructor, exactly as `OwnDeviceGalleryStatusSource` takes
`PhotoLibrary` today, and construct `EventPhotoSet(policy, source)` per call — the policy varies per query
(the preview sweeps candidate ranges) while the source does not.

*Rejected — keep the lambda seam.* A lambda is precisely what let nine call sites ignore the policy
parameter without a compiler complaint.

### D8 — The incremental id-scoped fetch is internal to the cycle's discovery

Only the cycle has identifiers to scope by, and it already owns the change-token logic. The shared port
therefore has **one** method; `IosDiscovery` builds candidates from its own full or id-scoped fetch using
the same internal mapping.

*Rejected — a second `candidates(ids, policy)` on the shared port*, which would put an upload-only concern
on the seam the preview and the status total also use.

## Risks / Trade-offs

- **[An edited or ≥3 MP GIF now uploads]** → Accepted, and it is the policy's declared direction rather
  than an exception to it. Revisitable via the `playbackStyle` spike (D1) if it proves to matter.
- **[Reworking the walk path just before a release]** → This is where a mistake costs a silent
  whole-library re-upload. Mitigated by: the ledger is untouched, `SelectionScopedTransfer`'s prune
  suppression is explicitly preserved (D5), and the on-device re-run of the closed-window scenario covers
  the same path.
- **[The `LIMITED` path is touched at all]** → Only its *interface* changes; the read points, their
  eagerness, and the transfer wrapper are unchanged by construction (D5). The device check is a joined
  `LIMITED` launch confirming the sanctioned reads still fire and no alert appears.
- **[Lazy resources change when PhotoKit calls happen inside `process()`]** → Total work is unchanged for
  admitted assets and strictly lower for rejected ones, so the extension's ~3 minute budget is never worse.
- **[Fewer eager reads could mask a latent resource-read failure until upload time]** → A resource read
  that fails now surfaces during job creation rather than during the walk. The cycle already treats a
  failed job creation as retryable, so the disposition is unchanged.

## Migration Plan

No data migration: no persisted format changes. The ledger, the discovery cursor, the manifest and the
config are all untouched.

Ships **in the same release** as `decouple-event-window-from-lifetime` and `introduce-eventphotoset`. It
supersedes `introduce-eventphotoset` task 4.2, which proposed threading the policy through the seven-layer
relay instead of removing it; that task is marked superseded rather than implemented.

Rollback is a revert: the only observable behavior change is the GIF admission, and no device state records
it.

## Open Questions

- Whether `PHAsset.playbackStyle == .imageAnimated` would let the animated-image rule return as a *fact*
  (D1). Needs a device spike; deliberately not blocking this change.
- Whether the `Resource.metadata` origin-fact round-trip (`RESOURCE_META_IS_SCREENSHOT` and siblings) can
  eventually be deleted. It survives only for the `LIMITED` snapshot, which starts from resources rather
  than from a walk. Removing it means making the snapshot facts-only — see D5 for why that is not free.
- `introduce-eventphotoset`'s `limited-photo-access` delta states the snapshot carries **facts only** with
  lazy resources. That describes neither what was built (`candidatesFromResources` — eager, held) nor what
  D5 concludes. It is unarchived and unshipped, so the discrepancy should be corrected in that change
  rather than layered over here.
