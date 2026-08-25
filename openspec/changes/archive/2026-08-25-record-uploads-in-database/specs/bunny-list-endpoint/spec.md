## REMOVED Requirements

The capability is dissolved into `api-endpoints`. Both read routes survive with their paths and (bar one
unread field) their shapes; what does not survive is the way they were assembled — a marker read, a
directory listing per member device, a manifest read per member, and a second listing used as a
completeness oracle. All of it becomes one query.

### Requirement: Per-event file listing route
**Reason**: Route mechanics. The route is per **device**, not per event, despite the requirement's name.
**Migration**: `api-endpoints` → *Per-device file listing*.

### Requirement: Asset assembly from a single directory listing
**Reason**: The listing is served from `resources` rows, not from a storage enumeration.
**Migration**: `api-endpoints` → *Per-device file listing*.

### Requirement: Presigned S3 download URL
**Reason**: Route mechanics, unchanged in substance.
**Migration**: `api-endpoints` → *Presigned S3 download URL*.

### Requirement: Normalized asset entry shape
**Reason**: Shape is surface — and `size` is removed from it (see below).
**Migration**: `api-endpoints` → *Per-device file listing*, whose closed shape is `{ filename, url }`.

### Requirement: Faithful outcome — no partial list
**Reason**: Stated once for every route rather than per endpoint.
**Migration**: `api-endpoints` → *Faithful outcome — no partial success, no partial list*.

### Requirement: Authorization by event id only
**Reason**: A read-authorization posture `device-attestation` already owns as entries 8 and 9 of its
closed list.
**Migration**: `device-attestation` → *Ungated routes are a closed list*.

### Requirement: Listed resource filename round-trips with the uploaded filename
**Reason**: The property survives and becomes structural rather than derived: the filename is a stored
column on the `resources` row, so no decoding of a storage key can disagree with it.
**Migration**: `api-endpoints` → *Per-device file listing*; `database` → *Five tables, with resources
outside the event ownership chain* (the `filename` column).

### Requirement: Event-wide union read route
**Reason**: Route mechanics.
**Migration**: `api-endpoints` → *The event union is one query over active and departed memberships*.

### Requirement: Union event-existence gate
**Reason**: Route mechanics; the gate now reads a row.
**Migration**: `api-endpoints` → *The event union…*; `database` → *Event existence is a row*.

### Requirement: Union device enumeration and per-device fan-out
**Reason**: The fan-out is what this change exists to remove. One directory listing plus one manifest read
per member becomes a single join, measured at 373–541 ms over 30 000 resource rows
(`PROBE-FINDINGS.md` §4.3).
**Migration**: `api-endpoints` → *The event union…*.

### Requirement: Union completeness — complete assets only
**Reason**: Completeness stops being "every named resource is present in a directory listing" and becomes
"every named resource's row carries `uploaded = 1`".
**Migration**: `api-endpoints` → *The event union…*.

### Requirement: The union byte-presence check is defense-in-depth
**Reason**: The check survives with its posture intact but a different second witness: two rows written by
two different requests, rather than a manifest cross-checked against a storage listing. The guarantee it
backs is unchanged, because the sweep still protects a referenced byte from collection.
**Migration**: `api-endpoints` → *The event union…*, third paragraph; `scheduled-cleanup` →
*Stale-asset collection*.

### Requirement: Union entry shape
**Reason**: Shape is surface, and **`size` is removed** from the resource element. It has no reader: the
iOS `UnionResource` model omits it, `HttpDeviceFilesSource` documents it as an ignored unknown key, and the
web zip page reads only `role`, `url`, `filename` and `key`. Keeping it would force a `size_bytes` column
writable only by the best-effort byte route, where one lost write would leave a NULL, make the closed shape
unemittable, and silently drop the asset from the union.
**Migration**: `api-endpoints` → *The event union…*; consumers need no change, since none read the field.

### Requirement: Union faithful outcome — no partial union
**Reason**: Stated once for every route rather than per endpoint.
**Migration**: `api-endpoints` → *Faithful outcome — no partial success, no partial list*.

### Requirement: Union authorization, identity-blindness, and caching
**Reason**: Authorization and caching are `device-attestation`'s; identity-blindness is a property of the
union shape, which carries `deviceId` per asset and no viewer identity at all.
**Migration**: `device-attestation` → *Ungated routes are a closed list* and *A gated response is never
cached*; `api-endpoints` → *The event union…*.
