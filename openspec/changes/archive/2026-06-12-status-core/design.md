# Design — status-core

## Context

The sync-engine-ledger change (archived 2026-06-12) gave the engine a SQL per-key ledger as its
only state. Status was deliberately split out as the next proposal. What exists today:

- `SyncStatus`/`SyncState`/`SyncStatusSource` live in `:domain:sync`; presentation `api()`s the
  whole engine module to reach them — engine types (events, decisions, jobs) leak onto
  presentation's compile classpath.
- `SyncStatus` documents its counts as "the most recent pass" — a notion the ledgered engine
  erased (there are no passes, only the ledger's lifetime truth).
- The ledger seam is `LedgerBackend { get, put }` + `LedgerReader.entry(key)` +
  `LedgerWriter` records — per-key only, no aggregates, no timestamps, no change signal.
- The harness panel forges `SyncStatus` presets through the seam; nothing real produces one.

Interview decisions (2026-06-12, full walk recorded in conversation; supersedes nothing — this
is additive to the ledgered-engine design):

## Goals / Non-Goals

**Goals:**

- A real, shared `SyncStatusSource`: read-only projection of the ledger, usable unchanged on
  every platform.
- Plug the presentation leak via a `:domain:status` module; presentation never sees engine types.
- Land the ledger's `updatedAt` column while no persisted database exists (free migration).
- `active` = operational state from permission, computed in exactly one shared place.

**Non-Goals:**

- Wiring the real source into the harness (slice ⑥; the panel keeps its preset stand-in).
- Staleness detection (needs platform token bookkeeping that doesn't exist yet — iOS slice).
- Cross-process ding delivery (Darwin observer feeding an app-side backend — iOS slice; this
  slice only fixes the seam it must fit: `changes` is whatever flow the backend produces).
- Estimation (`estimatedRemaining` stays null from the real source) and any new screen states.

## Decisions

### D1 — Module topology: status depends on engine, implementation-scoped

`:domain:status` holds the seam types and `LedgerSyncStatusSource`, with
`implementation(":domain:engine")` and `implementation(":domain:permission")`. Presentation
`api()`s `:domain:status` and `:domain:permission` only.

- *Why not the inverse* (status = pure types, source lives in the engine module): the engine
  module would know about a projection built over it — the ledger doesn't know it has readers.
- *Why implementation scope*: the whole point of the split is keeping engine types off
  presentation's classpath; `api` would re-leak them transitively. Only composition roots
  construct the source, and they see all modules anyway.

### D2 — Rename `:domain:sync` → `:domain:engine`

The delivery matrix's two tracks (engine / status) become the two domain module names. The
rename goes all the way down: directory, package (`app.snapsync.engine`), SQLDelight package
(`app.snapsync.engine.db`). Type names keep their `Sync` prefixes (`SyncEngine.handle()` reads
better than `Engine.handle()`).

### D3 — Aggregates live on the backend seam

`LedgerBackend` gains `aggregates(): LedgerAggregates` (pending = non-`COMPLETED` keys,
completed = `COMPLETED` keys, newestCompletionAt = max `updatedAt` over `COMPLETED` rows) —
one method, one SQL round-trip, so the counts are mutually consistent (no read skew).

- *Why not status-owned SQL against the same file*: a second module coupled to the table schema,
  and the in-memory backend couldn't serve status tests.
- *Why not `all(): List<LedgerEntry>` + Kotlin counting*: pulls every row into memory at
  30k-asset scale for something SQL counts for free.

`LedgerAggregates` gets value equality (the watcher dedupes on it, D6).

### D4 — `updatedAt` stamped by `LedgerWriter`, typed columns

One column, `updatedAt` epoch millis, stamped on **every** record operation (not a nullable
completion-only column — same query cost, no null asymmetry, free debugging value).
`LedgerWriter(backend, clock: Clock = Clock.System)` is the single stamping point: the engine
stays clock-free (decided last slice, preserved), backends stay stores-verbatim, contract tests
inject a fixed clock.

The `.sq` schema uses SQLDelight typed columns (`state TEXT AS LedgerState`,
`updatedAt INTEGER AS Instant`) with the built-in `EnumColumnAdapter` and a 4-line Instant
adapter — deleting the hand-written conversions instead of adding more. Adapter wiring hides in
one factory function next to the backend; construction sites never see it.

Consequence, accepted: the idempotence contract rewords to "duplicate record operations converge
on state/attempt/version" — the timestamp moves forward on replay. A duplicate `UploadCompleted`
nudging `lastFinishedAt` seconds later is invisible; timestamp-preserving writes would be
read-before-write cleverness in a deliberately dumb upsert.

### D5 — The ledger signals its own changes

`LedgerBackend` gains `changes: Flow<Unit>` — a ding after every successful put, no payload, no
interpretation (still a dumb store; it just dings). A ding means "re-read the truth", which makes
loss semantics honest by construction: conflation, duplicate puts, and missed-while-busy signals
are all safe because every recompute queries current state.

- *Why not an injected refresh flow on the status source*: the thing that changes should notify
  about its own changes; with the ding on the backend, slice ⑥ has no trigger wiring at all
  (console writes → backend dings → status recomputes).
- *Why not SQLDelight query listeners*: in-process only (useless for iOS's cross-process reader),
  and couples the source to SQLDelight instead of the seam.
- iOS fit: the app-side read-only backend will feed its `changes` from the Darwin notification —
  "where dings come from" is each backend's implementation detail; seam, watcher, and source
  never know.

### D6 — Three ledger types: Reader, Writer, Watcher

```
LedgerReader(backend)            entry(key)                      engine-facing, per-key
LedgerWriter : LedgerReader      recordRequested/Completed/Failed engine-facing, sole writer
LedgerWatcher(backend)           aggregates: Flow<LedgerAggregates> status-facing, stream
```

The watcher is a cold flow: emits current aggregates on collect, then re-queries on every ding
(`changes.conflate()`), deduped with `distinctUntilChanged()`. Each consumer gets exactly its
capability — status cannot read per-key entries, the engine-facing reader never sees aggregates
or dings. The re-query-on-ding plumbing lives in one reusable place instead of inside the source.

### D7 — `SyncStatus`: same shape, rewritten contract; FAILED vertical deleted

Fields are unchanged (`pending/completed/failed/active/estimatedRemaining/lastFinishedAt`);
their meanings rewrite: counts are lifetime ledger aggregates; `failed` is structurally 0 from
the real source (retry-forever never gives up a key — a `FAILED` ledger row is transient, and
even a crash-stranded one counts as pending and is retried on next encounter; the field fills
itself when v2 adds an attempt budget); `estimatedRemaining` is null from the real source;
`lastFinishedAt` = newest completion.

Classification (decision-table order):

```
!active                => SUSPENDED      machinery off — nothing else matters
pending > 0            => IN_PROGRESS
lastFinishedAt == null => NEVER_SYNCED
failed > 0             => INCOMPLETE
_                      => COMPLETE
```

`SyncState.FAILED` is deleted, with its UI state, hero row, harness preset, and scenarios:
under lastFinishedAt-as-newest-completion the old FAILED branch
(`completed == 0 && lastFinishedAt != null`) is self-contradictory — a non-null completion
timestamp implies a completed key. The seam can no longer truthfully tell that state; v2's
attempt budget can reintroduce it with real semantics. `!active` outranking everything means
complete-but-inactive shows SUSPENDED — invisible through real wiring in v1 (the permission gate
covers the hero whenever `active` is false), and the dominant fact regardless. Harness finished
presets flip to `active = true` so they classify as finished.

### D8 — `active` derived inside the source

`LedgerSyncStatusSource` takes `PermissionStatusSource` and mints
`active = permission == GRANTED` per snapshot. The shared rule lives once; every platform gets
it by construction. Permission being consumed twice (gate in the container, `active` in the
source) is accepted: same `StateFlow`, can't disagree, and the gate's precedence hides any
within-frame skew. The alternative — injecting a pre-derived `Flow<Boolean>` — would migrate the
`== GRANTED` rule into every composition root.

### D9 — Source construction: suspend factory, synchronous first value

```kotlin
suspend fun LedgerSyncStatusSource(
    watcher: LedgerWatcher,
    permission: PermissionStatusSource,
    scope: CoroutineScope,
): SyncStatusSource
```

The seam promises `status.value` synchronously, so the factory takes the watcher's first
emission for the initial snapshot before constructing, then keeps combining
`watcher.aggregates × permission.permission` into the `MutableStateFlow`. Minting is total:
`failed = 0`, `estimatedRemaining = null`, `lastFinishedAt = aggregates.newestCompletionAt`.

## Risks / Trade-offs

- [Rename touches everything] The `:domain:sync` → `:domain:engine` rename ripples through every
  import in engine, presentation, harness, and tests → purely mechanical, compiler-verified; do
  it as one isolated task before behavioral changes so diffs stay readable.
- [Timestamp moves on replay] Duplicate completions nudge `lastFinishedAt` forward → accepted;
  duplicates arrive within seconds and "last finished" display granularity is minutes.
- [SUSPENDED unreachable through real wiring] `!active` fires exactly when the gate covers the
  hero → known and accepted since the ledgered pass; preset-only in v1.
- [Cold watcher re-queries per collector] Each collection runs its own initial query → one
  consumer in v1; the spec states the contract ("each collection starts with current truth") so
  a future second consumer doesn't assume sharing.
- [Backend ding is in-process] A second process writing would not ding this process's watcher →
  by design; cross-process delivery is the iOS backend's job (Non-Goal here, seam already fits).

## Migration Plan

No persisted databases exist outside tests — the schema change is a plain edit to `Ledger.sq`,
no SQLDelight migration files, no version bump. Single PR; revert = revert.

## Open Questions

None — every branch was walked in the 2026-06-12 interview (scope, topology, aggregates,
timestamp, classification, active wiring, watcher signaling, testing).
