# Tasks: s3-request-provider

## 1. Module & dependencies

- [x] 1.1 Add to the version catalog: `kotlinx-datetime` (0.7.x line — must operate on
      `kotlin.time.Instant`), KotlinCrypto `sha2` and `hmac-sha2` (pin the current stable line);
      verify coordinates resolve
- [x] 1.2 Create `:capability:s3` with `build.gradle.kts` (kotlin multiplatform plugin, `jvm()`
      target only, `jvmToolchain` per catalog), register it in `settings.gradle.kts`
- [x] 1.3 commonMain deps: `:domain:engine`, `kotlinx-datetime`, kotlincrypto `sha2` + `hmac-sha2`;
      commonTest deps: `kotlin("test")`. Verify `./gradlew :capability:s3:build` is green (empty)

## 2. Config & hex

- [x] 2.1 `S3Config(bucket, region, endpoint, accessKeyId, secretAccessKey)` — plain-`String` value
      type in commonMain; KDoc: injected input contract, BuildKonfig-free
- [x] 2.2 `internal` lowercase hex helper (`ByteArray → String`) with its own unit test

## 3. SigV4 presigner core

- [x] 3.1 `internal S3SigV4Presigner(config)` with
      `presign(method, key, signedHeaders: Map<String,String>, expiresSeconds: Long, timestamp: Instant): UploadRequest`
- [x] 3.2 Timestamp formatting via `kotlinx-datetime` `toLocalDateTime(TimeZone.UTC)`: `amzDate`
      (`yyyyMMdd'T'HHmmss'Z'`) and `dateStamp` (`yyyyMMdd`), both from the same instant
- [x] 3.3 Canonical request: path-style canonical URI `/<bucket>/<key>` (single-encode, literal
      bucket + separators); canonical query (auth params, RFC 3986-encoded values incl. `%2F`/`%3B`,
      sorted by encoded key); canonical headers (sorted lowercase: host[:port], content-type,
      x-amz-meta-*); `UNSIGNED-PAYLOAD`
- [x] 3.4 String-to-sign: `SHA256(canonicalRequest)` (kotlincrypto sha2 → lowercase hex);
      signing-key HMAC chain (`AWS4`+secret → dateStamp → region → `s3` → `aws4_request`); signature
      = lowercase hex; append `X-Amz-Signature` to the URL last
- [x] 3.5 Assemble `UploadRequest(url, headers, resource=null-placeholder at this layer)` — the core
      returns url+headers; the adapter attaches the `Resource` (keep the core `Resource`-free)

## 4. Provider adapter

- [x] 4.1 `S3UploadRequestProvider(config, expiresIn: Duration = 7.days, clock: Clock = Clock.System)`
      `: UploadRequestProvider`; `init` validates `0 < expiresIn ≤ 7.days`
- [x] 4.2 Object key: `resources/` + percent-encode `filename` UTF-8 bytes outside `[A-Za-z0-9._-]`
      → uppercase `%XX` (`/` in filename → `%2F`)
- [x] 4.3 Headers: `content-type` = `resource.contentType`; each metadata entry →
      `x-amz-meta-<lowercased key>` verbatim value; validate values ASCII/no-CR-LF (throw on
      violation); empty metadata → none
- [x] 4.4 `provide`: read `clock.now()`, build key + signed headers, delegate to the core, return an
      `UploadRequest` carrying the **same `Resource` instance**, `headers` = content-type +
      x-amz-meta-* (no Host)

## 5. Tests (commonTest)

- [x] 5.1 Key-encoding tests: unreserved pass-through, reserved → uppercase `%XX`, `/` in filename
      escaped + literal prefix slash, injectivity
- [x] 5.2 Metadata-mapping tests: name lowercased, value verbatim, empty → none, non-ASCII/CRLF
      value throws
- [x] 5.3 Expiry-validation tests: default 7d, rejects `> 7d` and non-positive at construction,
      `X-Amz-Expires` whole seconds
- [x] 5.4 **Golden / known-answer test**: fixed `(accessKey, secretKey, region, bucket, key-name,
      timestamp, expiry, content-type, x-amz-meta-*)` → assert exact presigned URL + signature, with
      the **generating reference command recorded in a comment** (independent SigV4 oracle, e.g.
      `aws s3presign` / boto3). Include focused canonical-request + string-to-sign asserts so a
      failure localizes
- [x] 5.5 Determinism test: a fixed `Clock` yields byte-identical output across calls; host carries
      a non-default port when the endpoint has one

## 6. Documentation

- [x] 6.1 docs/design.md §3.1/§3.5: `photos/` → `resources/` placement prefix (and the restore-side
      `LIST` prefix example), with a one-line note on why (all `PHAssetResource`s, not only photos)
- [x] 6.2 docs/design.md §6/§7: record s3mock as **deferred** (golden tests are the v1 signature
      guard), keep s3mock listed as a later validation step
- [x] 6.3 Verify full build + tests green (`./gradlew build`)
