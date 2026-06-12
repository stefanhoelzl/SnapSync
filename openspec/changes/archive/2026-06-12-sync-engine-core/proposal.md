# Proposal: sync-engine-core

## Why

The UI/presentation half of SnapSync is built, but nothing produces real sync behavior — the status
screen runs only on harness-injected state. The backend architecture was designed and locked on
2026-06-11 (docs/design.md §2.2): a platform-driven decision core where platform adapters submit
events and a stateless shared engine answers with upload requests. This change delivers slice ① of
that plan — the shared-code rung of the engine track — so every later slice (JVM console, status
projection, desktop wiring, iOS adapter) has the frozen vocabulary and decision core to build on.

## What Changes

- Add the **platform seam vocabulary** to `:domain:sync` commonMain (resources-only — the asset
  layer is a later slice above this seam):
  - `Resource` — concrete value type (filename, contentType, metadata, `data: Any`); `data` is
    the opaque platform payload (`PHAssetResource`, bytes, path — always present; engine and
    provider never read it; deliberately `Any`, not a generic — a type parameter would infect
    every seam type and erase to `id` in the ObjC header anyway). Filename layout
    `<cloudId>-<kind>.<ext>` composed platform-side; the filename is pure identity — a plain
    string
  - `SyncEvent` — sealed: `ResourceChanged(resource)` | `UploadFailed(job, error)`
  - `UploadRequest` — complete executable request: url + exact headers + data (the resource)
  - `UploadJob` — request + attempt (0 = create platform job, >0 = retry)
  - `UploadError` — sealed: `Network` | `Http(status)` | `Cancelled` | `Unknown(detail)`
  - `UploadRequestProvider` — the engine's single dependency seam:
    `provide(resource): UploadRequest` (interface only; no impl in this slice). The provider owns
    **encoding and placement** of the filename (URL path vs header, prefix like `photos/`), under
    the contract: filename→destination is deterministic and injective
- Add **`SyncEngine`** — the stateless decision core: `suspend fun handle(event: SyncEvent):
  UploadJob` — `ResourceChanged` → job with attempt 0; `UploadFailed` → fresh job
  (`attempt + 1`, re-provided from `job.request.resource`), retry forever in v1.
- Add **unit tests** covering the engine's decision behavior, with a fake `UploadRequestProvider` living
  in test sources only.

**Explicitly out of scope** (later slices of the matrix): `DumbHttpRequestProvider` and `EngineConsole`
(slice ③, platform code), `StatusEvent`/`StatusReducer` (slice ②), desktop app changes (slice ⑤),
status↔screen bridge (slice ⑥), iOS adapter, S3 presigner provider.

## Capabilities

### New Capabilities

- `sync-engine`: the shared event→job decision core and its platform seam vocabulary — what
  events platforms may submit, what requests the engine answers with, key derivation and metadata
  merge rules, retry semantics, failure/concurrency contracts.

### Modified Capabilities

<!-- none — no existing spec's requirements change; the engine is new, UI specs untouched -->

## Impact

- **Code**: `:domain:sync` commonMain gains the seam types + engine; `commonTest`/`jvmTest` gains
  engine tests and a fake provider. No other module changes; no desktop app changes.
- **Dependencies**: none new — uses `kotlinx-coroutines` (already present) for `Flow`.
- **Compatibility**: purely additive; existing `SyncStatus`/`SyncStatusSource` and all UI/presentation
  code are untouched.
- **Docs**: docs/design.md already updated (2026-06-11) — §2.2 is the authoritative design this
  change implements.
