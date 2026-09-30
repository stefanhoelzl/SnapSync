## Why

An iPhone Live Photo and an Android motion photo are the same idea stored in two forms: a still plus a paired
video on iPhone, one image file with the video appended on Android. Today neither survives the crossing. An
Android member receives an iPhone Live Photo as its still, even though its video was already downloaded and
then thrown away (`changes/archive/2026-09-30-android-receiving` D1). An iPhone member receives an Android
motion photo as a plain still, with the video hidden unused in the file. And the event page's zip drops every
Live Photo's video. This is phase 7 of the Android programme. A moving photo should arrive moving, in the
receiving platform's own form.

## What Changes

- **A Live Photo arrives on Android as a motion photo** that plays in Google Photos. The Android device builds
  its gallery copy at import time: a JPEG still, Google's motion-photo metadata, and the Live Photo's video
  appended as delivered. The iPhone default still (HEIC) is re-encoded to a high-quality JPEG, because Google
  Photos does not play HEIC motion photos (measured). The capture date and location are carried over. A still
  that is already a JPEG is used unchanged. **Replaces** "A Live Photo reaches Android as its still".
- **An Android motion photo arrives on iPhone as a Live Photo.** The iPhone recognises Google's motion-photo
  metadata, takes the video out of the file, and saves the still and the video as one Live Photo, without
  re-encoding either.
- **Nothing is ever lost to a conversion.** Any photo that cannot be converted, or that is in a form the
  receiver does not recognise, arrives as its still, exactly as today, and exactly once.
- **The event's original is never changed.** Uploads, the backend and the web download keep the sender's
  bytes. Only the receiving member's gallery copy is converted. "Full fidelity" is reworded to state that
  honestly: the capture moment, resolution, date, location and base filename are kept, and the format may
  change only where a conversion makes the photo move.
- **The event page's zip keeps a Live Photo's video**: the still, and beside it the video under the same name
  with its own extension, which is Apple's own export form and re-imports as a Live Photo. When two photos
  share a name, a pair is renamed together, so its two files still match. "One file per photo" is reworded.
- **Photos received before this change stay as they are.** Received photos are never downloaded again.

## Capabilities

### New Capabilities

(none)

### Modified Capabilities

- `receiving-photos`: "Received photos keep full fidelity" states that a moving photo arrives moving in the
  receiving platform's own form (a scenario for each direction), the fallback to the still, and that the
  gallery copy's format may differ from the original only for such a conversion.
- `event-site`: "The zip holds every photo the members have shared" keeps a Live Photo's video beside its
  still under the same name, and a same-name collision renames the pair together.

## Impact

- **Android** (`:adapter:android`): the MediaStore import builds a motion photo from a Live Photo's two staged
  resources before its one pending insert. It uses the platform's decoder, JPEG encoder and EXIF support, with
  no new dependency.
- **iPhone** (`:adapter:ios:app-only`): the PhotoKit import recognises a motion photo among the staged primary
  resources and imports a still and a paired video in its single creation request. The pairing marks are
  written with ImageIO and AVFoundation, without re-encoding.
- **Shared** (`:domain:model`): the pure byte codecs, namely building and reading the motion-photo metadata and
  locating the appended video. They are platform-free and unit-tested on the JVM. The core's planning, staging
  and exactly-once bookkeeping are unchanged. Conversion is an adapter concern.
- **Web** (`site/src/pages/join.astro`, the invite page): the zip includes the Live Photo's video, and renames
  pairs together. The same file is in phase 5's scope (the /join page), so the two phases need coordinating.
- **Tests:** import-contract clauses for the conversion on the Android emulator (file structure) and on the
  iOS simulator (one asset with a paired video). Playback in Google Photos and the Live Photo on a real iPhone
  are measured on hardware.
- **iOS behaviour changes** for received Android motion photos. Every merge ships a TestFlight build, and
  `./gradlew compileIosMainKotlinMetadata` stays green.
- **Out of scope:** Samsung's own motion-photo format and Samsung Gallery playback (phase 6), remuxing the
  video to MP4, converting photos received earlier, and Play delivery (phase 5).
