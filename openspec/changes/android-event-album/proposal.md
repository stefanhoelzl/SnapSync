## Why

An Android member of an event has no event album: `changes/archive/2026-09-30-android-receiving` (D2) ruled it
out because an Android album is a folder and a file lives in one folder, so filing received photos would move
them out of the camera roll or copy them. That reasoning holds for the member's **own** photos, but not for
received ones: the app creates those files itself and can save them straight into an event folder under `DCIM`
instead of the camera folder. Each gallery app then shows that folder as an album named after the event. So
Android can have a real event album without moving or copying anything, as long as it holds received photos
only.

## What Changes

- **Android gets the event album, holding received photos.** While the album is on, other members' photos are
  saved into a folder named after the event inside a SnapSync folder in DCIM, instead of the camera folder. Every
  gallery app shows that folder as an album. The member's own photos stay where their camera saved them and are
  never moved or copied, so on Android the album holds the photos they **receive**, not the ones they share.
- **The album choice is offered on Android**, on the join screen and in settings, on by default as on iPhone.
  Its note says the album collects the photos they receive and that their own photos stay in the camera folder;
  with receiving off it says the album will collect nothing.
- **Turning the album on later gathers**: this event's photos already received into the camera folder are moved
  into the album's folder. Moving files the app saved itself needs no prompt. A photo the app can no longer move,
  after a reinstall, stays in the camera folder.
- **Turning it off** saves new photos into the camera folder again. The folder and its photos stay.
- **A deleted album stays deleted.** Deleting the folder in a gallery app deletes its photos too; after that,
  new photos arrive in the camera folder until the member turns the album on again.
- **Folder identity**: two events with the same name get separate folders (the second numbered). Renaming the
  event does not rename the folder. A folder the member renamed is not followed: like a deleted one, it sends
  later photos to the camera folder until the member turns the album on again.
- **The event albums' folder is never shared.** Nothing inside SnapSync's folder in DCIM is shared as the
  member's own, even though DCIM is what Android shares from. So received photos saved there stay out of a later
  event even after a reinstall has erased the device's record of what it received.
- **Superseded:** D2 of `android-receiving` ("the app offers no event album on Android") and the requirement
  "An Android member has no event album".

## Capabilities

### New Capabilities

(none)

### Modified Capabilities

- `event-album`: offered and created on Android too; on Android it holds received photos only; gathering moves
  already-received photos into it; a deleted folder takes its photos with it, and a deleted or renamed folder
  sends later photos to the camera folder; it appears in a gallery with its first photo. The requirement "An
  Android member has no event album" is removed.
- `receiving-photos`: on Android a received photo lands in the event album's folder while the album is on,
  otherwise in the camera folder. A received photo in the album's folder is never shared back, even after a
  reinstall.
- `photo-sharing`: on Android, the SnapSync folder inside DCIM is excluded from what is shared.
- `join-event`: the join screen offers the album choice on Android too.
- `manage-membership`: settings offer the album on Android too, and turning it on there says the photos already
  received are collected.

## Impact

- **Modules:**
  - `:domain:ports`: the gallery states *how* it holds an album instead of *whether* it can: an album the photo
    is added to (iPhone) or the folder the photo lives in (Android).
  - `:domain:model`: that album kind, and the join/settings form carrying it in place of `albumOffered`.
  - `:domain:feature`: `AlbumCoordinator` and `AlbumGather` never place own photos where the album is a folder,
    and they apply the folder album's "deleted" rule.
  - `:domain:services`: the album map remembers whether a folder album has held a photo.
  - `:domain:presentation`, `:ui:screens`: the album choice on Android and its received-only notes.
  - `:domain:host`: hands presentation the album kind.
  - `:adapter:android`: folder albums: create, resolve, move, import into the album's folder, and the SnapSync
    folder's exclusion from own-photo reads.
  - `:adapter:generic:mock`: the photo-library mock models folder albums.
  - `:test:contracts`: GalleryReader and GalleryImport clauses for folder albums, bound on `ANDROID_EMU`.
- **iOS:** no behaviour change. `./gradlew compileIosMainKotlinMetadata` and the iOS contract replays stay green.
- **Out of scope:** placing or copying the member's own photos into the Android album; following a folder the
  member renamed; moving photos back to the camera folder when the album is turned off.
