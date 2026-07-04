## MODIFIED Requirements

### Requirement: Edge URL composition with injective filename encoding

The provider SHALL map a `Resource` to the URL
`<host>/devices/<deviceId>/files/<encoded-filename>`, where `host` is the injected compile-time
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
- **THEN** the URL path ends `/devices/<deviceId>/files/<filename>` unchanged

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
`<host>/devices/<deviceId>/files/<encoded-filename>` with no query string (no signature, no expiry
parameters).

#### Scenario: Only Content-Type is carried
- **WHEN** `provide` returns
- **THEN** `headers` contains exactly `Content-Type` and no `Host`, no auth, and no `x-*-meta-*`
  entries — even when `resource.metadata` is non-empty

#### Scenario: URL carries no auth query string
- **WHEN** `provide` returns
- **THEN** `url` is `<host>/devices/<deviceId>/files/<encoded-filename>` with no `?`-query parameters
