# Tasks

## 1. The read says where its photos came from (design D1)

- [x] 1.1 In `domain/model`: add `CandidateScope { Library, Selection }`, an abstract `judgesWhatItIs` on `SelectionRule`
  (true for the screenshot, screen-recording, both floor and album rules; false for the capture bounds, echo and
  `DenyAll`) and `SelectionPolicy.over(scope)`. Update the `SelectionPolicy` KDoc, which currently says the selection is
  filtered "exactly as it would a library". Verify with `SelectionPolicyTest` cases:
  - under `Selection`, a screenshot, screen recording, sub-floor image, sub-floor video and denylisted-album member are
    admitted
  - out-of-range, undated and echoed assets are still refused
  - under `Library` nothing changes
- [x] 1.2 Carry the scope on `CandidateRead.Readable` and `Discovery` (required, no default). `EventPhotoSet` judges with
  `policy.over(scope)`:
  - `EventPhotoSet.readable` uses the read's scope
  - `UploadCycle.decide` uses `discovery.scope`

  Label the reads:
  - `PermissionAwareCandidateSource`'s snapshot branch and `SelectionScopedDiscovery`'s `Scoped` branch: `Selection`
  - `GalleryDiscovery` and `GalleryCandidateSource`: `Library`

  Verify:
  - `SelectionScopedDiscoveryTest` and a `PermissionAwareCandidateSource` test assert the labels
  - a feature/services test over the mocks: under `LIMITED` the status total, join preview and cycle admit a selected
    screenshot and small image, and under `GRANTED` they do not
  - `./gradlew build` and `./gradlew compileIosMainKotlinMetadata` pass
- [x] 1.3 Update the desktop harness (`WorldInspectorController.snapshotNow`) so its "policy excluded" badge judges with
  the scope its read reports. Verify that it compiles and that the harness shows a selected screenshot as not excluded
  under a partial grant.

## 2. Walk + policy decides what stays listed (design D2)

- [x] 2.1 In `UploadCycle.decide` / `departedKeys`: on an authoritative walk, delete the in-window rows whose asset walk +
  policy did not **admit** (previously: whose asset the walk did not return). Rewrite the KDoc: the in-window guard is
  what separates this from the retired retain-live reconcile. Verify with cycle tests:
  - a present screenshot row is deleted under a `Library` walk, and kept under a `Selection` walk
  - a range-narrowed row is kept and returns without a new upload
  - an album row is kept
  - an absent photo's row is deleted as before
- [x] 2.2 Keep `SelectionPolicyConstructionTest`, `EventPhotoSetSourceTest` and every `:test:architecture` guard
  green. Verify that `./gradlew :test:architecture:test` passes.

## 3. Android: a partial grant's gallery is the selection (design D3)

- [x] 3.1 In `adapter/android` `AndroidGalleryReader` / `DefaultGallery`:
  - apply the DCIM path scope only to a full-grant read
  - a selection-scope read queries with no path filter, re-checks the grant after the query, and answers
    `GalleryRead.NotReadable` if the grant is no longer `LIMITED`
  - update the class KDoc on the default gallery

  Verify with the photo-library contracts on `ANDROID_EMU`:
  - a `:test:partial-grant` clause in which a selected photo outside DCIM is returned
  - a `:adapter:android` full-grant clause in which an item outside DCIM is still not returned

  Run them with `./gradlew androidPlatformTest`. `ClauseCoversTest` and `AdapterCoverageTest` must stay green.
- [x] 3.2 Confirm that the app's own imports, which are visible under a partial grant wherever they are filed, stay out
  of the share through `NotEcho`. Verify with a mocks-level test of a limited selection holding an imported photo
  outside DCIM.

## 4. The join screen tells a limited member what is shared (design D5)

- [x] 4.1 Add `share_selection_note` ("The photos you selected from this range are shared." and its German
  counterpart) to `ui/screens` `composeResources` (`values` and `values-de`). Have `ParticipationSections` show it
  instead of `share_exclusions_note` when the join state says access is limited. The fact is mapped in
  `domain/presentation`. Verify:
  - a `JoinScreenTest` case for each note
  - the `HardCodedUiText` rule stays green
  - `./gradlew nativeStrings` produces no diff
- [x] 4.2 Check that the manage-membership (reconfigure) screen, if it shows the same note, follows the same mapping.
  Verify with its screen test.

## 5. Observable behaviour through the rig (spec scenarios)

- [x] 5.1 Add `:test:integration` behaviours (`behaviour { given … whenever … then … }`, `@Verifies`) for the new
  scenarios of `photo-sharing`'s "Under limited access the member's selection decides what a photo is":
  - a selected screenshot is shared
  - a selected screen recording is shared
  - a selected WhatsApp photo is shared (iPhone album / Android folder)
  - a selected small image is shared
  - a selected photo outside the range stays private
  - photos selected for an earlier event stay private
  - a selected undated photo is not shared
  - a received photo in the selection is not shared back

  Verify with `./gradlew :test:integration:test`.
- [x] 5.2 Behaviours for the grant switch: `photo-access` "A selected screenshot leaves on widening to full access"
  (withdrawn, nothing uploaded again), and `photo-sharing` "A screenshot withdrawn by full access returns with limited
  access" (uploaded again and offered). "Under limited access a selected photo outside DCIM is shared" is covered on
  Android by the partial-grant contract (3.1): the JVM host's mock library has no folders. Verify by asserting the backend mock's
  upload counts and the other member's receipts.
- [ ] 5.3 Hand phase 8 (workspace `android-shaped-answers`) the scenarios it may now claim. Verify that the message has
  been sent and that the old scenario names (`A selected screenshot is still excluded`,
  `A selected photo from a WhatsApp folder is excluded on Android`, `A selected photo outside DCIM is not shared`)
  appear nowhere in `@Verifies`.

## 6. Docs and context

- [x] 6.1 CLAUDE.md:
  - the limited-access paragraph: the policy no longer "filters the selection exactly as it would a library"; under a
    partial grant only the range and echo suppression apply
  - fact ③'s denylist sentence
  - the default-gallery sentence: under a partial grant, the selection on both platforms

  Verify by reading it against design D1/D3.
- [x] 6.2 Update the default-gallery sentence in `openspec/config.yaml`'s `context:` and the matching passages of
  `docs/architecture.md`. Verify with
  `grep -n -i "default gallery\|DCIM" openspec/config.yaml docs/architecture.md` that no line still says DCIM bounds a
  partial grant.
- [x] 6.3 Run `./gradlew architectureDiagrams` and commit any change. Verify that the diagrams freshness check
  passes.

## 7. Integration check and sync

- [x] 7.1 Run `./gradlew build` and `npx --yes @fission-ai/openspec@1.13.2 validate --specs --strict`. Both must be
  green.
- [x] 7.2 At sync (when the user asks for it), edit the `## Purpose` of `photo-sharing` and of `photo-access` directly
  in `openspec/specs/`, because a delta cannot carry a Purpose:
  - photo-sharing: the exclusions and DCIM apply under full access; under limited access the selection decides,
    within the range
  - photo-access: "the photos the member hand-picked from their range"

  Verify that both archive gates in `openspec/config.yaml` print nothing for the touched specs.
