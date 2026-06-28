## ADDED Requirements

### Requirement: Per-asset manifest document

For every asset it backs up, the producer SHALL upload exactly one manifest object at the key
`<eventId>/<assetId>.manifest.json` with `Content-Type: application/json`. The manifest SHALL be a
UTF-8 JSON object carrying `version` (an integer schema version, `1` in this change), `assetId` (the
opaque asset identity, equal to the `assetId` of its resources), `creationDate` (the asset's capture
timestamp as an ISO-8601 string), and `resources` (a non-empty array). Each `resources` element SHALL
carry `role`, `contentType` (the resource's MIME type), `filename` (the resource's object name within
the event — its storage key minus the `<eventId>/` prefix), and `originalFilename` (the resource's
human filename as captured). The manifest SHALL carry no other top-level fields in v1 — no location,
no favorite/hidden flags, no media subtypes, no pixel dimensions.

#### Scenario: One manifest object per asset

- **WHEN** the producer backs up an asset with id `A`
- **THEN** exactly one object `<eventId>/A.manifest.json` is uploaded with `Content-Type:
  application/json`, carrying `version`, `assetId = A`, `creationDate`, and a non-empty `resources`
  array

#### Scenario: Each resource entry carries the four fields

- **WHEN** a manifest lists a resource
- **THEN** that entry carries exactly `role`, `contentType`, `filename`, and `originalFilename`

### Requirement: Generic resource roles

Resources SHALL be typed by a generic, platform-neutral `role`, never a platform resource-type name.
This change defines two roles: `primary` — the single original primary medium of the asset (a still
image, a video, or an audio track) — and `motion` — the original paired video of a Live Photo. An
asset SHALL have exactly one `primary` resource and at most one `motion` resource. Whether the primary
is an image or a video SHALL be carried by `contentType`, not by the role.

#### Scenario: A plain photo has one primary

- **WHEN** an asset is a single still image
- **THEN** its manifest lists exactly one resource with role `primary` and no `motion`

#### Scenario: A Live Photo has primary plus motion

- **WHEN** an asset is a Live Photo (original still plus original paired video)
- **THEN** its manifest lists a `primary` (the still) and a `motion` (the paired video)

### Requirement: Original-only, immutable resource set

The manifest SHALL list only the asset's **original** resources and SHALL NOT list edit artifacts —
edited or full-size renders, adjustment data, adjustment-base media, or proxies. Because only
originals are listed and originals never change, an asset's resource set SHALL be fixed at capture: a
manifest, once uploaded for an asset, SHALL NOT be revised for that asset, and editing the asset later
SHALL NOT add resources or alter its manifest.

#### Scenario: An edited asset lists only its original

- **WHEN** an asset has been edited (it has an original plus a rendered edit and adjustment data)
- **THEN** its manifest lists only the original resource(s); the render and adjustment data are absent

#### Scenario: The resource set never grows

- **WHEN** an asset is edited after its manifest was uploaded
- **THEN** no new resource is added and the manifest is not revised

### Requirement: Manifest is the authoritative resource set

A consumer SHALL be able to determine an asset's complete expected resource set from its manifest
alone, without enumerating the storage namespace or knowing the producing platform. An asset SHALL be
considered **complete** only when every resource named in its manifest is present in storage.

#### Scenario: Consumer learns the full set from the manifest

- **WHEN** a consumer reads an asset's manifest
- **THEN** it knows every resource the asset is expected to have, by `filename` and `role`

#### Scenario: Completeness is all-named-resources-present

- **WHEN** every resource a manifest names is present in storage
- **THEN** the asset is complete; **WHEN** any named resource is absent, the asset is not complete

### Requirement: Manifest uploaded out of band, not a ledger resource

The manifest SHALL be uploaded independently of its resources, with **no ordering guarantee** relative
to them (the platform's background-upload scheduler owns resource ordering, so "upload last" is not
achievable). The manifest SHALL NOT be modeled as an engine `Resource` and SHALL NOT be recorded in
the upload ledger; its delivery SHALL be tracked outside the ledger and its completion SHALL be
observed (via a storage listing or the upload task's own completion) rather than recorded by a ledger
writer. Re-uploading an identical manifest SHALL be harmless (last-write-wins).

#### Scenario: The manifest never enters the ledger

- **WHEN** the manifest for an asset is uploaded
- **THEN** no ledger row is recorded for it and the engine's upload-job creation is never invoked for it

#### Scenario: Order relative to resources does not matter

- **WHEN** a manifest is uploaded before, between, or after its asset's resources
- **THEN** correctness is unaffected — completeness is determined by reading the manifest against the
  stored objects, not by upload order

#### Scenario: Duplicate manifest upload is harmless

- **WHEN** the same manifest object is uploaded more than once
- **THEN** the result is the last write, with no error or corruption
