## MODIFIED Requirements

### Requirement: Generic resource roles

Resources SHALL be typed by a generic, platform-neutral `role`, never a platform resource-type name.
This change defines two roles: `primary` — the single original primary medium of the asset (a still
image or a video) — and `live` — the original paired video of a Live Photo. An asset SHALL have
exactly one `primary` resource and at most one `live` resource. Whether the primary is an image or a
video SHALL be carried by `contentType`, not by the role. The manifest SHALL list only the asset's
**original** resources and SHALL NOT list edit artifacts. (The role formerly named `motion` is renamed
to `live`; this is a clean cutover — the producer rewrites each device.json as a full-state snapshot
next cycle, so older `motion` manifests from un-updated builds age out and are not migrated.)

#### Scenario: A plain photo has one primary

- **WHEN** an asset is a single still image
- **THEN** its entry lists exactly one resource with role `primary` and no `live`

#### Scenario: A Live Photo has primary plus live

- **WHEN** an asset is a Live Photo (original still plus original paired video)
- **THEN** its entry lists a `primary` (the still) and a `live` (the paired video), and
  image-versus-video is distinguished by `contentType`
