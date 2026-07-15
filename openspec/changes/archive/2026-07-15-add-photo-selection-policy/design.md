## Context

`photo-date-cutoff` already establishes the shape this change needs: an authoritative `commonMain` filter in
`UploadCycle`'s resource selection, with the platform fetch predicate as an optimization that "can never widen
or narrow the admitted set". Echo-suppression (`photo-download`) is a second filter of the same shape — an
injected `suspend () -> Set<String>` port, filtered before the engine and before `retainAssets`. This change
adds a third filter to that same seam. The architecture is not in question; the policy is.

Three facts about the existing code constrain the design, and all three were established by reading it rather
than assumed:

1. **The incremental walk takes no predicate.** `PhotoLibraryResourceEnumerator.walk()` uses
   `PHAsset.fetchAssetsWithLocalIdentifiers`, which accepts no `PHFetchOptions.predicate`. Any fetch-level
   filter therefore covers the full enumeration *only*, and a `commonMain` filter is **mandatory** regardless
   of what the predicate can express. This is not a preference — it is forced.
2. **The manifest is fed the raw, unfiltered discovery.** `UploadCycle` passes `discovery`, not
   `liveResources`, to `onDiscovery` (the manifest hook), because `device-manifest` specifies a *device-global
   accumulator* with a *per-event date projection*. Left alone, every excluded photo would land in the
   accumulator, project into `device.json`, enter the event union, and be downloaded by every other member —
   as bytes that were never uploaded. A 404 for everyone.
3. **`retainAssets` prunes the ledger to the admitted set on every full enumeration.** Anything newly excluded
   loses its ledger row the next time a full enumeration runs.

Two of those are hazards. The third is a gift, and the design leans on it.

**Device verification (SE2, iOS 26.5.2).** A throwaway probe measured which `PHFetchOptions.predicate` forms
actually work, against a seeded 25-asset library (all `mediaSubtypes == 0`, so a working exclusion form must
return 25 and the reported-broken form returns 0):

| Predicate | Result | Verdict |
|---|---|---|
| `NOT ((mediaSubtypes & 4) != 0)` | 25 | **works — this is the form we ship** |
| `(mediaSubtypes & 4) == 0` | **0** | **broken**, on the *documented plural* key |
| `creationDate >= … AND NOT ((mediaSubtypes & 4) != 0)` | 25 | works — the real compound shape |
| `pixelWidth < 2000 AND pixelHeight < 1500` | 25 | works |
| `(mediaSubtype & 4) != 0` (singular key) | 0 | silently wrong — **no throw** |
| `hasAdjustments == NO` | — | **SIGABRT** (uncaught `NSException`) |
| `(pixelWidth * pixelHeight) < 3000000` | — | **SIGABRT** (uncaught `NSException`) |

The decade of "exclusion by media subtype is broken" reports is **real, and it is not the singular-key
mistake** it was hypothesized to be: the documented, plural, Apple-sanctioned `mediaSubtypes` key returns zero
rows under the `== 0` form. Note the singular key **silently returns 0 rather than raising** — a
one-character typo would exclude the entire library with no error, which is very likely how this bug earned
its reputation.

**End-to-end confirmation through the real enumerator** (SE2, iOS 26.5.2, after implementation). With a
library of 45 assets — 25 at 64×64 and 20 seeded to straddle the 3 MP floor (`SNAPSYNC_SEED_POLICY`) — the
real `PhotoLibraryResourceEnumerator` and the real policy reported:

```
gallery: enumerated 45 resource(s) since 2001-01-01T00:00:00Z
         (0 over-returned pre-cutoff, 3 suppressed, 35 origin-excluded) → N=10
```

35 excluded (25 tiny + the 10 below-floor half), `N=10` (exactly the above-floor half). This is the
load-bearing evidence: **the shipped predicate returns a superset, not zero.** Had the `== 0` form gone out,
this line would read `enumerated 0` and the library would have emptied silently, with no error anywhere.

**The subtype rule against a real, OS-generated asset.** The resolution floor can be synthesized, but a
subtype bit cannot — `PHAssetCreationRequest` cannot set `mediaSubtype`. So the subtype rule was confirmed
with a real **screen recording** added by hand (it carries `videoScreenRecording`, `1<<19`, the same
subtype-mask mechanism as `photoScreenshot`). A raw-library census plus a `(mediaSubtypes & 524288) != 0`
**select** fetch reported `library total=46, screen-recordings=1`, and the production exclusion predicate then
dropped exactly it (`enumerated 45` = 46−1). Both directions — the select form matches a real asset, the
exclusion form removes it — are device-verified; screenshots share the identical mechanism.

