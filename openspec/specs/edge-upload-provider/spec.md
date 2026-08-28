# edge-upload-provider Specification

## Purpose

The on-device, network-free `UploadRequestProvider` that builds the byte upload URL
(`/files/devices/<deviceId>/<assetId>/<role>?filename=<capture name>`) using only string-building — no
crypto, no signing, no network I/O. It sets `Content-Type`, the calling build's marketing version
(capability `min-app-version`), and, when one is available, the device token's `Authorization: Bearer`
header (capability `device-attestation`) — reading that token is the provider's only side effect, and it
still mints nothing. It carries the deterministic, injective resource→destination mapping that anchors
upload idempotency, and the destination it composes resolves to the **same stored object name** the
previous URL shape did, which is what makes crossing versions cost no re-upload. Lives in `:domain`'s `model/` zone
(seated by migration step 3a).
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
`<host>/files/devices/<deviceId>/<assetId>/<role>?filename=<encoded-capture-name>`, where `host` is the
injected compile-time base (carrying exactly one `/api/vN` prefix) and `<deviceId>` is injected verbatim
(already a canonical UUID, not re-encoded). The byte destination is **event-independent**: it carries no
`eventId` and is partitioned only by `deviceId`, so the same resource maps to the same byte destination
regardless of which event it is uploaded for (this is what makes bytes reusable across events).

`<assetId>` and `<role>` SHALL be derived from `resource.filename` — the ledger key, shaped
`<assetId>-<role>.<ext>` — through the shared `assetIdFromUploadKey` / `roleFromUploadKey` parsers, so the
one definition of that layout stays in `model/`. Each SHALL be emitted as a single path segment,
percent-encoding every byte outside `[A-Za-z0-9._-]` as `%XX` with **uppercase** hex; any `/` SHALL be
escaped to `%2F` so the endpoint decodes it back to one slash-free segment.

`<encoded-capture-name>` SHALL be the same percent-encoding applied to the resource's capture filename,
taken from `resource.metadata`'s `RESOURCE_META_ORIGINAL_FILENAME` entry, treating a blank value as absent
and **falling back to `resource.filename`** (the key itself). The fallback is exact rather than
approximate: the endpoint consumes only the value's **extension** when composing the stored object name,
and the key carries the same extension as the capture name it was built from — so a request built on the
retry path, where metadata is empty, addresses a byte-identical object. It is required because the query
parameter is mandatory and an empty value is refused.

The `resource → destination` mapping SHALL remain **deterministic and injective** (distinct resources never
collide) — the contract where upload idempotency lives — and the stored object name it resolves to SHALL be
byte-identical to the one v1 composed for the same resource, so a device crossing versions finds its bytes
where it left them and re-uploads nothing.

#### Scenario: Identity is named in the path
- **WHEN** `provide` is called for a resource whose key is `<assetId>-<role>.<ext>`
- **THEN** the URL path ends `/files/devices/<deviceId>/<assetId>/<role>`, with no synthetic object name in
  any segment

#### Scenario: The capture name travels as a required query parameter
- **WHEN** the resource carries `RESOURCE_META_ORIGINAL_FILENAME`
- **THEN** the URL carries `?filename=` with that value percent-encoded, and the value never appears in any
  path segment

#### Scenario: A rebuilt resource with no metadata still addresses the same object
- **WHEN** `provide` is called for a `Resource` rebuilt from a job key alone, so its metadata is empty
- **THEN** the query carries the key as the capture name, and the object name the endpoint composes is
  byte-identical to the one the original request resolved to

#### Scenario: Reserved bytes percent-encode
- **WHEN** an `assetId`, `role` or capture name contains bytes outside `[A-Za-z0-9._-]` (including
  multi-byte UTF-8 or `/`)
- **THEN** each such byte is emitted as uppercase `%XX`

#### Scenario: Distinct resources never collide
- **WHEN** two different resources are built
- **THEN** the resulting URLs differ

#### Scenario: Destination is event-independent
- **WHEN** the same `resource` is built for the same `(host, deviceId)`
- **THEN** the URL is the same byte destination with no `eventId` anywhere in it

#### Scenario: The object name is unchanged across versions
- **WHEN** the same asset and role are uploaded under the v1 destination and the v2 destination
- **THEN** both resolve to the same stored object name

