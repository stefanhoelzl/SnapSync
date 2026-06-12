# Tasks: sync-engine-core

## 1. Seam vocabulary (`:domain:sync` commonMain, package `app.snapsync.sync`)

- [x] 1.1 Add `Resource` concrete class (filename, contentType, metadata, `data: Any`) with
      KDoc: `data` is the opaque platform payload, always present, never read by engine or
      provider (deliberately `Any`, not a generic — see change design); the filename is pure
      identity and its layout is the caller's; the engine never constructs Resources
- [x] 1.2 Add `UploadRequest` (url, headers, resource: Resource) and `UploadJob` (request,
      attempt) with KDoc for the round-trip/retention rule ("platform must be able to produce the
      newest job on demand")
- [x] 1.3 Add `SyncEvent` sealed interface (`ResourceChanged`, `UploadFailed`) and the
      `UploadError` sealed hierarchy (`Network` / `Http(status)` / `Cancelled` / `Unknown(detail)`)
- [x] 1.4 Add `UploadRequestProvider` interface (`provide(resource): UploadRequest`) with KDoc
      stating the provider contract: filename→destination mapping is deterministic and injective;
      encoding and placement (e.g. `photos/` prefix) are the provider's; the returned request
      carries the same resource instance (and `data` is never read); must tolerate concurrent
      `provide` calls

## 2. Engine

- [x] 2.1 Implement `SyncEngine(provider)` with `suspend fun handle(event: SyncEvent): UploadJob`
      — `ResourceChanged(r)` → `UploadJob(provide(r), 0)`; `UploadFailed(job, e)` →
      `UploadJob(provide(job.request.resource), job.attempt + 1)` (retry forever); no catch blocks
      (rethrow contract)
- [x] 2.2 KDoc the engine contracts: statelessness, re-handle safety, concurrent calls allowed

## 3. Tests (`commonTest`, kotlinx-coroutines-test)

- [x] 3.1 Add recording fake `UploadRequestProvider` (invocation log; scriptable results and
      throws) in test sources
- [x] 3.2 `ResourceChanged`: one job with attempt 0; provider invoked once with the same resource
      instance; `job.request` is the provider's return value unmodified; `job.request.resource`
      identity round-trip
- [x] 3.3 `UploadFailed`: attempt incremented; request freshly minted via
      `provide(job.request.resource)` — for every `UploadError` variant
- [x] 3.4 Provider failure: exception propagates unswallowed from `handle`; the same event
      succeeds on a later call when the provider does
- [x] 3.5 Concurrency: two concurrent `handle` calls against a thread-safe fake return exactly
      their own resources' jobs

## 4. Verify

- [x] 4.1 `./gradlew :domain:sync:check` green; no changes outside `:domain:sync`; no new
      dependencies in `gradle/libs.versions.toml`