## Goals / Non-Goals

**Goals:**

- Keep photos that were never *taken* — screenshots, screen recordings, GIFs, compressed received media, and
  media in messaging-app albums — out of the event, in both the byte upload and the manifest.
- Make every exclusion decision in tested `commonMain`, none in the untestable app/extension shells.
- Add **zero** per-asset PhotoKit cost. The walk already pays ~110 ms/asset for `assetResourcesForAsset`; the
  policy must not add a second round-trip.
- Fail **include-on-doubt**: never silently drop a real event photo. A photo-sharing app that quietly loses
  photos is broken in a way that a photo-sharing app which uploads a stray meme is not.
- Hold on **both** upload tiers (OS-driven PhotoKit ≥26.1 and app-driven `URLSession` 18–26.0), which follows
  from putting the filter in the shared `UploadCycle`.

**Non-Goals:**

- **Catching full-resolution received media that lives in no album.** AirDrop, WhatsApp "document" mode, and
  Messages saves of uncompressed originals still upload. See the *no camera-origin API* decision below.
- A user-facing album picker. (What Immich and Ente ship. Deliberately deferred — see decision below.)
- Deleting already-uploaded bytes from storage. "No object is ever deleted" stays intact.
- Any backend, wire-format, or stored-data change.

## Decisions

### There is no camera-origin API — so subtract, do not infer

The whole design rests on a negative result. The complete public property surface of `PHAsset` and
`PHAssetResource` was enumerated from the iOS 26.5 SDK headers: there is **no** flag, on any iOS through 26,
saying "this device's camera took this". No originating-application, no creator-app — deliberately, since it
would leak cross-app usage across the sandbox. `adjustmentFormatIdentifier` is the closest thing and it names
the app that *edited* an asset, not the one that created it.

*Alternatives considered.* **EXIF `Make`/`Model`** is a genuine discriminator (camera captures carry it;
WhatsApp/Signal/downloads strip it) but every access path (`requestContentEditingInput`,
`requestImageDataAndOrientation`, `PHAssetResourceManager.requestData`) is a per-asset round-trip *heavier*
than the ~110 ms one already identified as watchdog-dangerous — and under include-on-doubt, "missing EXIF" is
a strong signal, not a certain one, so excluding on it is the wrong posture anyway. **`originalFilename`
allowlisting** (`IMG_####`) is free — the walk already reads it — but AirDropped iPhone photos keep their
`IMG_` name (a miss) while third-party camera apps (Halide, ProCamera) may not use it (a false *drop* of a
real event photo). Both rejected. **`PHAssetExtendedMetadata`** (iOS 27) finally makes `originalFileName`
cheap to batch-fetch; irrelevant to an 18–26 deployment target, but worth revisiting later.

So the policy subtracts categories that are *certainly* not captures, and admits everything else.

### Already free: iCloud Shared and iTunes-synced need no code

`PHAsset.h`, verbatim: *"Fetches PHAssetSourceTypeUserLibrary assets by default (use
includeAssetSourceTypes option to override)."* The existing `PHAsset.fetchAssetsWithOptions` call therefore
already excludes iCloud Shared Album photos and iTunes-synced photos. `sourceType` is **not**
predicate-filterable, and does not need to be. **No code is written for this**; it is recorded here so a
future reader does not "add" a filter that already exists.

### The exclusions are global; the cutoff is per-event — so they land on opposite sides of the accumulator

This is the load-bearing distinction, and it resolves hazard (2) above.

A screenshot is a screenshot in **every** event. The cutoff is a **per-membership** choice. That asymmetry is
what lets the origin exclusions be applied **before** the device-global accumulator without any loss of
per-event flexibility: the accumulator goes on holding every *admitted* asset, and each event's manifest goes
on being the *date-filtered projection* of it, exactly as `device-manifest` specifies today. The cutoff
projection stays exactly where it is.

*Alternative considered:* record an `excluded` flag per accumulator entry and filter in the projection. This
preserves the letter of "the accumulator holds every discovered asset" but buys nothing — no event will ever
want a screenshot — at the cost of a schema change and a second filter site. Rejected.

Concretely: `onDiscovery` is fed the **origin-filtered** (not cutoff-filtered) resource list.

### Include-on-doubt, and what that rules out

