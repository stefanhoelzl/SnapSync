## MODIFIED Requirements

### Requirement: Edge URL composition with injective filename encoding

The provider SHALL map a `Resource` to the URL
`<host>/event/<eventId>/file/<encoded-filename>`, where `host` is the injected compile-time base, and
`<eventId>` is injected verbatim (already a canonical UUID, not re-encoded). `<encoded-filename>`
SHALL be the percent-encoding of `resource.filename`'s UTF-8 bytes, escaping every byte outside
`[A-Za-z0-9._-]` as `%XX` with **uppercase** hex. The `filename → URL` mapping SHALL be
**deterministic and injective** (distinct filenames never collide) — the contract where upload
idempotency lives. The encoded filename SHALL be a single path segment: any `/` in the filename SHALL
be escaped to `%2F` so the edge endpoint decodes it back to one slash-free segment.

#### Scenario: Unreserved filename passes through
- **WHEN** the filename contains only `[A-Za-z0-9._-]`
- **THEN** the URL path ends `/file/<filename>` unchanged

#### Scenario: Reserved bytes percent-encode
- **WHEN** the filename contains bytes outside `[A-Za-z0-9._-]` (including multi-byte UTF-8 or `/`)
- **THEN** each such byte is emitted as uppercase `%XX` in the `file/…` segment

#### Scenario: Distinct filenames never collide
- **WHEN** two different filenames are built
- **THEN** the resulting URLs differ

### Requirement: Returned request shape — Content-Type only, no auth, no metadata

`UploadRequest.headers` SHALL contain exactly `Content-Type` (from `resource.contentType`) and
nothing else — **no** authorization header (the edge endpoint authorizes by the `eventId` in the
path), **no** `Host` (URL-implied), and **no** custom metadata headers (the bunny native Storage
API has none; `resource.metadata` SHALL NOT be emitted as headers). `UploadRequest.url` SHALL be
the complete edge URL with no query string (no signature, no expiry parameters).

#### Scenario: Only Content-Type is carried
- **WHEN** `provide` returns
- **THEN** `headers` contains exactly `Content-Type` and no `Host`, no auth, and no `x-*-meta-*`
  entries — even when `resource.metadata` is non-empty

#### Scenario: URL carries no auth query string
- **WHEN** `provide` returns
- **THEN** `url` is `<host>/event/<eventId>/file/<encoded-filename>` with no `?`-query parameters

### Requirement: Plain-string configuration contract

The provider SHALL take its two placement inputs — the edge `host` and the `eventId` — as injected
**plain strings**, with no BuildKonfig dependency and no platform API calls inside the provider.
(Sourcing those strings — host from the bundle, eventId from the Keychain — is the consuming
composition root's responsibility, not the provider's.)

#### Scenario: Built from literal strings
- **WHEN** an `EdgeUploadRequestProvider` is constructed with literal `host`/`eventId`
- **THEN** it builds requests against those values with no other configuration source and no
  platform call

### Requirement: Stable, no-expiry destinations

A request built by the provider SHALL be a **stable** URL with no expiry: re-building the request
for the same `(host, eventId, resource.filename)` SHALL yield a byte-identical URL, so a retry
re-derived much later re-PUTs the exact same destination (nothing to re-mint or expire).

#### Scenario: Rebuild is byte-identical
- **WHEN** `provide` is called twice for the same resource with the same configuration
- **THEN** both calls produce byte-identical URLs and headers
