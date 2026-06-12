# Tasks: sync-engine-ledger

## 1. SQLDelight setup

- [x] 1.1 Add SQLDelight to the version catalog and apply the plugin to `:domain:sync` (runtime in
      commonMain, JVM sqlite driver in jvmTest); verify `./gradlew :domain:sync:build` stays green
- [x] 1.2 Define the ledger schema in a `.sq` file: `key TEXT PRIMARY KEY, state TEXT NOT NULL,
      attempt INTEGER NOT NULL, version TEXT NOT NULL`, with `get`-by-key and single-statement
      upsert queries

## 2. Ledger classes

- [x] 2.1 Add `LedgerEntry` (key, state enum REQUESTED|COMPLETED|FAILED, attempt, version) and the
      `LedgerBackend` interface (`get`/`put`)
- [x] 2.2 Add concrete `LedgerReader(backend)` with `entry(key)` and
      `LedgerWriter(backend) : LedgerReader` with `recordRequested`/`recordCompleted`/`recordFailed`
      (each a self-contained single `put`)
- [x] 2.3 Add the SQLDelight `LedgerBackend` implementation mapping rows ↔ `LedgerEntry`
- [x] 2.4 Add the in-memory test backend (`MutableMap`) in commonTest
- [x] 2.5 Backend-contract tests (round-trip, unconditional overwrite, unknown-key null, record
      idempotence) run against BOTH the in-memory backend and the SQLDelight backend on a JVM
      sqlite driver

## 3. Seam vocabulary amendments

- [x] 3.1 Add `version: String` to `Resource` (KDoc: platform-supplied content-identity proof,
      equality-only)
- [x] 3.2 Add `SyncEvent.UploadCompleted(job)` (KDoc: reported at the acknowledge edge, before
      acknowledging; at-least-once)
- [x] 3.3 Add `SyncDecision` (sealed: `Work` sub-interface with `job`; `Upload`/`ReUpload`/`Retry`
      classes; `AlreadyUploaded` object), with KDoc explaining each arm's provenance

## 4. Engine rewrite

- [x] 4.1 Rewrite `SyncEngine(provider, ledger: LedgerWriter)`: decision table for
      `ResourceChanged` (absent/hope → `Upload`; completed+same version → `AlreadyUploaded`,
      no provider call, ledger untouched; completed+changed → `ReUpload`), recording `REQUESTED`
      after successful minting only
- [x] 4.2 `UploadFailed` → `Retry` (attempt + 1, fresh mint), recording `FAILED` then `REQUESTED`;
      `UploadCompleted` → record `COMPLETED`, answer `AlreadyUploaded`
- [x] 4.3 Update contract KDoc: sequential `handle()` (one call in flight; concurrency is the
      caller's), re-handling safe via idempotent upserts, provider failures rethrow leaving the
      ledger untouched

## 5. Engine tests

- [x] 5.1 Rewrite `SyncEngineTest` around the in-memory ledger: every spec scenario (unknown
      uploads, skip on same version, re-upload on changed version, hope never skips, no minting on
      skip, retry chain with ledger state, provider-failure leaves no trace, completion marks done,
      duplicate completion no-op, completed-then-resubmit skips, version equality-only)
- [x] 5.2 Idempotence/replay test: a recorded event sequence re-handled from any point converges
      to the same ledger state and decisions

## 6. Documentation

- [x] 6.1 Update docs/design.md: §2.2 rewritten (ledgered engine, decision vocabulary, sequential
      contract, single-writer rule with its "app has nothing to report" justification, two-writer
      future note), §2.4/§3.2/§3.3 amended (inbox and platform discovery ledger superseded;
      platform bookkeeping = token/residue/deferred, lossy-tolerant), §7 (+SQLDelight), §8 (add:
      cross-process read-only reads, DB file-protection class; keep job-system in-flight
      enumeration)
- [x] 6.2 Verify full build + tests green (`./gradlew build`)