Every rule below excludes only on a *certain* signal. This is why the design carries a **resolution floor**
rather than the resolution *allowlist* that first suggests itself.

An allowlist of the device's actual camera dimensions (obtainable at runtime via
`AVCaptureDevice.supportedMaxPhotoDimensions`) is unusable: one camera produces 4:3, 16:9, square, panorama
(arbitrary width), ProRAW, and front-vs-back sizes — and **any cropped photo has arbitrary dimensions**. It
would drop every crop and every panorama. That is exclude-on-doubt with terrible recall.

A **floor** inverts it. Exclude an image only if it is smaller than any camera could plausibly produce:

| Origin | Typical size |
|---|---|
| WhatsApp received | max side 1600 → ~1.9 MP (worst case square, 2.6 MP) |
| Telegram | max side ~1280 → ~1.2 MP |
| Instagram save | 1080×1350 → ~1.5 MP |
| SE2 **front** camera | 3088×2320 = **7.2 MP** |
| SE2 back camera | 4032×3024 = **12.2 MP** |

**3 MP** separates them with a >2× margin below the *weakest* camera on the device.

*And the floor is hardcoded, not derived from `AVCaptureDevice`.* Deriving it yields a **tighter** floor
(12 MP on a modern phone), and tighter means **more aggressive** — more false drops, which is precisely what
include-on-doubt forbids. A low fixed floor is both safer and simpler: pure `commonMain`, unit-testable, no
AVFoundation dependency, and no risk of `AVCaptureDevice` misbehaving inside a background extension. The
device's real camera resolution is information we deliberately decline to act on.

Two guards make the floor safe:

- **`hasAdjustments == false`.** A photo cropped in Photos renders at the cropped size and can fall under the
  floor. Edited assets skip the floor check entirely. (`hasAdjustments` is a free in-memory `PHAsset`
  property — and, per the probe, *not* predicate-filterable, so this guard necessarily lives in `commonMain`.)
- **Videos get a separate, lower floor (1280×720).** 1080p video is 1920×1080 = **2.07 MP — below a 3 MP
  floor**. A shared floor would silently drop every 1080p recording, and 1080p60 is the iOS default. This is
  the single most dangerous false-drop in the design and it is worth the extra rule.

### The `commonMain` filter is authoritative; the predicate is only ever an optimization

Forced by the incremental walk taking no predicate (Context 1), and consistent with the rule
`photo-date-cutoff` already sets for the cutoff. `RawAsset` therefore surfaces `mediaSubtypes`, `mediaType`,
`pixelWidth`, `pixelHeight`, `hasAdjustments` as decision-free facts — all in-memory `PHAsset` properties, so
**no additional per-asset round-trip**; the expensive call (`assetResourcesForAsset`) is untouched.

The predicate carries what it *can* (subtype exclusion; a bounding-box approximation of the floor) purely to
avoid paying the resource round-trip for assets that will be dropped anyway. Given the probe results this is a
narrow, precisely-specified thing:

- The exclusion **must** be `NOT ((mediaSubtypes & N) != 0)`. The `== 0` form returns zero rows.
- The floor can only be a **bounding box** (`pixelWidth < W AND pixelHeight < H`); predicate arithmetic aborts
  the process.
- `hasAdjustments` cannot appear in a predicate at all.

Because over-exclusion by the predicate is the *only* failure that could lose a real photo (the walk would
never return the asset and `commonMain` cannot re-add it), and the shipped form is device-proven to return the
full complement, this is safe. Under-exclusion merely costs a little fetch efficiency, and `commonMain`
catches it.

### Album membership: a decision-free platform verb, a `commonMain` policy

`AlbumManager` gains a verb taking titles and returning member asset ids (bounded by the cutoff). The platform
supplies *facts*; `commonMain` holds the *policy* (which titles), per `event-album`'s existing rule that no
album decision may live in the app shell. Cost is O(albums) — one collection fetch per title — not O(assets).

**Match smart albums by subtype, never by title:** smart-album `localizedTitle` is system-localized
("Screenshots" / "Bildschirmfotos"). User albums — which is what messaging apps create — carry the
app-supplied string verbatim.

**Recall here is honestly poor, and that is accepted.** Only WhatsApp is *confirmed* to create an album
(`"WhatsApp"`, and only when "Save to Camera Roll" is enabled, which is off by default in current versions).
**Most iOS messaging apps save straight to the camera roll and create no album at all.** The denylist is kept
because it is cheap and strictly additive — not because it is a primary mechanism. The resolution floor is
what actually catches compressed messenger media.

