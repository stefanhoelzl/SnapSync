## Context

`decouple-event-window-from-lifetime`'s on-device smoke (task 11.1) surfaced a bug: a member of a
closed-window event uploads their in-window photos, but the status screen sticks at
"Synchronization pending…" and two post-ceiling photos sit in the device manifest with no bytes uploaded.

Root cause is a **drift class**, not a line. `photo-selection-policy` declares "one policy, applied at one
place," yet the policy is re-stated at **four** consumers, each assembling the rules by hand:

```
                    Contribution.Since(cutoff, until)
    ┌───────────────┬───────────────┼───────────────┬────────────────┐
    ▼               ▼               ▼               ▼
 ① byte upload   ④ join preview   ③ status N      ② device manifest
   cutoff+until    cutoff+until     cutoff ONLY     cutoff ONLY  (floor-only projection)
   ✓               ✓                ✗ dropped        ✗ dropped
```

`add-event-date-range` added the ceiling (`until`) to ① and ④ and missed ② and ③. `Contribution.Since ->
contribution.cutoff` (dropping `until`) compiles as readily as the correct form; every fixture used
`until = null`, so the whole suite was blind. The spec itself re-enumerates the policy per consumer
(`#364`/`#401` say "cutoff"; `#657` says "range"), so the code faithfully implemented a spec that had
already drifted.

The fix must make the admitted set a **single thing consumers receive**, not a policy they re-apply — in
both the spec and the code.

This document captures a deep exploration. Delivery scope is intentionally open (see *Sequencing*).

## Goals / Non-Goals

**Goals:**
- Every consumer of the selection policy derives its answer from **one** admitted set; a consumer cannot
  see or count a disallowed asset.
- Adding a rule (or a bound) is one edit; consumers are updated by construction, not by remembering.
- Platform specifics (native fetch predicates, PhotoKit ABI) live in the platform layer; `model/` is
  platform-neutral.
- The date-role swap that caused this bug is a compile error, not a convention.
- Preserve the measured platform constraints: LIMITED's no-autonomous-read discipline, the walk's
  liveness (bounded by the lower cutoff), and off-device testability of the *policy* logic.

**Non-Goals:**
- No change to what the policy *admits* (the rules are unchanged; only where/how they are applied).
- No backend change (the manifest wire shape and the union are unchanged).
- Not building multi-event membership; the device-global-accumulator forward-prep is *removed*, not
  deepened.

## Decisions

### D1 — `EventPhotoSet`: the admitted set as a first-class abstraction
Introduce a per-event `EventPhotoSet` that exposes only **admitted** assets. Consumers ask it for the set
(or a count, or resources); they never hold a raw asset alongside a policy they might forget to apply.
This is strictly stronger than "pass the policy object": it makes illegal states unrepresentable rather
than merely avoidable. *Rejected:* a shared `admits()` predicate consumers call — a consumer can still
hold an asset and not call it (the bug).

### D2 — One policy, applied at query, over an injected `CandidateSource`
```
   EventPhotoSet.assets = candidateSource.candidates(policy).filter { policy.admits(it) }
```
Admission appears **exactly once** (inside `EventPhotoSet`). The three backings (full-walk, incremental
accumulator, LIMITED snapshot) are `CandidateSource` impls; only `candidates()` varies. Admission is
applied at **query**, never at ingest — so no backing can pre-filter wrong (the origin-early/date-late
freedom that leaked the ceiling). *Rejected:* mode-specific `EventPhotoSet` classes — each would write
`filter { admits }` again, re-creating the drift three ways.

### D3 — Interface: a cost ladder; resources per-asset and lazy
```kotlin
interface EventPhotoSet { suspend fun count(): Int; suspend fun assets(): List<Asset> }
interface Asset { val assetId; val creationDate: CaptureDate; suspend fun resources(): List<Resource> }
```
`count`/`assets` are facts-only (cheap); `Asset.resources()` pays the ~110ms PhotoKit resource read, and
**only for admitted assets** you iterate. Admission is decidable on facts alone (GIF-on-doubt = false when
resources absent), so the count path never reads resources. This also reorders the pipeline to be *faster*
than today: filter-then-fetch instead of fetch-then-drop.

