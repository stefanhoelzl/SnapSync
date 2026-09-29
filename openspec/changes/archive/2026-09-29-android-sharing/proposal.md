## Why

SnapSync is going to ship on Android as a full member of the same events iOS users are in. Phases 1 and 2
(foundation, identity) gave Android a composition, storage, device attestation and invite links, but an
Android member still cannot share a single photo: there is no photo-library adapter, no permission flow and
no uploader, so the plain Android build refuses to start. This change makes an Android member's **own**
photos reach the event — the first Android phase a user can observe, so the first one with spec deltas.
Receiving (downloads, push) is phase 4; release (Play, crash reporting, listing) is phase 5.

## What Changes

- **On Android, only the DCIM folder is shared.** An Android member contributes only photos and videos in
  the phone's `DCIM` folder and its subfolders (on internal storage and on an SD card) — where camera apps,
  Sony's and standard third-party cameras included, save. Each `DCIM` subfolder counts as an album for the
  existing album rule, whose one list gains the phone's screenshot and screen-recording folders
  (`Screenshots`, `Screen recordings`, `ScreenRecorder`; `Facebook` is already on it); the capture range and
  the resolution floors apply inside. Photos outside `DCIM` — messenger downloads, web saves, in-app
  cameras — are never shared, and there is no way to add a folder: a knowing exception to "when in doubt, a
  photo is shared", chosen for cleaner events. Moving a photo out of `DCIM` withdraws it; moving it back
  shares it again. iOS keeps its rules; the new titles only affect an iPhone album a user names that way.
- **Photo access on Android.** Full access, no access, and — on Android 14 and later — limited access to a
  hand-picked selection, which the camera-folder rule then filters. Below Android 14 access is full or
  none. Access is asked on Join, and the access line, "Choose more photos" and "Allow full access" work as
  on iOS, through Android's own dialog, selection sheet and settings page. The originals are read with
  their embedded location.
- **Background uploading on Android.** Photos upload without the app being opened: the app is woken when
  the library changes, keeps a timed wake while work remains, and uploads while open. A force-stop pauses
  background uploading until the next opening; before the first unlock after a restart nothing runs.
  There is no second, OS-run uploader on Android.
- **A genuine Android phone can change an event.** The "genuine app on a genuine Apple device" promise
  becomes platform-neutral: a genuine, unmodified SnapSync app on a genuine iPhone, or on an Android phone
  whose hardware vouches for the app and which runs its manufacturer's verified system.
- **Platform-neutral wording** where this phase makes an Android surface real: the access dialog, the
  app's settings page and the share sheet in `photo-access`, `join-event`, `sync-status` and
  `manage-membership` speak of "the system's" dialog, settings page and share sheet.
- **Mission text.** `openspec/config.yaml`'s context and `CLAUDE.md` stop listing Android as a named
  future; the "one policy at one place" rule is restated as *the platform defines the member's default
  gallery; the selection policy is the one decision over it.*
- **Code (no spec of its own):** the Android gallery over MediaStore, the permission adapter and change
  observer, the WorkManager-backed `Wake` / `BackgroundTime` / `Upload` adapters, the system-UI adapter,
  an extension registry that answers "unsupported", and a library-change wake armed for as long as the
  membership contributes.

## Capabilities

### New Capabilities

(none)

### Modified Capabilities

- `photo-sharing`: an Android member shares only from `DCIM`, its subfolders being albums for the album
  rule, whose list gains the screenshot/screen-recording folders; the album rule applies under limited access
  on Android; "when in doubt" becomes the iPhone rule; moving out of / back into `DCIM` withdraws /
  re-shares; the trash counts as deletion.
- `photo-access`: Android's three states (limited only on 14+); the dialog, picker and settings page are
  the system's rather than iOS's.
- `background-upload`: Android's uploading without the app opened, its force-stop behaviour, what "the
  original" means on Android (a motion photo as one file; a gallery app's saved edit is a photo of its
  own), a revoke that stops the app mid-upload, and platform-neutral wording.
- `privacy-security`: "Only a genuine SnapSync app can change an event" names Android's genuine device.
- `join-event`: "Photo access is explained before iOS ever asks" becomes the system's dialog.
- `sync-status`: the missing-access line raises the system's dialog or opens the app's settings page.
- `manage-membership`: the invite's share action uses the system share sheet.

## Impact

- **Modules:** `:adapter:android` (gallery, permission, change observer, WorkManager wake/background-time/
  upload, system UI, extension registry); `:app:android` (manifest permissions, the worker entry, the
  root's wiring); `:domain:feature` / `:domain:services` (the library-change wake's own arming rule — the
  only `:domain` behaviour change, inert on iOS, which answers that wake `Unsupported`); `:test:rig`
  (Android's adapter choice may make the library, wake, background time and upload session real);
  `:test:contracts` (the gallery, upload and wake contracts bound on `ANDROID_EMU`).
- **iOS:** no behaviour change. `./gradlew compileIosMainKotlinMetadata` and the iOS contract replays stay
  green; every merge still ships a TestFlight build.
- **The plain Android build still refuses to start** after this phase: downloads, push and crash reporting
  are phases 4 and 5.
- **Dependencies:** `androidx.work:work-runtime` (WorkManager) in `gradle/libs.versions.toml`.
- **Play policy:** holding `READ_MEDIA_IMAGES` / `READ_MEDIA_VIDEO` needs the Photo & Video Permissions
  declaration (phase 5, Play Console).
- **Phase 4 input (decided here, built there):** downloaded photos are imported into `DCIM/Camera`,
  iOS-style, with the download store as the only guard against sharing them back — replacing the handoff's
  `Pictures/SnapSync/<event>` folder.
