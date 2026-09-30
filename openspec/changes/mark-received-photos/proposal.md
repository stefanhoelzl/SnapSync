## Why

The download record is the only thing that knows which photos in a member's library came from SnapSync, and it
dies with the app. So a member who deletes and reinstalls the app, then rejoins, receives the event's photos a
second time (duplicates in their library). If those photos fall inside their capture range, they are also
shared back as their own, so the other members get duplicates too. Both specs accept this today as a gap.
The photo library outlives the app, so a mark on each received photo lets a reinstalled app recognise what it
had already received.

## What Changes

- **Received photos carry a SnapSync mark in their filename.** A received photo keeps the sender's filename,
  with a short SnapSync mark added before the extension: the sender's `IMG_4471.HEIC` arrives as
  `IMG_4471.snapsync-<token>.HEIC`. The token identifies which shared photo it is, without exposing any id.
  The mark is visible wherever the phone shows a photo's filename.
- **A rejoin recognises photos already received.** At every join, whether the member receives or only shares,
  and before anything is downloaded or uploaded, the app looks in the member's library for marked photos that
  belong to the event. It treats each one as received: it is neither downloaded again nor shared back as the
  member's own. This closes the reinstall gap for photos still in the library.
- **The reinstall gap narrows but does not disappear.** A reinstall still may receive again, or share back:
  - a photo deleted from the library before the reinstall;
  - a photo received before this change (it carries no mark);
  - a photo the app cannot see because access is limited to a selection;
  - a photo shared into the event after the member joined;
  - any photo, when the event could not be reached at the join.
- **Not changed:** the download record stays; the mark is a second record, not a replacement. Deciding what a
  member shares still never looks at filenames, so a marked photo that reaches someone another way (AirDrop,
  a messenger) is treated like any other photo. Photos already in the library are not renamed.

## Capabilities

### New Capabilities

(none)

### Modified Capabilities

- `receiving-photos`: "Received photos keep full fidelity" changes the filename promise (the sender's filename
  plus a SnapSync mark). "A deleted received photo never comes back" and "Received photos are never shared
  back" narrow their reinstall gap: a rejoin recognises the marked photos still in the library.

## Impact

- **Shared core:** a pure naming rule in `model/`, covering the token over the source ref, the marked
  filename, and parsing a filename back to its token. The token is used by both import adapters in place of
  `importFilename`'s bare sender name. There is a new adoption step at every join (any direction), before the upload arm is enabled. It reads candidate photos in the
  event window and their names, and settles matches in the download store as imported, carrying the local
  asset as the created local id. That is the existing suppression handle, so echo suppression follows with no
  new rule.
- **Download store:** a write that records an already-present local asset as imported for a source ref (an
  insert, not a new column). No schema migration.
- **iOS:** `IosPhotoLibraryImporter` names each resource with the marked filename (the Live Photo's video uses
  the same token).
- **Android:** `MediaStoreImport` sets the marked `DISPLAY_NAME`. The reader already reports `DISPLAY_NAME` as the
  resource's original filename.
- **Specs:** `receiving-photos` only. `photo-sharing` states no reinstall gap for received photos, so it is
  unchanged.
- **No backend, manifest or API change.** The token is derived on the receiving device from the union's source
  ref.
