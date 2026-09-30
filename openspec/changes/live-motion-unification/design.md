# Design

## Context

See proposal.md for why. The state this design starts from:

- **Planning is platform-blind** (`2026-09-30-android-receiving` D1). Every receiver downloads and stages every
  resource of a foreign asset: `PRIMARY`, plus `LIVE` for a Live Photo. The core hands the gallery one
  `ImportRequest` holding every staged resource, and frees all of them once the import confirms. **Android
  therefore already has the Live Photo's video on disk at import time.**
- **Android import** (`MediaStoreImport`, `:adapter:android`) takes the `PRIMARY` resource and runs pending
  insert → `onPlaceholder(id)` → copy → stamp → publish. `import` is `@Synchronized` and runs on
  `Dispatchers.IO`. A kill before publishing leaves a pending item, which `cleanOrphans` deletes, and the
  startup sweep imports the photo again.
- **iOS import** (`IosPhotoLibraryImporter`, `:adapter:ios:app-only`) runs one `PHAssetCreationRequest` in one
  `performChanges`. Its resources are added with `shouldMoveFile = true`, and the placeholder marker is
  written inside the change block. A `3302`/`3303` failure means the library consumed the files, so the photo
  settles as failed for good.
- **Web zip** (`site/src/pages/join.astro`): the browser fetches the union and keeps `role === "primary"`
  only. `uniqueName` de-duplicates each filename on its own.
- **Measured** (Galaxy A40, Android 11, Google Photos 7.84, 2026-09-30):
  - Google Photos plays JPEG + Google XMP + the iPhone MOV appended unchanged. Either tag set alone suffices.
  - It does not play any HEIC motion-photo layout that was tried.
  - Re-encoding a 12 MP HEIC → JPEG q95 takes ≈ 2.1 s, with a ≈ 48 MB bitmap.
  - Samsung Gallery recognised no format.

## Goals / Non-Goals

**Goals:**

- Convert only in the receiving device's import adapter. The core's planning, staging, download store and
  exactly-once bookkeeping stay unchanged.
- Each import still creates **exactly one** library item, whether it converted or fell back.
- Put the byte-level formats (XMP packets, the JPEG segment layout, locating the trailer) in pure code that is
  unit-tested on the JVM.

**Non-Goals:**

- Samsung's SEF format, Samsung Gallery playback, remuxing MOV → MP4, HDR gain maps on Android, converting
  photos received before this change, and any change to the backend or to uploads.

## Decisions

### D1. Conversion lives in the two import adapters; the core does not know

A received asset is converted where it is imported: `MediaStoreImport` on Android and
`IosPhotoLibraryImporter` on iOS. The `ImportRequest` already carries every staged resource with its role and
content type, which is everything either adapter needs.

- **Rejected: a core step that "prepares" resources before import.** The work is pure platform media work
  (Android's `ImageDecoder`, iOS's ImageIO and AVFoundation). Putting it behind a new port would add a seam
  with one caller per platform, and would force the core to learn what a platform can play, which D1 of phase
  4 kept out.
- **Rejected: converting on the sender, or in the backend.** Both were settled in the design session. The
  sender would double storage and break "only the original is uploaded". The backend (bunny Edge Scripting)
  is no place for image and video work.

### D2. The byte formats are pure codecs in `:domain:model`

`model/` gains a small `motion` codec:

