# Tasks

## 1. Motion-photo codecs (`:domain:model`, PR "android")

- [x] 1.1 Add `MotionPhoto.packet` / `MotionPhoto.description(videoLength, presentationTimestampUs)`. It emits both tag sets: `Camera:MotionPhoto` with a `Container:Directory` of `Primary` and `MotionPhoto` items, and the legacy `GCamera:MicroVideo*`. Verify: a JVM test parses the packet and reads every property back.
- [x] 1.2 Add `motionPhotoStill(jpeg, videoLength)`, which places an APP1 XMP segment after SOI/APP0/EXIF, or merges into an existing XMP `rdf:Description`, and fails on a packet it cannot merge. Verify: JVM tests for a JPEG without XMP, one with XMP, and one with EXIF. The image data after the segments stays byte-identical.
- [x] 1.3 Add `locateMotionVideo(xmp, file)` for both tag sets, attribute and element form. It returns null on an implausible range or one with no `ftyp`. Verify: JVM tests over the builder's output and synthetic fixtures, plus a one-off check against the spike's four real files (too large to commit).

## 2. Android: Live Photo → motion photo at import (`:adapter:android`, PR "android")

- [x] 2.1 Build the motion photo into a scratch file in `MediaStoreImport` before the single pending insert, as in D3:
  - a HEIC still goes through `ImageDecoder`, then JPEG q95, then the EXIF copy with `Orientation` reset;
  - a JPEG still is used as-is;
  - then `insertXmp`, then the `LIVE` bytes are appended unchanged;
  - the insert uses `.jpg` and `image/jpeg`.

  Verify: ANDROID_EMU clause 2.3.
- [x] 2.2 Make every conversion failure (including `OutOfMemoryError` on decode) fall back to today's still import before the insert. `cleanOrphansOnce` empties the scratch directory, and the scratch file is deleted in a `finally`. An original that does not decode is still refused for good, as today, and that is checked before any conversion. Verify: the ANDROID_EMU test "a Live Photo whose still cannot carry the motion arrives as its still exactly once".
- [x] 2.3 Add the conversion tests to `AndroidImportContractTest`, beside the other Android-only import facts (no shared clause: the conversion is Android's alone):
  - a HEIC+MOV Live Photo becomes ONE `image/jpeg` item named `.jpg`, with both XMP tag sets, the exact MOV bytes at the tail, its capture date, GPS and model intact, upright at full size;
  - a JPEG still stays byte-for-byte ahead of the XMP;
  - the fallback test from 2.2.

  Verify: 7/7 pass on the emulator (API 30), with the new fixture `iphone-live.heic`.
- [x] 2.4 Update the `MediaStoreImport` KDoc (step 2 no longer "discards the MOV") and the receiving section of `docs/architecture.md` / `docs/testing.md` where they state the still-only import. Verify: `grep` finds no "arrives as its still" claim outside the fallback.
- [ ] 2.5 Hardware check, **only after the user's go-ahead**: use the rig build on the Galaxy A40, with the import driven through the app and no event joined. Confirm a received HEIC Live Photo and a "Most Compatible" JPEG one (with a gain map, if one is available) play in Google Photos, allowing for the indexing lag. Remove the test photos afterwards. Verify: the result is recorded in this change's design under Risks.

## 3. iPhone: motion photo → Live Photo at import (`:adapter:ios:app-only`, PR "ios")

- [x] 3.1 In `IosPhotoLibraryImporter`, recognise a lone `PRIMARY` image as a Google motion photo (`LivePhotoFromMotionPhoto`): read the XMP through ImageIO (no full read unless it names a motion photo), find the video range with `locateMotionVideo`, and require an `ftyp` box (after an optional `mpvd` header). Verify: `./gradlew compileIosMainKotlinMetadata` passes, and simulator clause 3.5 passes.
- [x] 3.2 Write the still up to the trailer carrying MakerApple key `17` = a fresh UUID: losslessly when ImageIO keeps it, otherwise re-encoded at quality 1.0 (D4). Both routes read the identifier back from the written file before use. Verify: the simulator log shows the route ("re-encoded at quality 1.0"), and clause 3.5 shows Photos accepted it.
- [x] 3.3 Write the MOV with a passthrough `AVAssetReader`/`AVAssetWriter` (`outputSettings = nil`). It carries the top-level `com.apple.quicktime.content.identifier` and a `still-image-time` timed metadata track at the XMP's timestamp, or 0. Verify: clause 3.5, and the log ("video 2242 B passed through").
- [x] 3.4 Import through one creation request with `.photo` and `.pairedVideo`. If it fails, run a second commit with today's single resource from the untouched original, as in D5. The refused attempt settles as any failed commit (clearing its marker); only the final verdict returns. `recordCreatedLocalId` is an unconditional `UPDATE`, so the second attempt's marker replaces the first. Verify: the existing `GalleryImport` clauses stay green on IOS_SIM_APP.
- [x] 3.5 Add the iOS-only `LivePhotoImportContract` (`test/contracts`), bound by `SimAppLivePhotoImportBinding` and registered for the simulator app. It checks that a motion photo becomes ONE asset with `photo` + `pairedVideo` and the Live subtype, and that a JPEG whose motion XMP points at no video imports as a plain photo. Verify: `scripts/sim-contracts` on the macOS runner (2026-09-30), where every registered contract and the journeys passed.
- [ ] 3.6 Hardware check, **only after the user's go-ahead**, holding the `ios-device` lock: a received Android motion photo plays as a Live Photo on the SE2. Verify: the result is recorded in this change's design under Risks.

## 4. Web zip: Live Photo pair (`site/`, PR "web")

- [x] 4.1 Extract the zip's naming into a pure function. It allocates one stem per asset through the existing collision rule; the still keeps its extension, and the `live` video takes the same stem with its own extension. Include `live` resources in the item list, and remove the stale "GIF" comment. Verify: `site/test/zip-names.test.ts` (one pair, two colliding pairs, a photo colliding with a pair) runs under `npm run check`, which CI's `site` job runs.
- [ ] 4.2 Rebase onto phase 5's /join changes if they have landed. Verify: the site build passes, and in a local run (`local-backend`) the downloaded zip holds `IMG_x.HEIC` and `IMG_x.MOV` for a seeded Live Photo.

## 5. Specs and ship

- [x] 5.1 Validate the change with `npx --yes @fission-ai/openspec@1.13.2 validate live-motion-unification --strict`, and run `./gradlew build` and `./gradlew compileIosMainKotlinMetadata` before each PR. Regenerate `architecture/` if a diagram moved. Verify: all green.
- [ ] 5.2 Ship each PR with the `enhancement` label through `/ship --keep-workspace`. Sync and archive only when the user says so.
