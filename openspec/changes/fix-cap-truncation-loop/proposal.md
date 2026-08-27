## Why

The upload cycle's only source of work is a library walk, and it publishes nothing unless that walk
is fully drained into jobs. A device with more than four outstanding resources therefore never
completes a cycle: it never publishes its device manifest, never notifies the event, never advances
its discovery cursor, and re-walks its entire photo library once per four uploads — forever. Its
photos upload successfully and stay invisible to every other member.

Measured (SNAPSYNC-16, build 0.3(605), iPhone11,2 / iOS 18.7.9, event "Triglav", 2 h 05 m):
**26 cycles, `PROCESSING` × 26, `COMPLETED` × 0.** 65 uploads completed, 53 photos placed in the
event album — and **zero** `PUT /events/<id>/devices/<id>`. The event union did not learn about a
single one of that afternoon's photos while the app was open and working. The device eventually
caught up and published all of them, so nothing was lost: the failure is an unbounded, silent
**lag that grows with how much you contribute**, ending in a lump once you stop.

The same defect has a second face that loses photos outright. Since `fix-lost-upload-acks`, the
app-driven tier's `drainTerminals()` returns an empty list and records `FAILED` from the delegate,
compensating with *"the engine re-uploads a `FAILED` key from a later discovery"*. That discovery
only sees the key on a **full** enumeration — so on a device whose cursor is settled (i.e. one that
is caught up), a single failed upload is never retried until a token expiry, rejoin, or reset. It
does not bite today only because a busy device's cursor is never settled: **the bug being fixed here
is what masks it.**

## What Changes

- **The ledger becomes the source of upload work.** A new `LedgerState.DISCOVERED` records every
  resource the walk admitted and the engine judged to be new work, written before any job is
  created. The producer enqueues from the ledger — `DISCOVERED` and `FAILED` rows alike — instead of
  from the walk, so a completion-triggered top-up needs no library read at all.
- **The discovery cursor advances on a different condition.** Today: *every job was created*.
  After: *every fact the walk produced is durable* — the `DISCOVERED` rows, the removal marks, and
  the bare-row capture-date backfill. This is the dual of an invariant the codebase already honours
  in the other direction (destroy a row behind the cursor, clear the cursor).
  **BREAKING (spec):** `ios-photokit-upload`'s requirement *"Token does not advance on a cap-truncated
  cycle"* is **replaced**, not amended.
- **A cap-truncated cycle publishes.** The device manifest, the enumeration audit line, and (under
  the new trigger below) the completion notify all move above the early return. The event album
  placement already ran there.
- **The notify's trigger changes** from *drained cycle with ≥1 completion* to *the manifest
  projection actually changed*. The per-cycle completion counter is consumed by truncated cycles
  that cannot announce it, so a drained cycle can find nothing left to notify about.
- **A deferred re-join reconciliation settles with the platform.** Today a non-contributing
  membership drains returned jobs and a deferred reconcile does not — an asymmetry whose measured
  consequence (`PHPhotosError 50008`: the OS discards outstanding jobs and defers the extension
  ~300 s) is documented on the branch that was fixed and absent from the one that was not.
- **Bare-row manifest-detail backfill no longer stops at the truncation point.** It becomes a
  precondition of the cursor change: a capture date lives only in PhotoKit, and a bare row that the
  cursor has passed stays out of every manifest projection with no error anywhere.
- **`UploadCycle.run()` is restructured into four stages** — `settle` · `decide` · `update` ·
  `publish` — where `publish()` is the only producer of a `CycleResult`, so no path can return
  without publishing. Behaviour-preserving in itself; it is what makes the changes above expressible
  as one exhaustive `when` over the cycle's outcome rather than as five decisions spread across two
  early returns.