- **`MotionPhotoXmp.build(videoLength, presentationTimestampUs)`** builds one XMP packet carrying **both** tag
  sets:
  - `Camera:MotionPhoto=1`, `Camera:MotionPhotoVersion=1` and `Camera:MotionPhotoPresentationTimestampUs`;
  - a `Container:Directory` with the `Primary` item (`image/jpeg`) and the `MotionPhoto` item (`Item:Length` =
    the video's length). Its mime is `video/mp4` even for an appended MOV, because the spike's packet, which played
    with the MOV unchanged, said so. `video/quicktime` was never measured;
  - the legacy `GCamera:MicroVideo=1`, `MicroVideoVersion=1`, `MicroVideoOffset` = the video's length, and
    `MicroVideoPresentationTimestampUs`.
- **`JpegSegments.insertXmp(jpeg, packet)`** places an APP1 XMP segment after the SOI and any APP0/APP1 EXIF
  segments. When the JPEG already has an XMP segment, the motion properties are merged into its
  `rdf:Description` instead. A packet the codec cannot merge fails the conversion (D6).
- **`locateMotionVideo(xmp, file)`** reads either tag set and returns the video's byte range: the last
  `Item:Length` bytes of the `MotionPhoto` item, or the last `MicroVideoOffset` bytes. When the range is
  implausible or does not start with an `ftyp` box, it returns null.

  (As built, the codec is `MotionPhoto.kt`: `motionPhotoStill`, `locateMotionVideo`, `jpegXmp` and
  `motionPresentationTimestampUs`. It was checked once against the spike's four real motion-photo files.)

These are platform-free and tested on the JVM with synthetic segment fixtures. The spike's files (4–7 MB) are
too large to commit. Both adapters call them.

**Presentation timestamp: −1**, as in the spike, where both tag sets played in Google Photos. Reading the
MOV's still-image time would add a MOV parser to the Android path and buys nothing measurable. This can
change later without touching the spec.

### D3. Android: the motion photo is built in a scratch file BEFORE the one insert

`MediaStoreImport.import`, when the request holds a `PRIMARY` image and a `LIVE` video:

1. **Still → JPEG**, in a scratch file under the app's cache dir, in a fixed scratch directory that `cleanOrphansOnce` empties once per process:
   - **HEIC** (or any image that is not a JPEG): decode it with `ImageDecoder` (`ALLOCATOR_SOFTWARE`, full size,
     never downsampled; the decoder applies the orientation) and compress it with `Bitmap.compress(JPEG, 95)`.
     Then copy the EXIF from the HEIC with the platform `ExifInterface`: `DateTimeOriginal`/`Digitized`, the
     `OffsetTime*` tags, the GPS tags, `Make`/`Model`/`LensModel`, and the exposure tags. Set `Orientation` to
     `NORMAL`.
   - **JPEG**: used byte-for-byte.
2. **XMP**: `JpegSegments.insertXmp` with `MotionPhotoXmp.build(videoLength = LIVE's size, −1)`.
3. **Append** the staged `LIVE` bytes as delivered. The MOV is not remuxed, because Google Photos played it
   unchanged.
4. The existing single pipeline then runs on the scratch file: `decodes` check → pending insert with
   `DISPLAY_NAME` = the still's name with a `.jpg` extension and `MIME_TYPE = image/jpeg` → `onPlaceholder` →
   copy → stamp → publish. The scratch file is deleted in a `finally`.

**Why before the insert:** it is the same exactly-once argument as today, unchanged. Conversion creates no
library item, so a kill during conversion leaves only a scratch file, which the next process's orphan clean-up deletes before its first import. The next import converts again. A failed conversion decides which bytes to insert
**before** the one insert, so a photo can never land twice (still + motion).

**Memory:** `import` is already `@Synchronized`, so conversions run one at a time. At most one full-size bitmap
(≈ 48 MB at 12 MP) is alive per process. It is recycled as soon as it is compressed. An `OutOfMemoryError` is
caught for the decode alone and falls back to the still (D6).

- **Rejected: HEIC motion photos.** Google Photos did not play any layout that was tried.
- **Rejected: re-encoding a JPEG still.** It is lossy for no gain.
- **Rejected: writing into the pending item's stream directly.** A failure halfway would leave an insert to
  discard, and a fallback insert after it. Preparing the bytes first keeps one insert.

### D4. iPhone: a recognised motion photo becomes still + paired video in the ONE creation request

`IosPhotoLibraryImporter.import`, when the request holds a single `PRIMARY` image and no `LIVE`:

1. **Recognise:** `CGImageSourceCopyMetadataAtIndex` on the staged file, looking for the Google namespaces
   (`http://ns.google.com/photos/1.0/camera/` and `…/container/`). ImageIO parses the XMP of both JPEG and HEIC,
   so no hand parser is needed. `MotionPhotoTrailer.locate` gives the video's range, and the range must start
   with an ISO-BMFF `ftyp` box. Anything else is not a motion photo, and the import is today's.
2. **Content identifier:** one fresh UUID per import.
3. **Still:** the image part (the file truncated before the trailer) is copied losslessly with
   `CGImageDestinationCopyImageSource`, adding `kCGImagePropertyMakerAppleDictionary` key `17` = the UUID. It is
   written to the staging directory, on the same volume, so `shouldMoveFile` stays a rename.
4. **Video:** a passthrough `AVAssetReader`/`AVAssetWriter` (`outputSettings = nil`, so no re-encode) writes the
   MP4's tracks into a QuickTime `.mov` with:
   - the top-level `com.apple.quicktime.content.identifier` = the UUID;
   - a timed metadata track carrying `com.apple.quicktime.still-image-time` at the presentation timestamp from
     the XMP, or at 0 when that is −1 or absent.
5. **One creation request** with `.photo` (the still) and `.pairedVideo` (the MOV), both named after the
   sender's filename, and the video with a `.MOV` extension. The placeholder marker is written in the change
   block exactly as today.

The original staged file is **not** handed to PhotoKit on this path, so it survives any failure. That is what
makes D5's fallback possible.

- **Rejected: recognising Samsung's SEF trailer.** It is out of scope until a phase-6 sample lacks the XMP.
- **Rejected: re-encoding the video to HEVC/MOV.** A passthrough keeps the bytes. If a passthrough of a given
  codec is refused, it falls back (D6).

### D5. iPhone fallback: a second attempt inside the same `import` call

When the paired creation fails (`success = false`, so no asset was created), the importer runs a second
`performChanges` with today's single-resource request from the untouched original. Only that second attempt's
verdict reaches `onImportSettled`. The first attempt's in-block placeholder marker names an asset that does
not exist, and the second attempt's marker overwrites it for the same ref. The task verifies that
`onImportPlaceholder` is an overwrite. A kill between the two attempts leaves a marker for an absent asset,
which the startup sweep already reads as "not imported" and imports again, and that import converts again
and falls back again. This matters most for a `3302` (content rejected). Today that would settle the photo
as failed, and here it must not, because the original was never consumed.

- **Rejected: settle the failure and let a later trigger retry the still.** It would need a new "don't convert
  this ref again" record in the core.

### D6. Every conversion failure falls back to today's import, decided before anything is created

These all fall back:

- a decode or `OutOfMemoryError`;
- an encode or EXIF failure;
- an XMP packet the codec cannot merge;
- an unrecognised or implausible trailer;
- a refused passthrough or metadata write;
- a paired creation that PhotoKit rejects (D5).

The fallback is exactly today's still import. The failure is logged at `w` with the ref, and never reported
as an import failure. Android decides before its single insert, and iOS before its settle.

### D7. The spec says "the saved copy may take another format", not "full fidelity" alone

`receiving-photos` promised the original format and filename. The Android copy of a HEIC Live Photo breaks
both. The requirement now says it plainly:

- the gallery copy may take another format **only** to make a moving photo move;
- it keeps resolution, capture date, location and the sender's filename apart from its extension;
- the event's original is untouched for every other member and for the download.

This keeps the promise honest without weakening it for every other photo.

### D8. Web zip: the pair shares one allocated name

`join.astro` keeps a resource when its role is `primary` or `live`. Names are allocated **per asset**. The
still's name goes through `uniqueName` against the stems in use, and the live video is written as the
allocated stem plus the video's own extension. So `IMG_1.HEIC` becomes `IMG_1.HEIC` + `IMG_1.MOV`, and a
colliding second pair becomes `IMG_1-2.HEIC` + `IMG_1-2.MOV`. The union already carries both resources, and the
api does not change. The page is the /join page that phase 5 also touches, so this PR must rebase onto
whichever of the two lands first. Their changes are in different parts of the file.

### D9. Tests

- **JVM (`:domain:model`):** the XMP builder, round-tripped through the trailer locator; `insertXmp` with no
  XMP, with an existing XMP, and with EXIF present; locating the trailer from both tag sets and from real
  sample files.
- **ANDROID_EMU (`AndroidImportContractTest`, beside the other Android-only import facts):**
  - a HEIC + MOV Live Photo imports as ONE `image/jpeg` item whose file is a JPEG + both XMP tag sets + the
    exact MOV bytes at the end, with the capture date, location and camera intact, upright at full size;
  - a JPEG still is kept byte-for-byte ahead of its XMP;
  - a still that cannot carry the motion (it already describes one) imports as the still, once. An undecodable
    original is refused for good before any conversion, as today.

  Playback is asserted on the file's structure only, because a test cannot read what another app plays.
- **IOS_SIM_APP (the rig's `SimulatorAppContracts`):** a Google motion-photo JPEG imports as ONE asset with a
  `photo` and a `pairedVideo` resource and `PHAssetMediaSubtypePhotoLive`. A malformed trailer imports as a
  plain photo.
- **Web:** the pair naming is a pure function, unit-tested beside the page.
- **Hardware, once, before merging the platform PRs, and only with the user's go-ahead:**
  - a received Live Photo plays in Google Photos on the A40, imported by the rig build through MediaStore
    (never `adb push`, never with SnapSync joined to a real event, test photos removed afterwards);
  - a received motion photo plays as a Live Photo on the SE2 (`ios-device` lock).

## Risks / Trade-offs

- **The iPhone path is untested on any device.** Photos may reject a pairing whose still-image-time or
  identifier layout differs from what it expects. → D5 falls back to the still, so the risk is "no motion",
  never "no photo". The simulator clause and one SE2 check come before the iOS PR merges.
- **Losslessly copying a HEIC with ImageIO may be refused.** Some Samsung phones shoot HEIC motion photos. →
  Falls back. Recorded, and phase 6 can measure it.
- **An iPhone "Most Compatible" JPEG may carry an HDR gain map (MPF secondary image).** The motion-photo
  directory then does not list it. → Google Photos locates the video by the trailing length. Checked on the A40
  with a sample in the hardware step. If it breaks, strip the MPF segment or re-encode.
- **The HEIC re-encode drops HDR and is lossy (q95).** → Only for this member's gallery copy. The event keeps
  the original, and the spec says so.
- **≈ 2 s per Live Photo on a slow phone, inside a background wake.** → Imports already run one at a time, and
  the OS's expiry signal ends the wake. An unfinished import is re-run from staging, and the conversion is
  repeatable.
- **Samsung Gallery shows these as plain stills** (measured). → Accepted until phase 6.
- **The /join file overlaps with phase 5.** → A rebase, since the changes are in different blocks.

## Migration Plan

- No data migration. Photos received earlier stay as they were saved (the spec says so), and nothing is
  downloaded again.
- Merge order: this change's specs, then Android, iOS and the web in any order. Each can ship alone, because
  each fallback is today's behaviour.
- **Rollback:** revert the adapter PR. Imports go back to the still, and already-converted photos stay as they
  are.

## Open Questions

- Whether a later player (phase 6) needs the video remuxed to MP4 or the presentation timestamp from the MOV.
  Both change only the Android adapter's bytes, not the spec.
