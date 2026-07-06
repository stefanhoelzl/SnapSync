# edge-upload-provider Specification

## Purpose

The on-device, network-free `UploadRequestProvider` that builds the bunny edge upload URL
(`/devices/<deviceId>/files/<encoded-filename>`) using only string-building — no crypto, no signing,
no network I/O. It sets `Content-Type` only and carries the deterministic, injective
filename→destination mapping that anchors upload idempotency. Lives in `:capability:upload-url`.
## Requirements
### Requirement: Pure URL-building provider

The capability SHALL provide `EdgeUploadRequestProvider`, a concrete `UploadRequestProvider`
(the sync-engine seam) that builds an executable `PUT` `UploadRequest` for a `Resource` using
**only string-building** — no network I/O, no HTTP client, and **no cryptography** (no signing,
no presigning, no payload hash). The returned `UploadRequest` SHALL carry the **same `Resource`
instance** supplied and SHALL NOT read `Resource.data`. The provider SHALL live in
`commonMain` so it is exercised on both the JVM and `iosSimulatorArm64`.

#### Scenario: Builds without I/O or crypto
- **WHEN** `provide(resource)` is called
- **THEN** an `UploadRequest` is returned with no network access and no signing, carrying a plain
  `PUT` URL

#### Scenario: Resource instance round-trips
- **WHEN** `provide(resource)` returns
- **THEN** `request.resource` is the identical instance supplied (no copy), and `resource.data` was
  never read

### Requirement: Edge URL composition with injective filename encoding

The provider SHALL map a `Resource` to the URL
`<host>/files/devices/<deviceId>/<encoded-filename>`, where `host` is the injected compile-time
base, and `<deviceId>` is injected verbatim (already a canonical UUID, not re-encoded). The byte
destination is **event-independent**: it carries no `eventId` and is partitioned only by
`deviceId`, so the same resource maps to the same byte destination regardless of which event it is
uploaded for (this is what makes bytes reusable across events). `<encoded-filename>` SHALL be the
percent-encoding of `resource.filename`'s UTF-8 bytes, escaping every byte outside `[A-Za-z0-9._-]`
as `%XX` with **uppercase** hex. The `filename → destination` mapping SHALL be **deterministic and
injective** (distinct filenames never collide) — the contract where upload idempotency lives. The
encoded filename SHALL be a single path segment: any `/` in the filename SHALL be escaped to `%2F`
so the edge endpoint decodes it back to one slash-free segment.

#### Scenario: Unreserved filename passes through
- **WHEN** the filename contains only `[A-Za-z0-9._-]`
- **THEN** the URL path ends `/files/devices/<deviceId>/<filename>` unchanged

#### Scenario: Reserved bytes percent-encode
- **WHEN** the filename contains bytes outside `[A-Za-z0-9._-]` (including multi-byte UTF-8 or `/`)
- **THEN** each such byte is emitted as uppercase `%XX` in the final path segment

#### Scenario: Distinct filenames never collide
- **WHEN** two different filenames are built
- **THEN** the resulting URLs differ

#### Scenario: Destination is event-independent
- **WHEN** the same `resource` is built for the same `(host, deviceId)`
- **THEN** the URL is the same byte destination with no `eventId` anywhere in the path

### Requirement: Returned request shape — Content-Type only, no auth, no metadata

`UploadRequest.headers` SHALL contain exactly `Content-Type` (from `resource.contentType`) and
nothing else — **no** authorization header (the edge byte route is ungated, addressed by the
`deviceId` in the path, with no event auth and no token), **no** `Host` (URL-implied), and **no**
custom metadata headers (the bunny native Storage API has none; `resource.metadata` SHALL NOT be
emitted as headers). `UploadRequest.url` SHALL be the complete edge URL
`<host>/files/devices/<deviceId>/<encoded-filename>` with no query string (no signature, no expiry
parameters).

#### Scenario: Only Content-Type is carried
- **WHEN** `provide` returns
- **THEN** `headers` contains exactly `Content-Type` and no `Host`, no auth, and no `x-*-meta-*`
  entries — even when `resource.metadata` is non-empty

#### Scenario: URL carries no auth query string
- **WHEN** `provide` returns
- **THEN** `url` is `<host>/files/devices/<deviceId>/<encoded-filename>` with no `?`-query parameters

### Requirement: Plain-string configuration contract

The provider SHALL take its two placement inputs — the edge `host` and the `deviceId` — as injected
**plain strings**, with no BuildKonfig dependency and no platform API calls inside the provider. The
byte URL no longer carries an `eventId`: bytes are device-partitioned and event-independent, so the
provider is parameterized by `deviceId` rather than by an event. The `deviceId` SHALL be sourced
from the `device-identity` seam at the consuming composition root (the provider neither mints nor
reads it). Sourcing those strings — host from the bundle, `deviceId` from the device-identity seam —
is the composition root's responsibility, not the provider's.

#### Scenario: Built from literal strings
- **WHEN** an `EdgeUploadRequestProvider` is constructed with literal `host`/`deviceId`
- **THEN** it builds requests against those values with no other configuration source and no
  platform or network call

### Requirement: Stable, no-expiry destinations

A request built by the provider SHALL be a **stable** URL with no expiry: re-building the request
for the same `(host, deviceId, resource.filename)` SHALL yield a byte-identical URL, so a retry
re-derived much later re-PUTs the exact same destination (nothing to re-mint or expire).

#### Scenario: Rebuild is byte-identical
- **WHEN** `provide` is called twice for the same resource with the same configuration
- **THEN** both calls produce byte-identical URLs and headers