### Retroactive cleanup is taken for free, not forced

No migration, no policy-version marker, no forced re-walk. But `retainAssets(admittedSet)` already runs on
every **full** enumeration, and full enumerations happen naturally (change-token expiry, re-join, reinstall).
When one next runs, previously-uploaded screenshots lose their ledger rows and drop out of `device.json` — so
they leave the event union and no new joiner ever sees them.

Bytes already in the bunny zone stay (no object is ever deleted); members who already downloaded a copy keep
it. That is the best achievable without a delete path, and it costs nothing.

*Alternative considered:* clear the discovery token on upgrade to force the cleanup immediately. Rejected —
it buys determinism of *timing* only, at the cost of one full walk (~85 s for 4000 assets on an SE2).

## Risks / Trade-offs

- **[The predicate silently excludes everything if the key is mistyped]** — the singular `mediaSubtype` returns
  0 rows and does **not** raise. → The `commonMain` filter is authoritative and cannot be fooled this way, but
  an over-excluding *predicate* would starve it of assets. Mitigate with an explicit test asserting the walk
  returns a **superset** of the admitted set, and pin the exact predicate string in a spec requirement.
- **[Unsupported predicate keys abort the process, uncatchably]** — Kotlin/Native cannot catch `NSException`.
  → Only device-verified forms ship (no arithmetic, no `hasAdjustments`). Any future predicate key must be
  probed on a device before it is written.
- **[A 1080p video is below the 3 MP image floor]** → Separate video floor at 1280×720. This is the highest-cost
  mistake available in this design; it gets its own requirement and its own test.
- **[A heavily cropped photo falls under the floor]** → The `hasAdjustments` guard. Residual: a photo cropped
  in a *third-party* app that writes a **new** asset carries no adjustments and could be dropped. Judged rare
  and accepted.
- **[Full-resolution received media still uploads]** (AirDrop, WhatsApp document mode, Messages saves) → No
  PhotoKit API can catch it; recorded as a Non-Goal rather than quietly hoped away. If it proves painful in
  practice, the escalation is the user-facing album picker that Immich and Ente both landed on, not a cleverer
  heuristic.
- **[The album denylist rots]** — app-chosen titles, no registry, changeable per release. → Accepted; it is
  additive, not load-bearing. A backend-served list was considered and rejected as a new remote-config surface
  (and one a hostile event could use to *un*-blacklist).
- **[Racing album membership]** — a photo could be discovered between its insert and its album add, and the
  incremental walk sees `PHObjectTypeAsset` changes only, not `PHAssetCollection` ones. → In practice apps add
  to the album in the same `performChanges` block, and `process()` is OS-scheduled minutes later. Accepted.

## Migration Plan

None required — no stored data, wire format, or backend contract changes, and no forced re-enumeration.
Sequenced as behavior-preserving steps so each lands independently:

1. **Spec + capability rename** (no behavior): `git mv` the base spec and this change's delta folder
   `photo-date-cutoff` → `photo-selection-policy` together; update the ~12 live-spec cross-references and ~60
   code-comment citations. Archived changes keep the old name — they are history.
2. `:domain:gallery` — origin facts on `RawAsset` + the enumerator/predicate + in-memory fake.
3. `:capability:album` — the decision-free membership verb + the `commonMain` denylist policy.
4. `:capability:upload` — the authoritative `commonMain` filter, the injected port, and `onDiscovery` fed the
   admitted set. This is where the tests live.
5. `:domain:status` — the same filter in `OwnDeviceGalleryStatusSource`.
6. Composition roots (both tiers), `:test:world` + both harnesses, `:test:integration`.

**Rollback:** the filter is one injected port; supplying an empty exclusion set restores today's behavior
exactly. The rename is a `git mv` and is independently revertable.

## Open Questions

- **Uploads were not driven end-to-end on device.** Event creation is attest-gated (`device-attestation`) and
  the token is not logged, so there is no headless route to a joined event; the policy was verified through
  the real enumerator instead, which is where its entire decision happens — strictly before any HTTP call. The
  byte path is orthogonal and already covered by `:test:integration`.
- The exact denylist titles beyond `WhatsApp` (confirmed) are unverified on current iOS — `Telegram` on iOS
  could not be confirmed, and most messengers create no album. Titles are cheap to add; none are load-bearing.
