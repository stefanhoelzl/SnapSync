# s3 request provider Specification (new)

## Purpose

The first production `UploadRequestProvider` (the sync-engine seam): it mints an executable,
presigned S3 `PUT` `UploadRequest` for a `Resource` using only string-building and cryptography —
no network I/O. iOS's background upload-job system performs the actual `PUT` against the minted
URL; this capability never transmits bytes. Object-key encoding and placement live here, under the
seam's deterministic-and-injective contract. Authoritative design: docs/design.md §2.2 (seam),
§3.1 (object keys), §3.5 (reconstruction metadata), §4 (S3/auth/config), §3.3 (expiry).

## ADDED Requirements

### Requirement: Pure request-minting provider
The capability SHALL provide `S3UploadRequestProvider`, a concrete `UploadRequestProvider` that
mints a presigned S3 `PUT` `UploadRequest` for a `Resource` using only string-building and
cryptography — no network I/O and no HTTP client. The returned `UploadRequest` SHALL carry the
**same `Resource` instance** supplied and SHALL NOT read `Resource.data`. Minting failures SHALL be
thrown, never masked.

#### Scenario: Mints without I/O
- **WHEN** `provide(resource)` is called
- **THEN** an `UploadRequest` is returned with no network access, carrying a presigned `PUT` URL

#### Scenario: Resource instance round-trips
- **WHEN** `provide(resource)` returns
- **THEN** `request.resource` is the identical instance supplied (no copy), and `resource.data` was
  never read

#### Scenario: Invalid input throws
- **WHEN** a resource cannot be minted into a valid request (e.g. an illegal metadata value)
- **THEN** `provide` throws rather than returning a malformed request

### Requirement: Object key — resources/ prefix, injective encoding
The provider SHALL map `resource.filename` to the object key `resources/` followed by the
percent-encoding of the filename's UTF-8 bytes, escaping every byte outside `[A-Za-z0-9._-]` as
`%XX` with **uppercase** hex. The mapping SHALL be deterministic and injective — distinct filenames
never collide. The `resources/` separator SHALL be the only literal `/` in the key; any `/` within
the filename SHALL be escaped to `%2F`.

#### Scenario: Unreserved filename passes through
- **WHEN** the filename contains only `[A-Za-z0-9._-]`
- **THEN** the key is `resources/<filename>` unchanged

#### Scenario: Reserved bytes percent-encode
- **WHEN** the filename contains bytes outside `[A-Za-z0-9._-]` (including multi-byte UTF-8)
- **THEN** each such byte is emitted as uppercase `%XX`

#### Scenario: Slash in filename is escaped
- **WHEN** the filename contains `/`
- **THEN** it is encoded as `%2F` and the only literal `/` in the key is the `resources/` separator

#### Scenario: Distinct filenames never collide
- **WHEN** two different filenames are encoded
- **THEN** the resulting keys differ

### Requirement: SigV4 query-presigned PUT
The provider SHALL sign requests with AWS Signature Version 4 in **query-string (presigned)** form,
**path-style** addressing (`<endpoint>/<bucket>/<key>`), service `s3`, region from `S3Config`. The
canonical request SHALL use `UNSIGNED-PAYLOAD` as the payload hash and sign exactly the header set
`host`, `content-type`, and every `x-amz-meta-*` header. The auth parameters `X-Amz-Algorithm`
(`AWS4-HMAC-SHA256`), `X-Amz-Credential`, `X-Amz-Date`, `X-Amz-Expires`, and `X-Amz-SignedHeaders`
SHALL be carried in the query string, RFC 3986-encoded (so `/` → `%2F`, `;` → `%3B`) and sorted by
encoded key; `X-Amz-Signature` SHALL be computed last and appended to the URL. The signing key
SHALL be derived by the HMAC-SHA256 chain `"AWS4"+secret → dateStamp → region → "s3" →
"aws4_request"`, and the signature rendered as **lowercase** hex. The wire URL path SHALL be
byte-identical to the canonical URI (single path encoding — the S3 exception).

#### Scenario: Matches an independent SigV4 reference
- **WHEN** a request is presigned for a fixed `(accessKey, secretKey, region, bucket, key, content
  type, x-amz-meta-* set, timestamp, expiry)` tuple
- **THEN** the full presigned URL and `X-Amz-Signature` are byte-identical to the output of an
  independent SigV4 implementation (the golden oracle — e.g. `aws s3presign` / boto3 — named and
  reproduced by a recorded command in the test)

