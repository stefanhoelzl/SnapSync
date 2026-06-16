# Proposal: s3-request-provider

## Why

The engine's `UploadRequestProvider` seam (shipped in the sync-engine slices) has exactly one
production implementation outstanding — the piece that turns a `Resource` into a real, executable
S3 upload. Today the only impls are test doubles (`RecordingUploadRequestProvider`). This is the
single biggest missing piece of platform-agnostic shared code: without it no platform can upload
anything.

iOS performs the actual `PUT` from its background upload-job extension, so the shared code's job is
**not** to upload — it is to **mint a presigned destination URL** the system can execute later. The
AWS SDK for Kotlin is JVM/Android-only (no Kotlin/Native), so the only single-codebase path is a
**hand-rolled AWS SigV4 presigner** in `commonMain`: pure cryptography and string-building, no
network I/O and no Ktor in v1. Design authority: docs/design.md §2.2 (seam), §3.1 (object keys),
§3.5 (reconstruction metadata), §4 (S3/auth/config), §3.3 (presigned-URL expiry).

## What Changes

- **New module `:capability:s3`** (`jvm()`-only target this slice; code in `commonMain`, written
  iOS-ready — KotlinCrypto-common + pure Kotlin only). Depends on `:domain:engine` for the
  `UploadRequestProvider` seam and `Resource`/`UploadRequest` types. iOS Gradle targets are
  deferred to the iOS adapter slice.
- **New public `S3UploadRequestProvider : UploadRequestProvider`** — maps `Resource` → object key +
  headers and delegates to an `internal` pure `S3SigV4Presigner` core (crypto + canonicalization +
  URL assembly). Two layers: the adapter owns `Resource`-mapping, the core owns signing.
- **New `S3Config` value type** (`bucket`, `region`, `endpoint`, `accessKeyId`, `secretAccessKey`,
  plain strings) — the presigner's injected input contract. This slice is **BuildKonfig-free**; the
  `BuildKonfig → S3Config` wiring is a later app-side concern.
- **Object key** = `resources/` + percent-encoded `resource.filename` (UTF-8, escape every byte
  outside `[A-Za-z0-9._-]` → uppercase `%XX`), deterministic and injective — the provider owns
  encoding and placement, per the seam contract.
- **SigV4 query-presigned PUT**, path-style addressing, `UNSIGNED-PAYLOAD`, signed header set
  `host` + `content-type` + `x-amz-meta-*`, 7-day default expiry (injected, validated ≤ 7 days).
  The engine's `Retry` path re-mints, so expiry self-heals.
- **Metadata → headers**: each `resource.metadata` entry becomes `x-amz-meta-<lowercased key>` with
  the value signed **verbatim**; values fail-fast-validated as ASCII without CR/LF; empty metadata
  → no meta headers.
- **Returned `UploadRequest.headers`** = `Content-Type` + `x-amz-meta-*` only (Host is
  URL-implied though signed); `url` carries the full auth query + signature; the **same `Resource`
  instance** round-trips.
- **New dependencies** (version catalog): KotlinCrypto `sha2` (SHA-256) + `hmac-sha2`
  (HMAC-SHA256), `kotlinx-datetime` (0.7.x line, operating on `kotlin.time.Instant`). Hex is
  hand-rolled (lowercase). **No Ktor.**
- **docs/design.md** updated: §3.1/§3.5 `photos/` → `resources/` placement prefix (the bucket holds
  every `PHAssetResource`, not only photos), and §6/§7 record s3mock as **deferred** rather than
  the chosen v1 guard.

**Explicitly out of scope** (later slices): the `BuildKonfig → S3Config` wiring and secret
plumbing; iOS Gradle targets, framework export, and on-device/Xcode S3 verification; the s3mock
Testcontainers round-trip; `ListObjectsV2` and the restore admin path; wiring the provider into any
composition root or the desktop console.

## Capabilities

### New Capabilities

- `s3-request-provider`: the first production `UploadRequestProvider` — mints presigned S3 `PUT`
  requests from resources via hand-rolled SigV4 (pure crypto + string-building, no network).

### Modified Capabilities

- None. The `sync-engine` `UploadRequestProvider` contract is implemented as-is, not changed.

## Impact

- **Code**: new `:capability:s3` commonMain (`S3Config`, `S3UploadRequestProvider`,
  `internal S3SigV4Presigner`, hex helper); commonTest (golden/known-answer tests, key-encoding and
  metadata-mapping tests). No changes to `:domain:engine`.
- **Dependencies**: KotlinCrypto `sha2` + `hmac-sha2`, `kotlinx-datetime`; added to the version
  catalog and `:capability:s3` only.
- **Validation**: `commonTest` golden tests are the sole signature guard (s3mock deferred); golden
  values generated from an independent SigV4 reference, with the generating command recorded in the
  test for reproducibility.
- **Compatibility**: additive — a new module and a new provider impl; no existing consumer changes.
- **Docs**: docs/design.md §3.1/§3.5/§6/§7.
