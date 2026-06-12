# Design: sync-engine-core

## Context

The authoritative architecture lives in **docs/design.md §2.2** ("Platform seam: event → job
decision core", decided 2026-06-11 after a full design interview + exploration). This document does
not duplicate it — it records only the implementation-level decisions for slice ①. Read §2.2 first;
the type vocabulary, engine behavior, platform contract, and accepted costs are defined there.

Current state: `:domain:sync` contains only the presentation-facing snapshot seam (`SyncStatus`,
`SyncState`, `SyncStatusSource`). Nothing produces sync behavior yet.

## Goals / Non-Goals

**Goals:**
- Freeze the platform seam vocabulary in `:domain:sync` commonMain exactly as specified in
  docs/design.md §2.2.
- Implement `SyncEngine` with full unit-test coverage of the spec scenarios.
- Zero new dependencies, zero changes outside `:domain:sync`.

**Non-Goals (later slices of the 2×3 matrix, see docs/design.md §8):**
- `DumbHttpRequestProvider`, `EngineConsole` (slice ③ — platform integration).
- `StatusEvent` / `StatusReducer` (slice ② — status shared core).
- Desktop harness changes (slice ⑤), status↔screen bridge (slice ⑥), iOS adapter, S3 presigner.

## Decisions

- **Package layout**: everything in `app.snapsync.sync` alongside the existing `SyncStatus` types —
  one flat package until size demands otherwise. The whole seam vocabulary + engine live in a
  **single file** (`SyncEngine.kt`), per Kotlin convention (multiple semantically-related
  declarations per file are encouraged) and matching the module's existing style
  (`SyncStatus.kt` holds `SyncStatus` + `SyncState`): the entire sync domain reads on one screen.
- **Resources-only domain**: the seam knows `Resource` — a concrete class (filename, contentType,
  metadata, `data: Any`). `data` is the explicit opaque platform payload (`PHAssetResource`,
  bytes, path), always present; engine and provider never read it; it is the one non-serializable
  field (platforms rehydrate retained jobs by re-attaching the payload — minting reads only the
  string fields, only execution needs the payload). **Deliberately `Any`, not a generic**: the
  parameter would infect all six seam types while the engine never reads it, and Kotlin generics
  erase to `id` in the exported ObjC header — Swift gains nothing; writer and reader of `data`
  are the same platform adapter, so the cast risk is contained. Note for TypeScript instincts:
  Kotlin's `Any` is the `unknown`-equivalent (only `equals`/`hashCode`/`toString`; every use
  requires explicit narrowing), NOT the checking-off `any` — Kotlin has no such escape hatch on
  JVM/Native. No `Asset`, no fan-out, no
  metadata merge — those are the later asset layer's, above this seam (docs/design.md §2.2/§8).
  The filename layout (iOS: `<cloudId>-<kind>.<ext>`) is composed by the caller.
- **No key type in the sync domain**: the filename is pure identity (plain string); encoding and
  placement are transport concerns owned by each provider (URL path with percent-encoding +
  `photos/` prefix for S3; possibly a header with different escaping elsewhere). The provider
  contract — filename→destination **deterministic and injective** — is where idempotency lives;
  encoding tests belong to provider-impl slices, not this one.
- **Provider takes the resource**: `provide(resource: Resource): UploadRequest` — reads
  `filename`/`contentType`/`metadata` (never `data`), returns the complete request carrying the
  same resource instance. No chicken-and-egg (the request contains the resource, and the provider
  is the one constructing it).
- **Plain suspend, no Flow**: one event maps to exactly one job, so `handle` is
  `suspend fun handle(event: SyncEvent): UploadJob`. Streaming/backpressure were fan-out
  properties; they return with the asset layer, above this seam. No catch blocks anywhere in the
  engine (rethrow contract).
- **No engine interface**: `SyncEngine` is a concrete class (docs/design.md decision — interfaces
  only at platform seams with multiple impls; the engine is the single shared impl and is never
  faked in tests).
- **Tests in `commonTest`** with `kotlinx-coroutines-test` (`runTest`), using a recording fake
  `UploadRequestProvider` (invocation log + scriptable results/throws) defined in test sources only.
  Concurrency scenario uses two concurrent `handle` calls in `runTest`.
- **KDoc carries the contracts** that types can't: rethrow behavior, re-handle safety,
  concurrent-`provide` requirement on providers, the deterministic-and-injective
  filename→destination contract, the provider's obligation to carry the same resource instance on
  the returned request (and never read `data`), and the platform retention rule ("produce the
  newest `UploadJob` on demand"; payload re-attached at rehydration).

## Risks / Trade-offs

- **[Vocabulary freezes early]** Later slices (console, iOS adapter) may want shape changes →
  mitigated by the iOS paper dry-run already performed against this exact vocabulary
  (conversation 2026-06-11; findings folded into docs/design.md §3.3 — within-batch progress
  cursor, retention-as-reproducibility, job idempotency clause).
- **[Retry-forever has no terminal state]** A permanently broken resource churns forever →
  accepted for v1 (docs/design.md §2.2 accepted costs); an attempt-budget policy is a pure engine
  change later, no seam impact.
- **[No consumer in this slice]** The engine lands without a driver → mitigated by the spec
  scenarios doubling as the consumer contract, and slice ③ following immediately.

## Open Questions

- None blocking. Deferred items are tracked in docs/design.md §8 (status-track details, iOS
  verification checklist).
