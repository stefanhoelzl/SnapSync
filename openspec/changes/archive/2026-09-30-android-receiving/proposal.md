## Why

SnapSync is going to ship on Android as a full member of the same events iOS users are in. Phase 3
(`changes/archive/2026-09-29-android-sharing`) made an Android member's own photos reach the event, but
nothing reaches an Android phone yet: there is no download adapter, the gallery's import is a stub, there
is no push service, and the plain Android build still refuses to start. This change is phase 4, receiving:
other members' photos arrive in an Android member's camera roll, silent wakes reach Android, and the plain
build starts. Release (Play, crash reporting, listing) stays phase 5.

## What Changes

- **Received photos land in the Android camera roll.** On Android, other members' photos and videos are
  saved into the phone's camera folder, where they sit in the gallery's timeline beside the member's own
  camera photos, with their capture date, their format and (collisions aside) their filename. A received
  Live Photo arrives on Android as its still photo; a motion-photo conversion is phase 7.
- **No event album on Android.** A file on Android lives in exactly one folder and a folder is a gallery
  app's album, so filing received photos into an event album would move them out of the camera roll or copy
  them. The event album is therefore an iPhone feature: an Android member is never offered the album choice,
  on the join screen or in settings, and no album is created.
- **Silent wakes on Android.** Firebase Cloud Messaging carries the same silent wake APNs carries on iPhone,
  both for a new photo and for an event's close. Phones without Google Play services get no wake; their
  photos arrive when the app is opened, exactly as with a dropped wake.
- **The reinstall gap is stated.** The download record is the only thing that keeps a received photo from
  being shared back as the member's own. Deleting the app deletes that record on both platforms, so a member
  who reinstalls and rejoins the same event may share photos they had received, and other members may get a
  duplicate. The spec says so, as an accepted gap.
- **Platform-neutral wording** where receiving now happens on Android too: "the Photos library" becomes
  the phone's photo library, and a save "iOS cut short" becomes a save the system cut short.
- **Backend:** an FCM HTTP v1 sender beside the APNs sender. Every silent wake picks the sender by the
  device's registered push kind. Nothing is migrated: the device's push kind is already stored.
- **Code (no spec of its own):** the Android download adapter over DownloadManager, the MediaStore import
  into the camera folder, the FCM push adapter, an Android process-info adapter, an inert crash reporter,
  and the plain `:app:android` build composing its real adapters instead of refusing.
- **Shared core (iOS behaviour unchanged):** the push registration carries a kind the push adapter states,
  instead of a hardcoded APNs; the gallery states whether it can create and fill albums, and presentation
  hides the album choice where it cannot.

## Capabilities

### New Capabilities

(none)

### Modified Capabilities

- `receiving-photos`: received photos land in the Android camera roll; a Live Photo arrives on Android as
  its still; the platform-neutral wording of the library and of a save the system cut short; the
  reinstall-and-rejoin gap in "Received photos are never shared back".
- `event-album`: the album is offered on iPhone only; an Android member has no event album.
- `join-event`: the join screen offers the album choice on iPhone only.
- `manage-membership`: settings offer the album, and explain turning it on, on iPhone only.

## Impact

- **Modules:**
  - `:adapter:android`: the download adapter and its completion receiver, the gallery import, the FCM
    push adapter, and a process-info adapter.
  - `:app:android`: manifest permissions and receivers, the Firebase options from the resolved deployment,
    and the plain build's adapter set.
  - `:domain:ports`: the push kind on the push port, and the album-writes flag on the gallery reader.
  - `:domain:model`: the push-registration type replacing the APNs-only token type.
  - `:domain:feature` and `:domain:presentation`: the registration key carries the kind, and the album
    choice is hidden when the gallery cannot hold an album.
  - `:domain:compose`: the inert crash reporter.
  - `:adapter:generic:app`: the push body carries the stated kind.
  - `:adapter:generic:mock`: the push kind and album-writes switches.
  - `:test:contracts`: the download and import contracts bound on the Android emulator.
- **api:** `api/src/fcm.ts`, a sender that picks FCM or APNs by kind in front of both, and two new
  deployment fields. **Config before code:** the FCM service-account secret must be set on the Edge Script
  before this merges.
- **Deployment:** the Firebase project's public values in `deployments/components/android.json`.
- **iOS:** no behaviour change. Each iOS device re-publishes its push registration once after updating,
  because the registration key now includes the kind. `./gradlew compileIosMainKotlinMetadata` and the iOS
  contract replays stay green; the merge ships one TestFlight build.
- **Dependencies:** `com.google.firebase:firebase-messaging` in `gradle/libs.versions.toml`. There is no
  google-services Gradle plugin.
- **Out of scope:** phase 8's periodic check, which also amends receiving-photos' wake requirement (this
  change leaves that requirement untouched); phase 5's Sentry, signing and Play delivery; phase 7's
  motion-photo conversion.