### D4 — Per-process: the policy is the universal, the state is local
On iOS ≥26.1 the cycle (manifest+upload) runs in the **extension** and `N`/preview run in the **app** —
different processes. So `EventPhotoSet` is **not** one universal object:
- the **policy** (admission logic, `model/`) is compiled into both processes — the shared "one place" that
  makes the bug impossible;
- `EventPhotoSet` has **per-process impls** — stateful (cycle-side, durable) vs stateless full-walk
  (app-side). Same interface, same policy, different backing.

### D5 — Native narrowing: platform translates the domain rules; layer-1 is authoritative
Two layers. **Layer 1** (in `EventPhotoSet`, in-memory) enforces **every** rule — this decides admission.
**Layer 2** (the platform `CandidateSource`) narrows what the walk returns, an optimization that *never*
decides admission (`#440`: a platform fetch can neither widen nor narrow the admitted set). The policy
exposes `rules: List<SelectionRule>` (sealed domain types); the iOS source `when(rule)`-translates the
ones it can into `PHFetchOptions` (with the device-verified exact syntax) and ignores the rest.
- A hint is **advisory** — an omitted/incomplete hint costs only performance, because layer 1 re-filters.
- **Exception:** the capture-date *lower bound* must be pushed, or the walk goes unbounded and the process
  is watchdog-killed before layer 1 runs. That is a **liveness** property of the walk (not a correctness
  property of admission — all rules are equally load-bearing there), so it is a **source contract**
  (adapter-test-verified), not a domain hint.

*Correction recorded:* an earlier framing called the lower bound "the load-bearing rule." It is not —
every rule is load-bearing for admission; the lower bound is only special for the *walk's liveness*.

### D6 — Policy model: sealed rules; `Contribution` folds in
```kotlin
sealed interface SelectionPolicy {
    data object None                                        // non-contributing → empty set, no walk
    data class Admitting(val rules: List<SelectionRule>)    // rules.all { admits }
}
```
Rules: `CaptureAfter(CaptureCutoff)` · `CaptureBefore(CaptureCeiling)` · `ExcludeScreenshots` ·
`MinImageArea` · `NotEcho(Set)` · `NotInDenylistedAlbum(Set)` · … Each is a pure `admits(Asset)`; the
platform inspects the sealed set for translation (D5). `Contribution` (direction + range) folds entirely
into this — `None`/`Admitting` subsume `None`/`Since` — and is **deleted**. The policy stays a pure value;
`EventPhotoSet` reads the effectful sets (echo, album) from ports per query and assembles it.
**Constructed once**, from typed `EventConfig` fields, by name — closing the four-site, positional-String
construction that made the date-role swap possible.

### D7 — Global value-class dates; ceiling required
Distinct role types over a canonical `…Z` string, comparing by string (the lexicographic invariant
survives) and serializing transparently (the wire and persisted config are byte-identical, so the backend
is untouched and legacy configs still parse *shape*-wise):
```kotlin
@JvmInline value class CaptureDate(val iso: String) : Comparable<CaptureDate>
@JvmInline value class CaptureCutoff(val at: CaptureDate)   // + CaptureCeiling, EventStart, EventEnd, …
```
Distinct role types make the privacy-critical swap (`startsAt` where `minPhotoDate` is wanted — which
*lowers* the floor and leaks excluded photos) a **compile error**, and force the `createdAt`-millis-vs-
canonical heterogeneity to be modelled (killing the backend `deleteByMs` trap).