### Requirement: Returned request shape — Content-Type and Authorization, no metadata

`UploadRequest.headers` SHALL contain exactly `Content-Type`, `Authorization` (`Bearer <token>`, the device
token of capability `device-attestation`) and the **app-version header** carrying the calling build's
marketing version (capability `min-app-version`) — and nothing else: **no** `Host` (URL-implied) and **no**
custom metadata headers (the bunny native Storage API has none; `resource.metadata` SHALL NOT be emitted as
headers).

The app-version header is required here and not only on the shared HTTP client because **the OS performs
this request**: it is handed to the platform's background-upload subsystem and issued later, outside any
client this app controls, so a header the client adds cannot reach it. A v2 request that does not declare
the version is refused `426`.

`UploadRequest.url` SHALL be the complete edge URL described above, carrying the mandatory `filename` query
and **no credential parameters** (no signature, no expiry) — the credential rides in the header, never in
the URL, so the URL stays **stable with no expiry** and a retry re-derives a byte-identical destination.

`Content-Type` SHALL be the resource's **MIME type**, taken from `resource.metadata`'s
`RESOURCE_META_MIME` entry (resolved platform-side — on iOS by `UTType.preferredMIMEType`), treating a
blank value as absent and falling back to `resource.contentType`. It SHALL NOT be `resource.contentType`
by default: on iOS that field is the PhotoKit **UTI** (`public.jpeg`), which is not a media type and
which no HTTP client, CDN or browser interprets — every object uploaded before this rule was stored
typed with it (measured at the origin, SE2 / iOS 26.6). This is the same preference every other consumer
of a resource already applies (`toLedgerRow`), so the stored object's type agrees with the device
manifest and the event union rather than contradicting them.

Reading one metadata **value** to populate a header the contract already requires is distinct from
emitting metadata **as headers**, which stays prohibited above.

The fallback to `resource.contentType` is load-bearing rather than defensive: the retry path rebuilds a
`Resource` from the job key alone with empty metadata, and the platform supplies the type recovered from
the job's stored request — so the fallback is the seam through which a retried upload keeps its original
type instead of acquiring a default.

The token SHALL be re-read on **every** call to `provide`, never captured once at construction: the
engine re-mints the request from this provider on each retry, and that is precisely what allows an
upload that failed on an expired token to succeed once the app has renewed, with no special-casing
anywhere in the upload path.

When no token is available, `provide` SHALL still return a request (omitting the header) rather than
failing. The resulting `401` is a retryable failure like any other; refusing to build a request would
strand the resource instead.

#### Scenario: Content-Type, Authorization and the app version are carried

- **WHEN** `provide` returns and a token is available
- **THEN** `headers` contains exactly `Content-Type`, `Authorization: Bearer <token>` and the app-version
  header — no `Host` and no `x-*-meta-*` entries, even when `resource.metadata` is non-empty

#### Scenario: The version rides on the OS-performed request

- **WHEN** the composed request is handed to the platform's background-upload subsystem and issued later
- **THEN** it declares the app version itself, because no client this app controls issues it

#### Scenario: Content-Type is the MIME type, not the platform UTI

- **WHEN** a resource carries `RESOURCE_META_MIME` of `image/jpeg` and a `contentType` of `public.jpeg`
- **THEN** the request's `Content-Type` is `image/jpeg`

#### Scenario: A resource with no MIME metadata falls back to its content type

- **WHEN** a resource carries no `RESOURCE_META_MIME` entry, or a blank one — as a `Resource` rebuilt
  from a job key on the retry path does
- **THEN** the request's `Content-Type` is `resource.contentType`, so a retried upload keeps the type its
  platform recovered rather than acquiring a default

#### Scenario: URL carries no auth query string

- **WHEN** `provide` returns
- **THEN** `url` carries the mandatory `filename` parameter and no signature or expiry parameters

#### Scenario: A retry picks up a refreshed token

- **WHEN** an upload fails with `401` on an expired token, the app then renews, and the engine re-mints
  the request for that resource
- **THEN** the rebuilt request carries the **new** token, and the URL is byte-identical to the original

#### Scenario: A missing token still yields a request

- **WHEN** `provide` is called and no token is present in the shared Keychain
- **THEN** a request is returned with `Content-Type` and the app-version header but no `Authorization`
  header, and the upload is allowed to fail and be retried rather than being abandoned

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
