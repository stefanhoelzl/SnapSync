# Design: sync-engine-ledger

## Context

Slice ① shipped a stateless `SyncEngine` (event in → one `UploadJob` out) on the premise that the
platform's own guarantees (durable system jobs, change-token discovery) carry all bookkeeping. Two
research findings (2026-06-12, Apple docs/WWDC/forums) broke that premise:

1. **Change-token expiry is routine.** `persistentChangeTokenExpired` retention is undocumented
   (illustrated at ~days); Apple's remedy is full library re-enumeration. With no memory of what is
   backed up, every expiry re-uploads ~50k assets (~150 GB).
2. **Apple prescribes per-key idempotent bookkeeping.** The upload-job API's documented model is
   write-then-acknowledge ("Before acknowledging a job, update your app's tracking system…"; after
   acknowledge "its record is no longer available"), re-presentation until acknowledged, and the
   sample code filters discovery "against the uploaded set". Exactly-once across the file system
   and PhotoKit's job queue is impossible (no shared transaction; confirmed by Apple's own IPC
   guidance) — reports are at-least-once, the consumer must dedupe per key.

The old architecture scattered the resulting per-key state across three structures (status fold
map, platform discovery ledger, status-event inbox). This change consolidates them into one
engine-owned SQL ledger and makes events observations ("trust events as observations, never as
bookkeeping"). Status projection, console, and platform adapters build on it in later proposals.

No external consumers of engine types exist yet (verified by import scan), so the breaking
amendments are free today.

## Goals / Non-Goals

**Goals:**

- One durable per-key memory, owned and written exclusively by the engine, shared across platforms.
- Decisions, not jobs: the engine can answer "nothing to do" (`AlreadyUploaded`), making
  re-enumeration floods harmless in shared, desktop-testable code.
- Idempotent per-key recording that absorbs at-least-once platform reports (duplicate
  completions/failures/discoveries) without drift.
- Loss-free skip rule: never skip work the ledger cannot prove done.
- Codify the single-writer constraint in types (writer constructible only where intended).

**Non-Goals:**

- Status projection (`ReadonlySyncLedger` aggregates, `active`, timestamps/`lastFinishedAt`,
  `:domain:status` split) — next proposal.
- Engine console, `DumbHttpRequestProvider`, any platform adapter or driver.
- Concurrency support in `handle()` (deliberately dropped — see Decisions).
- Native (iOS) SQLDelight driver wiring — the iOS slice pays for it.

## Decisions

### D1 — The engine owns a ledger; events are observations

`SyncEngine(provider, ledger)` consults and updates a per-key store on every event. Platforms stop
filtering ("is this new?") and report what they see; the engine decides. *Alternative — platform-side
bookkeeping (status quo)*: requires a discovery ledger per platform anyway (expiry), duplicates the
per-key store three times, and leaves correctness per-platform instead of shared.

### D2 — `SyncDecision` with four self-documenting arms

```kotlin
sealed interface SyncDecision {
    sealed interface Work : SyncDecision { val job: UploadJob }
    class Upload(override val job: UploadJob) : Work      // not (provably) uploaded yet
    class ReUpload(override val job: UploadJob) : Work    // completed, but version changed
    class Retry(override val job: UploadJob) : Work       // answer to UploadFailed, attempt + 1
    data object AlreadyUploaded : SyncDecision            // completed + same version; no work
}
```

Platforms switch on two cases (`Work` → execute job; `AlreadyUploaded` → continue); logs and the
future harness journal get full provenance. *Alternatives*: nullable `UploadJob` (no provenance),
batch `handle(List)` (mints decisions for work the platform may never execute past its job limit —
widens the hope gap; backpressure wants per-event pacing; a batch convenience can be added later).

### D3 — Decision rules: skip only on proof, never on hope

| Ledger state for key | `ResourceChanged` answer |
|---|---|
| absent / requested / failed | `Upload` |
| completed + same `version` | `AlreadyUploaded` |
| completed + different `version` | `ReUpload` |

"Requested" rows are hopes: the engine cannot verify its answer was executed (engine-write vs
platform-job-creation is a two-generals pair). Skipping on a hope risks silent permanent loss —
the one unforgivable direction; duplicate jobs are merely absorbed (idempotent destinations).
`UploadFailed` → `Retry` (attempt + 1, fresh-minted request, retry-forever, all error kinds), and
provider failures still rethrow (event counts as unprocessed; re-handling safe via idempotence).

### D4 — `UploadCompleted` through `handle()`, answered with `AlreadyUploaded`

Completions are reported at the platform's acknowledge edge, *before* `acknowledge()`
(Apple-prescribed write-then-ack: the duplicate side of the crash window, because the loss side is
unrecoverable). One entry point, no CQS sibling: after recording, "already uploaded" is literally
the truth, so no fifth arm is needed.

### D5 — `Resource.version: String`, equality-only

The platform supplies content-identity proof (iOS: asset `modificationDate`; tests/console: any
string). The engine compares equality, never parses. *Alternatives*: a well-known metadata key
(stringly convention, engine reads a map it promised was opaque, value leaks into headers);
whole-metadata equality (any cosmetic change re-uploads all bytes). Accepted cost of the chosen
shape: metadata-only changes leave stale `x-amz-meta-*` headers remotely, forever — milder than
the byte-churn it replaces.

### D6 — Ledger classes: storage seam below, capability split above

```kotlin
interface LedgerBackend {                      // the ONLY thing impls provide
    suspend fun get(key: String): LedgerEntry?
    suspend fun put(entry: LedgerEntry)        // dumb single-row upsert
}
open class LedgerReader(backend) { suspend fun entry(key): LedgerEntry? }
class LedgerWriter(backend) : LedgerReader { recordRequested / recordCompleted / recordFailed }
class LedgerEntry(key, state /* REQUESTED|COMPLETED|FAILED */, attempt, version)
```

Record semantics are concrete shared code, written once; backends are dumb row stores (SQLDelight
≈ a few lines; test fake ≈ a `MutableMap`). Narrowing = upcasting (`LedgerWriter` *is-a*
`LedgerReader`; type safety against accidents, not adversarial casts). Single-writer is codified
by construction: exactly one composition root per platform ever constructs a `LedgerWriter` (iOS:
the extension's; the app holds `LedgerReader` over a read-only connection — next proposals). The
engine depends on the concrete `LedgerWriter` (no interface above it: tests vary the backend, a
second implementation does not exist). *Alternative rejected*: `SyncLedger`/`ReadonlySyncLedger`
interface pair — record semantics would be re-implemented per impl, test fakes would fake the
semantics instead of the storage.

### D7 — Sequential `handle()`; no transactions; rules in Kotlin

The old "may be called concurrently" was free under statelessness; with a ledger it would cost
transactions or SQL-encoded precedence — and no driver demands it (extension `processJobs()` and
the console are sequential loops). Contract: one `handle()` in flight per engine; concurrency is
the caller's responsibility. Consequences: `LedgerBackend` needs no transaction API; decision and
precedence rules live as plain unit-testable Kotlin (read → decide → write); each `put` is a
single atomic statement. If a concurrent driver ever appears, that slice reintroduces the
guarantee. Design note for a possible future second writer (app-side foreground acknowledging):
single-statement upserts shrink lock windows to microseconds, making two-writer WAL viable —
but cross-process read-decide-write races return (self-healing but churny); re-examine
`ON CONFLICT` precedence then.

### D8 — Schema: minimal, no timestamps

`key TEXT PRIMARY KEY, state TEXT, attempt INTEGER, version TEXT` — nothing else. No consumer of
time exists in this slice (decision rules read state/attempt/version only; `lastFinishedAt` is the
status proposal's requirement and adds its column then — a free migration while the DB ships only
to tests). One `LedgerEntry` type serves backend and reader until shapes diverge.

### D9 — SQLDelight as the SQL backend

Kotlin Multiplatform with JVM and Native drivers; typed queries generated from `.sq`. This slice
ships the schema, the SQLDelight `LedgerBackend`, and JVM/sqlite wiring for tests; the Native
driver and App-Group path are the iOS slice's. *Alternative — okio + kotlinx.serialization file
store (the old plan)*: rewrites O(state) per write at 200k keys, no concurrent cross-process
reader, no aggregates for the status proposal.

### D10 — Monolithic engine; middleware pipeline considered and deferred

A middleware decomposition was explored (2026-06-12): decision pipe, null-chain, interceptor
chain, and a typed maker/transformer/observer pipeline — each could host the ledger logic as
`LedgerReader`-only filter + `LedgerWriter`-only recorder, plus future concerns (console journal
tap, attempt-budget policy, logging). Deferred as overthinking for two call sites: the engine's
read → decide → mint → record fits in one small class today. If a real second consumer of the
decision flow appears (the slice-③ journal is the likely first), revisit — the typed pipeline
(makers first-non-null with the minting core as terminal maker, then transformers, then
observers, all receiving the event as context) was the shape that preserved every invariant
(no mint on skip, no trace on provider failure, `ReUpload` provenance).

## Risks / Trade-offs

- **Stale remote metadata headers** (D5) → accepted, documented; a header-refresh job kind is
  possible future work if it ever matters.
- **Requested-hope rule can storm duplicate jobs** if a platform re-submits in-flight work in its
  common path → platform contract keeps small lossy-tolerant bookkeeping (`{token, residue,
  deferred}`); lost residue costs one change-record of absorbed duplicates. Recorded in design.md
  for the iOS slice.
- **SQLite in an App Group (later slices)** → single writer + read-only app connection is the
  Apple-documented-safe WAL configuration; the file-protection class and cross-process read
  behavior are §8 verification items, not this slice's risk.
- **Engine now depends on storage** → contained: the dependency is `LedgerWriter` over a
  two-method backend; unit tests run on a `MutableMap`.
- **Breaking changes to a shipped slice** → zero external consumers verified; the amendment is
  cheapest now and the spec delta records it.

## Open Questions

- None blocking this slice. Deferred by scope: reader aggregates/timestamps (status proposal),
  Native driver + App-Group wiring and the residue/deferred bookkeeping details (iOS slice),
  batch `handle` convenience (if a flood is ever measured slow).