**The ceiling becomes required** (`maxPhotoDate` non-optional); the unbounded fallback and the reconcile
backfill of an *absent* ceiling are removed. This is safe *only by sequencing* — see D7a. It **inverts**
`EventConfig`'s standing decode-safety invariant (a pre-ceiling blob now fails to decode → lost
`eventId`/QR), acceptable for the TestFlight-internal, controlled installed base, and **recorded as a
deliberate reversal**. *Rejected alternatives:* a far-future sentinel ceiling (keeps the type total without
the bet — the safer option, chosen against in favour of the clean end state); an explicit
`CaptureCeiling.Unbounded` sealed case (safest, but keeps the case we wanted gone).

### D7a — The decode-ordering gate (why removing the fallback constrains sequencing)
The reconcile backfill (which fills a legacy config's ceiling) needs the config to **decode first**, and
decouple's *tolerant* decode does that. The strict decode (ceiling required) would reject the same blob.
So the order is forced: `pre-ceiling → decouple (tolerant, backfills) → this change (strict)`. A device
that skips decouple and jumps to the strict decode loses its event. This is a hard ship-gate — and a
direct argument that the work is a **sequence**, not one change.

### D8 — Neutral fact vocabulary; the mapping is platform code
`model/` must never see a PhotoKit bitmask. The iOS adapter maps `PHAsset → AssetFacts`
(`isScreenshot`, `isScreenRecording`, `isVideo`, `imageArea`, `isEdited`, `isGif`, `creationDate`) — the
bit constants and `(subtypes & bit) != 0` logic live in `iosMain` where they belong; the pinned OS
literals move there (still pinned). The **rules** read only neutral `AssetFacts`.
- *Trade:* the interpretation is now tested on `iosSimulatorArm64Test` (macOS CI), not JVM. Correct home
  — the SDK constants can only be truly verified on-platform; a JVM test would assert against a *copy*
  (the drift `RuntimeIdentityTest` exists to catch).
- *Win:* the **policy** logic stays in `commonTest` (JVM + simulator) and gets cleaner — tested with
  `isScreenshot = true`, not a hand-built bitmask. The `InMemoryRawAssetSource` fake produces neutral
  facts directly.

### D9 — The manifest derives from an enriched ledger; the accumulator is eliminated
The device-manifest accumulator and the upload ledger both maintain the device's asset set incrementally
and prune on deletion — the same job, different columns. Enrich the ledger row
(`+ creationDate, role, contentType, filename`) and make the manifest a **view of the ledger's COMPLETED
rows, date-filtered to the current event window**. The accumulator disappears; the cycle-side
`EventPhotoSet`'s full-state *is* the ledger.
- *Behavior change:* the manifest lists **completed** (uploaded) resources, not **discovered** (intent).
- *Verified:* the union's byte-presence check downgrades from load-bearing (it hid unloaded assets) to
  **defense-in-depth** — a named byte can't be GC'd (the sweep protects manifest-referenced bytes; the
  decouple change's main-region reads close the race). Keep the check; it now catches only a
  COMPLETED-but-absent edge.
- *Cost:* a wider hot keyed-lookup row (small strings); the `eventId` column stays *provenance* (the
  manifest filters by the current event's window, not the row's `eventId` — fine for single-membership v1).

### D10 — LIMITED: the snapshot as a `CandidateSource`
LIMITED is one `CandidateSource` impl (Snapshot), fed the current selection at the sanctioned read points
(cold baseline + `PhotoSelectionChangeSource` observer emissions) — the no-autonomous-read discipline
lives entirely in the source's construction; `EventPhotoSet` and the policy are permission-oblivious. No
native narrowing (no walk); layer-1 filters the small snapshot.
- **Corrected understanding:** the `.limited` alert is iOS's *"Select More Photos"* nag on **every**
  library touch — independent of whether the asset is granted. The archived probe
  (`2026-07-20-accept-limited-photo-access/PROBE-FINDINGS.md`) measured that
  `PHPhotoLibraryPreventAutomaticLimitedAccessAlert` does **not** reliably suppress it: off-flow **fetches**
  queue alerts that survive process death. So snapshot-fed *discovery* is mandatory and settled.

- **✅ RESOLVED by device spike (SE2, iOS 26.5.2, `.limited`, plist suppression ON).** The probe measured
  *fetches*; it never isolated `assetResourcesForAsset`. Spike: hold the baseline `PHFetchResult` refs
  (the sanctioned in-flow read), then run **6 off-flow bursts, 10 s apart, of `assetResourcesForAsset` on
  those held refs** — no fresh fetch, so the resource read is isolated from the fetch. 5 selected assets,
  9 resources per burst (54 reads). **Result: zero alerts** — clean app screen at t≈25/50/75 s during the
  bursts, and a **bare home screen 12 s and 32 s after SIGKILL** (the probe's signature was queued alerts
  draining post-kill). The **fetch-vs-resource-read distinction is real**: library *queries* storm;
  reading resources of an already-held asset does not.

  *Caveats on the strength of the claim:* per-round timing was not logged, so rounds 1–5 may have been
  PhotoKit cache hits rather than fresh XPC round-trips — the airtight claim is "at least one off-flow
  resource-read pass does not alert." And it was measured **foreground only**: in a first run the app was
  backgrounded and iOS *suspended the coroutine*, so the bursts did not fire at all. That gap matters less
  than it appears — under `.limited` the OS never invokes the PhotoKit extension, so LIMITED uploads run in
  the **app process**, which is what was measured.

  *Consequence:* a lazy per-asset `resources()` under LIMITED looks **viable**, so D3's single lazy path can
  serve both grants and the LIMITED snapshot can carry **facts only** (cheaper capture). Given the caching
  caveat, implement it behind the same `Asset.resources()` seam so reverting to pre-capture is a
  one-source-impl change if a storm ever appears in practice.

## Risks / Trade-offs

- **[Ceiling-required inverts decode safety]** → D7/D7a: deliberate, recorded, and gated on decouple's
  backfill running first on the controlled installed base; `SNAPSYNC_RESET_STATE` clears any holdout.
- **[Manifest lists completed, not discovered]** → D9: the union already tolerates it (byte-check as
  defense-in-depth); a foreign member sees an asset when its bytes land + the manifest re-projects, same
  observable timing.
- **[Mapping loses JVM test coverage]** → D8: correct home for platform ABI; the policy logic keeps full
  off-device coverage and gets cleaner.
- **[Large surface]** → the design spans several separable refactors; delivering as a sequence (below)
  keeps each diff reviewable and each step independently green.

## Sequencing

The pieces stack by dependency, and D7a *forces* an ordering, so a **sequence** is the recommended shape:

1. **Global value-class dates** — foundation; kills the swap class; wire-transparent so it's behavior-
   preserving. (Ceiling-required is deferred to the step that can rely on decouple's backfill.)
2. **One-policy admission over typed dates** — `SelectionPolicy` sealed rules, `Contribution` folded in,
   the four consumers routed through a single `admits`. **This fixes the ceiling bug** (task 11.1), early.
3. **`EventPhotoSet` + `CandidateSource` + neutral `AssetFacts`** — the abstraction and the platform
   translation/mapping seams.
4. **Manifest-from-enriched-ledger** — eliminate the accumulator.
5. **Ceiling-required + fallback removal** — last, once decouple has backfilled every device (D7a).

Alternatively one change, accepting a very large diff and leaving the bug open until it all lands.

## Open Questions

- Device-verify: lazy `resources()` under LIMITED with alert suppression (D10) — decides whether LIMITED
  needs source-specific resource backing.
- Scope: sequence (recommended) vs one change.
- Whether the union's byte-check can eventually be *removed* (not just downgraded) once the manifest is
  ledger-derived — a later simplification, out of scope here.
