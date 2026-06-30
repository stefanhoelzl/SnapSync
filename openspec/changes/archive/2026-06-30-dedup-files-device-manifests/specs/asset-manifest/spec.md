## REMOVED Requirements

### Requirement: Per-asset manifest document

**Reason**: Superseded by the per-event mutable `device-manifest` (`/events/<eventId>/device/<deviceId>.json`).
There is no longer one immutable manifest object per asset at `<eventId>/<assetId>.manifest.json`; the
per-device manifest projects all of a device's assets into a single object per event, so the per-asset
manifest document no longer exists.

**Migration**: Clean cutover, no backfill. Old `<eventId>/<assetId>.manifest.json` objects are
orphaned and ignored. The `{assetId, creationDate, resources[]}` shape — including the
`{role, contentType, filename, originalFilename}` resource fields — carries forward into the
`device-manifest` `assets[]` entries.

### Requirement: Generic resource roles

**Reason**: Superseded by `device-manifest`. The role vocabulary is no longer a property of a
standalone per-asset manifest object; it is a field on each resource inside the per-event device
manifest.

**Migration**: The generic, platform-neutral role vocabulary `primary` (the single original primary
medium) and `motion` (the original paired Live Photo video) carries into `device-manifest` unchanged —
exactly one `primary` and at most one `motion` per asset, the primary's image-vs-video nature carried
by `contentType`, not by the role.

### Requirement: Original-only, immutable resource set

**Reason**: Superseded by `device-manifest`. The per-asset immutable manifest object is gone, and with
it the write-once-per-asset property; the per-device manifest is a **mutable** full-state projection
rewritten each cycle.

**Migration**: The originals-only resource set carries into `device-manifest` — each asset's entry
still lists only its original resources (no edited/full-size renders, adjustment data,
adjustment-base media, or proxies). Immutability is not carried forward (the device manifest is
mutable by design); ordinary HTTP caching replaces the permanent-cache property.

### Requirement: Manifest is the authoritative resource set

**Reason**: Superseded by the own-device completeness model. Read-time completeness from a single
per-asset manifest no longer exists; completeness is computed by the app from the shared
`gallery-status` enumeration seam (expected resource set) intersected with the per-device file listing
`GET /files/device/<deviceId>` (present resources), not by reading a manifest against storage.

**Migration**: No per-asset manifest is read to determine an asset's expected resource set; the
expected set is derived from the same enumeration seam the producer uploads from, so app and extension
agree on what "complete" means.

### Requirement: Manifest uploaded out of band, not a ledger resource

**Reason**: Superseded by `device-manifest`. The out-of-band per-asset manifest side-channel is gone:
the per-event `device.json` is a single object the extension PUTs **synchronously in-cycle** (sole
writer), not a per-asset object uploaded with no ordering guarantee. It is still not an engine
`Resource` and still not a ledger row, but that contract now lives in `device-manifest`.

**Migration**: None for storage (old per-asset manifest objects are orphaned). The
not-a-ledger-resource and harmless-re-upload (last-write-wins) properties carry into `device-manifest`.