#### Scenario: Unsigned payload
- **WHEN** the canonical request is built
- **THEN** its payload-hash position is the literal `UNSIGNED-PAYLOAD`, and no
  `x-amz-content-sha256` header is signed or sent

#### Scenario: Signed header set is exact
- **WHEN** a request with content-type and metadata is presigned
- **THEN** `X-Amz-SignedHeaders` is exactly `content-type;host;x-amz-meta-…` (sorted, lowercase) and
  nothing else is signed

#### Scenario: Host carries a non-default port
- **WHEN** the endpoint authority includes a non-default port
- **THEN** the signed `host` canonical header and the URL authority both include that port

### Requirement: Metadata to x-amz-meta headers
The provider SHALL map each `resource.metadata` entry to a header named `x-amz-meta-` + the
**lowercased** key, with the value passed through **verbatim** (the provider performs no value
encoding). Empty metadata SHALL produce no `x-amz-meta-*` headers. Each value SHALL be validated as
ASCII with no control, CR, or LF characters; a violation SHALL throw.

#### Scenario: Name lowercased, value verbatim
- **WHEN** metadata contains an entry with a mixed-case key
- **THEN** the header name is `x-amz-meta-<lowercased key>` and the value is unchanged

#### Scenario: Empty metadata
- **WHEN** `resource.metadata` is empty
- **THEN** only `content-type` (besides `host`) is signed, with no `x-amz-meta-*` headers

#### Scenario: Illegal value throws
- **WHEN** a metadata value contains a non-ASCII or CR/LF character
- **THEN** `provide` throws

### Requirement: Returned request shape
`UploadRequest.headers` SHALL contain exactly `Content-Type` plus the `x-amz-meta-*` set, and SHALL
NOT contain `Host` (URL-implied, though signed). `UploadRequest.url` SHALL be the complete presigned
URL including the auth query string and the computed signature.

#### Scenario: Headers exclude Host but carry content-type and metadata
- **WHEN** `provide` returns
- **THEN** `headers` contains `Content-Type` and every `x-amz-meta-*` header, and no `Host` entry

#### Scenario: URL carries auth and signature
- **WHEN** `provide` returns
- **THEN** `url` contains the path-style object path and all `X-Amz-*` auth parameters including
  `X-Amz-Signature`

### Requirement: Expiry policy
Presign expiry SHALL be an injected `expiresIn` duration, defaulting to **7 days**, validated at
construction as `0 < expiresIn ≤ 7 days` (the SigV4 maximum). `X-Amz-Expires` SHALL be expressed in
whole seconds.

#### Scenario: Default expiry
- **WHEN** `S3UploadRequestProvider` is constructed without an explicit `expiresIn`
- **THEN** minted URLs carry `X-Amz-Expires=604800` (7 days)

#### Scenario: Rejects out-of-range expiry
- **WHEN** the provider is constructed with `expiresIn` ≤ 0 or > 7 days
- **THEN** construction throws

### Requirement: Configuration contract
The provider SHALL take an injected `S3Config` value type carrying `bucket`, `region`, `endpoint`,
`accessKeyId`, and `secretAccessKey` as plain strings, with **no** build-time-config (BuildKonfig)
dependency. `endpoint` SHALL be a scheme + authority base URL; the signed `host` SHALL be its
authority, including a non-default port.

#### Scenario: Built from a plain config
- **WHEN** an `S3Config` is constructed with literal strings and handed to the provider
- **THEN** the provider mints requests against that bucket/region/endpoint with no other
  configuration source

### Requirement: Deterministic signing via injected clock
The provider SHALL read the signing timestamp from an injected `kotlin.time.Clock` (default
`Clock.System`), and the pure presigner core SHALL accept the timestamp as an explicit
`kotlin.time.Instant` parameter, so that a fixed clock yields byte-identical output. `X-Amz-Date`
(`yyyyMMdd'T'HHmmss'Z'`) and the credential `dateStamp` (`yyyyMMdd`) SHALL both be UTC and derived
from the same instant.

#### Scenario: Fixed clock is reproducible
- **WHEN** the provider is given a fixed clock and `provide` is called twice for the same resource
- **THEN** both calls produce byte-identical URLs and headers

#### Scenario: Date fields agree
- **WHEN** a request is signed at a given instant
- **THEN** `X-Amz-Date` and the credential-scope `dateStamp` are both UTC and consistent with that
  single instant
