# Design: s3-request-provider

## Context

The sync engine answers `Work` decisions by calling `UploadRequestProvider.provide(resource)` and
carrying the returned `UploadRequest` unmodified (docs/design.md §2.2; `SyncEngine.kt`). Every
production path needs a real provider; only test doubles exist today. iOS does the actual upload
`PUT` from a `PHBackgroundResourceUploadJobExtension` against a destination URL we mint, so the
shared code must produce a **presigned** URL — pure cryptography, no network. The AWS SDK for Kotlin
has no Kotlin/Native target, so SigV4 is hand-rolled in `commonMain`.

This change was scoped in a design interview (2026-06-15). The decisions below are the interview's
resolutions; they are recorded here so the spec stays about *what* and this stays about *why*.

## Goals / Non-Goals

**Goals:**
- A pure `UploadRequestProvider` that mints presigned S3 `PUT` requests, deterministic under a
  fixed clock, signature-correct against an independent SigV4 reference.
- Keep the decision/seam core (`:domain:engine`) free of crypto and HTTP dependencies.
- Land fully testable without the BuildKonfig config workspace or any iOS toolchain.

**Non-Goals:**
- No network I/O, no Ktor, no actual `PUT` (iOS's job system does that).
- No `ListObjectsV2`, no restore/admin path (separate, restore-only).
- No BuildKonfig wiring, no iOS Gradle targets, no s3mock round-trip (all later slices).

## Decisions

- **Module placement — new `:capability:s3`** (vs folding into `:domain:engine`). Design §8 already
  reserves `:capability:s3` as "one `UploadRequestProvider` impl"; isolating KotlinCrypto (and any
  future Ktor) keeps the decision seam every consumer depends on pure. Dependency arrow:
  `:capability:s3` → `:domain:engine`.
- **`jvm()`-only this slice; code in `commonMain`.** Every existing module is `jvm()`-only; iOS
  targets, framework export, and the klib recheck are a deliberate later slice. `commonMain` keeps
  the code iOS-ready *structurally*, disciplined to KotlinCrypto-common + pure-Kotlin APIs. Accepted
  cost: no compile-time iOS proof until that slice.
- **Two layers.** `internal S3SigV4Presigner` (pure: method, key, signed headers, expiry seconds,
  `Instant` → `UploadRequest`) + public `S3UploadRequestProvider` (maps `Resource`). Mirrors the
  seam's own split of responsibility (provider owns encoding/placement/headers; presigner owns
  signing) and gives golden tests a primitive-input surface. The core stays `internal` —
  `commonTest` sees it within the module.
- **Config via injected `S3Config` value type, BuildKonfig-free.** Config is the presigner's input
  contract and lives with the presigner; BuildKonfig becomes one *producer* of it later. Plain
  `String` credentials (the design already accepts IPA-extractable keys); no STS/session token.
- **Path-style addressing with an explicit `endpoint` base URL.** One code path for AWS and any
  S3-compatible endpoint; Host = endpoint authority (port-qualified when non-default); canonical
  URI = `/<bucket>/<key>`. Virtual-hosted is a localized later change if ever needed.
- **`resources/` placement prefix** (not `photos/`). The bucket holds every `PHAssetResource` —
  originals, edited renders, `.adjustmentData`, Live Photo paired *videos* — so `resources/` is the
  honest name. Updates design.md §3.1/§3.5.
- **Expiry = injected `expiresIn: Duration = 7.days`, validated `0 < d ≤ 7d`.** Expiry is a
  presigner policy knob, not a config/credential fact. 7 days (the SigV4 maximum) minimizes
  expiry-induced retry churn during long deferred background execution; the only cost of a long
  window (a leaked single-key URL) is dominated by the already-accepted IPA-embedded-key risk. The
  engine's `Retry` re-mints, so expiry self-heals regardless.
- **Signed set = `host` + `content-type` + `x-amz-meta-*`; `UNSIGNED-PAYLOAD`.** No
  `x-amz-content-sha256` header (that is the header-auth flavor), no operational headers (ACL/SSE/
  storage-class) in v1.
- **Metadata values signed verbatim; names lowercased.** Value *encoding* (URL-encode/base64) is a
  restore-format concern owned by the asset/metadata layer that also decodes on restore — the
  transport presigner stays faithful. Names must lowercase (SigV4 canonical headers + S3 storage
  both lowercase). Fail-fast ASCII/no-CRLF validation on values surfaces caller bugs as thrown
  errors (per the seam's "failures are thrown" contract).
- **Returned `headers` exclude Host.** Auth is in the query string; the client derives Host from the
  URL (and it is signed via `X-Amz-SignedHeaders`). Matches `RecordingUploadRequestProvider`.
- **Clock = injected `kotlin.time.Clock = Clock.System`** (project convention: `Ledger.kt`,
  `StatusContainerHost.kt`). The pure core takes a fixed `Instant`, so golden tests are
  byte-deterministic. **`kotlinx-datetime` (0.7.x)** formats `Instant` via `toLocalDateTime(UTC)` —
  chosen over hand-rolled civil-date math to remove a leap-year-class bug surface; it operates on
  `kotlin.time.Instant` so there is no competing `Instant` type at the seam.
- **Crypto = KotlinCrypto `sha2` + `hmac-sha2`** (KMP with iOS targets), lowercase hand-rolled hex.
- **Testing — golden/known-answer only this slice (s3mock deferred).** Because s3mock does not
  validate SigV4, the golden test is the *only* signature guard and must pin output from an
  independent reference. It also pins the full URL string (path/key encoding, query params,
  `SignedHeaders`), so request *shape* is covered as string construction; live-server acceptance and
  metadata round-trip under lowercased keys are deferred to on-device verification. Updates design.md
  §6/§7 (s3mock → deferred).

## SigV4 correctness notes (the silent-403 traps)

A hand-rolled query presign fails closed (403) on small mistakes; the implementation and golden
tests must lock these down:

1. **Double-encode the canonical query *values*** (RFC 3986): `X-Amz-Credential`'s `/` → `%2F`,
   `X-Amz-SignedHeaders`'s `;` → `%3B`. Sort params by encoded key.
2. **`X-Amz-Signature` is computed last** and appended to the URL — it is the only auth param not in
   the canonical request.
3. **Single-encode the path (S3 exception)** and reuse the *same* encoded string for both the wire
   URL path and the canonical URI — they must be byte-identical.
4. **Host carries the port** when non-default; the exact Host string is what is signed.
5. **`amzDate` (`yyyyMMdd'T'HHmmss'Z'`) and `dateStamp` (`yyyyMMdd`)** are both UTC and derived from
   the *same* `Instant`; `amzDate` goes in `X-Amz-Date`, `dateStamp` in the credential scope.
6. **Signing-key chain**: `HMAC("AWS4"+secret, dateStamp) → region → "s3" → "aws4_request"`; the
   final signature is `lowercaseHex(HMAC(signingKey, stringToSign))`.

## Open risks

- **No real-AWS signature check in CI.** s3mock won't validate and tests won't hit AWS; the
  golden-vs-reference test is the proxy, backed later by on-device verification (design §3.3/§8).
- **iOS compile proof deferred.** A `jvm()`-only `commonMain` cannot catch an accidental JVM-only
  API leak; mitigated by discipline (KotlinCrypto-common + pure Kotlin) and a cheap future iOS-target
  smoke test.
