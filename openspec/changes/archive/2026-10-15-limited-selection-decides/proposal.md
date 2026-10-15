# Proposal — under limited access, the selection decides what a photo is

## Why

Under limited photo access a member hand-picks the photos SnapSync may see. Today the app still filters that
pick as if it were guessing over a whole library: screenshots, screen recordings, small images and
messaging-app albums are dropped from what the member deliberately chose. The rule also does not hold
together across platforms:
- On iPhone the album rule is already inert under limited access, because albums cannot be read.
- On Android, screenshots and screen recordings are recognised only by their folder, so the same spec
  sentence is enforced on one platform and not the other.
- Today's code skips the folder rule under limited access on Android, so a selected WhatsApp-folder photo
  and a selected screen recording are shared. That contradicts `photo-sharing`'s scenarios.

This reverses a recorded non-goal of `changes/archive/2026-07-20-accept-limited-photo-access`: *"No change to what the
selection policy admits (cutoff, origin exclusions, floors all apply to picked photos unchanged — settled in
interview 1)"*. That decision was taken on iPhone alone. Android, which arrived later, made it inconsistent, and the
selection has since proved to be the member's real choice of what to share.

## What Changes

- Under limited access, on iPhone and Android, a selected photo SHALL NOT be excluded for **what it is**. These
  rules no longer apply to it:
  - screenshots and screen recordings
  - the image and video resolution floors, and with them ordinary GIFs
  - the messaging-app album rule
- On Android under limited access, the DCIM-only rule drops too. The member's gallery is their selection, as on
  iPhone, so a selected photo saved from the web or a chat outside DCIM is shared.
- **Unchanged: the capture range still decides *when*.** This covers its start and end, nothing being shared
  before the event's start, and an undated photo never being shared. The system's selection belongs to the app
  and persists across events, so it is not an event-scoped choice. Dropping the range would send photos picked
  for one event into every later one.
- **Unchanged: received photos are never shared back**, even when they sit in the selection (capability
  `receiving-photos`).
- Switching between full and limited access now changes which kinds of photo are shared, not only which photos.
  Nothing is uploaded again either way.
- The join screen's "never shared" note stops claiming exclusions that do not apply under limited access.
  For a member already on limited access, it says instead that their selected photos from the range are shared.
- Under full access nothing changes.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `photo-sharing`:
  - The limited-access album requirement is replaced by "the selection decides what a photo is".
  - The origin exclusions, admit-on-doubt and the DCIM rule are scoped to full access.
  - Withdrawing and restoring through DCIM moves or messaging-app albums happen only under full access.
- `photo-access`:
  - A limited membership is filtered by the member's range alone.
  - Android 14's selection is no longer limited to DCIM.
  - A grant switch can change which kinds of photo are shared.
- `join-event`: the "never shared" statement applies to full access; under limited access the screen states the
  selection rule.
- `delivery`: on Android an original is uploaded as the file the phone stores, not "the file DCIM holds".

## Impact

- **Admission** (`domain/model`: `SelectionPolicy`, `EventPhotoSet`, `CandidateRead`; `domain/services` `Discovery`): a
  read states whether its photos came from the library or the selection, and the selection's are judged without the
  "what it is" rules. This is labelled where the reads are made (`PermissionAwareCandidateSource`,
  `SelectionScopedDiscovery`, `GalleryDiscovery`, `GalleryCandidateSource`), and the desktop harness follows.
- **The upload cycle** (`UploadCycle`): on a full walk it forgets the rows walk + policy no longer admit but the
  manifest would still list. A screenshot shared under limited access is withdrawn on switching to full access, as a
  deleted photo is.
- **The Android gallery** (`adapter/android` `DefaultGallery`, `AndroidGalleryReader`): the query scope depends on
  the grant. It reads the whole selection under a partial grant and DCIM under a full one. The `:test:partial-grant`
  contract bindings follow.
- **UI** (`ui/screens` `ParticipationSections`, `share_exclusions_note` in English and German): a limited-access
  variant of the note, carried by the join state from presentation.
- **Tests**: the `SelectionPolicy` suites, the services and feature suites over the mocks, and `:test:integration`
  behaviours for the new scenarios. Phase 8 of the integration-coverage programme (workspace
  `android-shaped-answers`) claims the limited-access scenarios once this lands.
- **Docs**: CLAUDE.md's limited-access paragraph and fact ③, the default-gallery sentence in `openspec/config.yaml`'s
  context, and `docs/architecture.md` wherever it describes the default gallery or the denylist under limited access.
- **No backend, wire or storage change.** A screenshot that returns after a switch back to limited access is
  uploaded again, like a recovered photo; storing the photo's facts on its row to avoid that was considered and
  declined.
