## Why

The capture-date cutoff bounds *when* a photo was taken, but says nothing about *what it is*. Inside the
event window a member's camera roll also accumulates screenshots, memes, and media received over WhatsApp,
Telegram, Messages and the browser — and every one of those uploads to the event and downloads onto every
other member's phone. The cutoff was built because "back up everything of mine" becomes "upload a guest's
whole camera roll to a stranger's event"; origin exclusion is the other half of that same sentence.

Screenshots are the sharpest case: they are the highest-frequency non-camera asset in a typical library, they
are trivially identifiable (`PHAsset.mediaSubtypes` carries a `photoScreenshot` bit), and there is no reading
of "photos from the event" under which one belongs.

## What Changes

- **The `photo-date-cutoff` capability is renamed `photo-selection-policy`** and generalized from "which
  dates are in scope" to "which of my photos enter this event". The cutoff becomes the policy's first rule,
  not the whole of it. Every existing cutoff requirement is preserved verbatim in behavior; only the framing,
  the capability name, and the three requirements that name the cutoff as *the* filter are restated over the
  policy. **No behavior is removed.**
- **Origin exclusions are added to the shared upload cycle's selection**, as a second authoritative
  `commonMain` filter beside the cutoff. A resource is excluded when its owning asset is any of:
  - a **screenshot** or **screen recording** (`mediaSubtypes` bits `1<<2` / `1<<19`);
  - an **animated image** (primary resource MIME `image/gif`);
  - **below a resolution floor** — an image under 3 MP, or a video under 1280×720 — **unless the asset has
    adjustments** (an edited/cropped capture renders small and must not be mistaken for compressed received
    media);
  - a member of a **denylisted album** (case-insensitive exact title match against a `commonMain` list of
    messaging/social app albums).
- **`RawAsset` gains five decision-free facts** — `mediaSubtypes`, `mediaType`, `pixelWidth`, `pixelHeight`,
  `hasAdjustments` — so every exclusion decision is made in tested `commonMain` and none in the untestable
  app/extension shells. All five are in-memory `PHAsset` properties, so the walk pays **no** additional
  per-asset round-trip.
- **Album membership becomes readable** via a new decision-free verb on the existing `AlbumManager` seam
  (titles in → member asset ids out, bounded by the cutoff). The *policy* — which titles — stays in
  `commonMain`. Cost is O(albums), not O(assets).
- **The PhotoKit fetch predicate additionally narrows by subtype and bounding box**, as an optimization only.
  Device-verified constraints (see design): the exclusion **must** be written `NOT ((mediaSubtypes & N) != 0)`
  — the documented `(mediaSubtypes & N) == 0` form returns **zero rows**; predicate **arithmetic**
  (`pixelWidth * pixelHeight`) and `hasAdjustments` each **abort the process**.
- **The own-device status total `N` and the device manifest are gated by the same admitted set**, so the
  status screen can still reach 100% and an excluded photo cannot leak into the event union via `device.json`.

Not a breaking change to any stored data, wire format, or backend contract. No migration: the exclusions take
effect on the next enumeration, and already-uploaded excluded assets drop out of the manifest whenever a full
enumeration next occurs naturally (see design).

## Capabilities

### New Capabilities

None. The policy is the renamed `photo-date-cutoff`, not a new capability beside it — the cutoff and the
origin exclusions share one enforcement point (the upload cycle's resource selection), one status
consequence (`N`), and one manifest consequence. Splitting them would duplicate exactly the hard parts.

### Modified Capabilities

- `photo-selection-policy`: **renamed from `photo-date-cutoff`** (the delta model cannot express a capability
  rename; per the `2026-07-04-add-url-session-upload` precedent, base spec and delta folder are `git mv`d
  together by task 1). Purpose generalized to the full selection contract.
  Three requirements restated over the policy rather than the cutoff alone ("gates both byte upload and
  manifest listing", "scopes the own-device status total", "byte-upload filter over the shared upload cycle").
  New requirements added for the origin-exclusion rules, the include-on-doubt posture, and the album denylist.
- `gallery-status`: the decision-free raw-asset walk seam SHALL additionally surface the five origin facts on
  `RawAsset`, and its `PHFetchOptions` predicate SHALL additionally narrow by media subtype and bounding box
  (with the device-verified form constraints above) while remaining an optimization that can neither widen nor
  narrow the admitted set. Separately, the own-device count `N` SHALL be scoped by the full policy, not by the
  cutoff alone (today it names only the cutoff, which would peg the screen below 100% once exclusions land).
  *(The fetch-predicate requirement lives here, not in `ios-photokit-upload`, which needs no delta.)*
- `device-manifest`: the device-global accumulator SHALL hold every **admitted** discovered asset rather than
  every discovered asset — origin exclusions are applied **before** it. This is safe precisely because the
  exclusions are **event-independent** (a screenshot is a screenshot in any event) while the cutoff is
  per-event; the per-event date projection over the accumulator is therefore unchanged.

## Impact

**Specs:** `photo-date-cutoff` → `photo-selection-policy` (base + delta `git mv`); deltas to `gallery-status`
and `device-manifest`. Cross-reference sweep: 13 citations of `photo-date-cutoff` in live specs
(`bunny-list-endpoint`, `deeplink-config`, `device-manifest`, `gallery-status`, `join-event`) and 73 in code
comments across 38 files. Archived changes keep the old name — they are a historical record.

**Code:**
- `:domain:gallery` — `RawAsset`/`RawResource` gain the origin facts; `PhotoLibraryResourceEnumerator` reads
  them and extends its `PHFetchOptions` predicate; `InMemoryRawAssetSource` mirrors it.
- `:capability:upload` — `UploadCycle` gains an injected exclusion port beside `suppressedAssetIds`, and the
  authoritative `commonMain` origin filter; `onDiscovery` is fed the **admitted** set.
- `:capability:album` — new decision-free album-membership verb on `AlbumManager` + `IosAlbumManager`; the
  denylist policy and its coordinator in tested `commonMain`.
- `:domain:status` — `OwnDeviceGalleryStatusSource` applies the identical filter, or `N` never reaches 100%.
- `:app:ios`, `:app:ios:photokit-extension` — composition-root wiring of the new port (both tiers).
- `:test:world`, `:app:desktop`, `:app:desktop:ui` — world fakes and both harnesses gain levers to forge an
  excluded asset, so the policy is exercisable without a device.

**Tests:** `UploadCycleTest` (exclusion matrix, incremental + full enumeration, interaction with cutoff and
echo-suppression), `RawAssetMappingTest`, `OwnDeviceGalleryStatusSourceTest`, album-coordinator tests, and a
`:test:integration` case asserting an excluded photo neither uploads nor appears in the manifest.

**Non-Goals / known gap:** full-resolution received media that lives in no album — AirDrop, WhatsApp
"document" mode, Messages saves of uncompressed originals — still uploads. PhotoKit exposes **no**
camera-origin API (verified against the iOS 26.5 SDK headers); Immich and Ente both hit this wall and shipped
user-facing album pickers instead. A picker is deliberately out of scope here.