- **A `createJob` that fails no longer loses the resource.** Found during implementation, not
  planned: `create_failure_records_no_requested_and_does_not_cap` asserted that a failed create leaves
  **no row at all**. With the walk recording before it acts, the row rests `DISCOVERED`, so the next
  cycle retries it from the ledger. Previously the resource was found again only by a walk that
  re-derived it — which an incremental walk does not do for an asset that has not changed. It is the
  same defect as the never-retried `FAILED` row, reached through a different door, and it is fixed by
  the same mechanism.
- **Drift correction, riding along.** `sync-ledger`'s storage-seam requirement still lists
  `deleteByAssetId` and `retainAssets`, both removed when retention stopped being driven by the
  selection policy, and omits the operations the seam has gained since. That requirement has to be
  restated here anyway to add the new state to its `LedgerEntry` enumeration, and reproducing a
  known-false operation list would re-bless it — so the list is corrected in the same edit. No
  behaviour changes; this is the spec catching up to `LedgerStore`.

Explicitly **not** changed: `IosUrlSessionUploadPlatform.cap` stays at 4. Once the ledger is the
work source it goes back to bounding concurrent transfers and staged temp-file disk, which is what a
concurrency cap should bound.

## Capabilities

### New Capabilities

None. Every change modifies the requirements of an existing capability.

### Modified Capabilities

- `sync-ledger`: adds the `DISCOVERED` state and the second state classification (`needsJob`)
  alongside `isDone`; adds the writer verb and the state-scoped read that make the ledger the
  upload work source; widens the seam's purpose from a record of uploads to a record of uploads and
  outstanding work.
- `ios-url-session-upload`: the producer tops up from the ledger rather than from a walk; a `FAILED`
  key is re-enqueued without a full enumeration; adds the id-scoped resolve of ledger keys to
  uploadable resources.
- `ios-photokit-upload`: **replaces** "Token does not advance on a cap-truncated cycle" with the
  durable-facts condition; amends "Cap-aware creation and tri-state processing result" so stopping
  job creation no longer implies stopping everything else.
- `device-manifest`: the manifest is published on a cap-truncated cycle; the bare-row backfill is
  not gated on the pass draining.
- `upload-completion-notify`: the notify fires when the projection changed, replacing the
  drained-plus-completion gate.
- `upload-lifecycle`: a deferred re-join reconciliation still settles returned jobs with the
  platform; the cycle's publication is stated over its outcome rather than over its exit point.
- `diagnostic-logging`: the `enumeration:` audit line is emitted by a cap-truncated cycle and states
  the truncation, so the remaining backlog is readable from a device log.
- `harness-world-model`: the world's fake transfer answers the resolve verb, and its job-limit lever
  exercises a truncated cycle that publishes.

## Impact

**Code.** `:domain` `model/` (`LedgerState`, the two classifications), `ports/` (`LedgerStore`,
`BackgroundTransfer`), `feature/upload` (the restructure and every delta above);
`:adapter:generic:app` (`Ledger.sq`, `LedgerWriter`); `:adapter:generic:fake`
(`InMemoryLedgerStore`); `:adapter:ios:ext-safe` and `:adapter:ios:app-only` (the resolve verb on
both tiers); `:test:world` (`LedgerStoreContract`, the levers); `:test:integration`.

`:app:*` is **untouched** — `run()` still returns `CycleResult`, so the pump, the extension root's
`processRawValue()`, and the Swift principal class are unaffected.

**Storage.** No schema migration: `state` is a SQLDelight typed enum column (`TEXT AS LedgerState`),
so a fifth value is a value. The three `.sq` predicates already bind `:doneStates` from Kotlin, so
the new state's classification reaches all of them from one `when`.

**Cost.** A first walk on a large library writes one `DISCOVERED` row per outstanding resource; the
state-scoped read needs a bound. In exchange, a completion-triggered top-up costs a targeted PhotoKit
resolve (~20 ms for four resources, measured against the same log's 0.3 s for 71) instead of a full
enumeration (6.1–7.2 s for 224).
