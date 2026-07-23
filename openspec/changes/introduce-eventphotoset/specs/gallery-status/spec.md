## ADDED Requirements

### Requirement: The domain reads neutral asset facts, not platform ABI

The platform library walk SHALL map each `PHAsset` to a **neutral** `AssetFacts` value carrying only
platform-independent facts the policy decides on — `isScreenshot`, `isScreenRecording`, `isVideo`,
`imageArea` (and video area), `isEdited`, `isGif`, and `creationDate`. The interpretation of raw PhotoKit
values (the `mediaSubtypes` bitmask, the `mediaType` integer) into those neutral facts SHALL live in the
iOS adapter (`iosMain`), where the PhotoKit bit constants belong and are pinned; `:domain` (`model/`) SHALL
NOT reference a PhotoKit bitmask. The selection rules (capability `photo-selection-policy`) SHALL read only
neutral `AssetFacts`, so the policy is platform-neutral and a second platform produces the same facts from
its own media model.

The interpretation SHALL be covered by iOS-target tests (`iosSimulatorArm64Test`); the policy logic SHALL
remain covered by `commonTest` over neutral facts (no hand-built bitmask in a policy test).

#### Scenario: model never sees a bitmask

- **WHEN** `:domain` source is inspected for PhotoKit media-subtype/media-type values
- **THEN** none appear — the mapping from raw values to `AssetFacts` lives only in the iOS adapter
