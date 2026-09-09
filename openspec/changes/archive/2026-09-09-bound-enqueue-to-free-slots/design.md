## Context

Since `fix-cap-truncation-loop` the upload cycle enqueues from the ledger rather than from the walk's
return value. `UploadCycle.enqueue` reads `ledger.rowsNeedingJob(enqueueBatchSize)`, resolves that
whole set to uploadable resources through `BackgroundTransfer.resourcesFor`, and then offers them to
`createJob` one at a time until the platform refuses.

`enqueueBatchSize` is 16. The app-driven tier's `cap` is 4. The two are unrelated numbers in
different modules, and the constant's KDoc prices the mismatch as *"asking for more than the platform
will accept costs a resolve, not a stage"* — with the resolve treated as pure latency.

**A resolve is not pure latency.** `IosDiscovery.resourcesFor` is `withContext(Dispatchers.Default)`
around `PHAsset.fetchAssetsWithLocalIdentifiers` plus one `PHAssetResource.assetResourcesForAsset`
per asset. Both are synchronous XPC into `photolibraryd`; there is no async variant. Only the *bytes*
have one (`writeDataForAssetResource`, which staging already uses). ⏰ Expires if PhotoKit publishes
an async resource-handle API.

**Measured on device** (iPhone12,8 / iOS 26.6, foreground, idle, 2026-09-09, build 0.4(1)):

| call | keys | duration |
|---|---|---|
| `platform.resourcesFor` | 16 | **54 ms** |
| `platform.resourcesFor` | 3 | 19 ms |
| `platform.resourcesFor` | 2 | 16 ms |
| `platform.resourcesFor` | 1 | 11 ms |

The 16-key case is the **backlogged** device — the one this change is for. Once work is flowing the
ledger holds one to three rows needing a job and the batch never approaches its bound, so the saving
is zero in steady state and lands entirely on the member who is behind.

For scale, the same cycle's walk on the same device: 7–10 ms when the change feed reports nothing,
58 ms at 40 candidates, 388 ms at 1054. In a backlog drain — where each iteration is triggered by a
completion and the feed reports nothing new — **the resolve is roughly 84% of the cycle's
uninterruptible time**, and it is resolving sixteen rows to create at most four.

## Goals / Non-Goals

**Goals:**

- No row is resolved for a job the platform will not accept.
- A full platform is reported as backpressure, never as absence of work.
- The bound cannot drift from the cap it is meant to track.
- The OS-driven tier is unaffected in behaviour.

**Non-Goals:**

- **Fixing the suspended-discovery-walk freeze.** This shrinks the window a suspension can freeze,
  because that window is uninterruptible PhotoKit time. It does not change the mechanism, and the
  field rate on a post-fix build is unmeasured. See Open Questions.
- **Making any one resolve or walk faster.** Neither call changes.
- **Moving discovery out of the cycle.** A discovery lane would remove the *smaller* of the two
  uninterruptible spans and owes a new answer to what `CycleResult` asserts. Separate question.
- **Skipping the walk on continuation triggers.** Measured at ~13% of a backlog cycle's blocking
  time, against threading trigger identity through a pump that deliberately erases it.
- **Explaining the 200× field-vs-idle gap** in walk cost (`fix-cap-truncation-loop`, "The walk's cost,
  measured"). Measured since: the full→incremental branch accounts for 2.7×, not 200×. The remainder
  is situational — device generation, iOS version, contention with a concurrent import flood,
  background QoS — and belongs with the walk's behaviour under load.
- **Changing `cap`.** It stays 4 and stays a concurrency bound.

## Decisions

### D1 — The platform reports its capacity; the cycle does not hold a second number

Alternatives: (a) a per-tier `enqueueBatchSize` supplied through `UploadPorts`; (b) resolving lazily,
one row at a time, until the platform refuses.

**(a) recreates the coupling it is meant to remove.** `UploadPorts` already carries two per-tier
values (`selectionScope`, `albumExcludedAssetIds`), so the precedent exists and the change would be
smaller. But the app tier's batch would have to be *its cap*, stated in a different module from the
cap itself, with nothing to catch a drift — change `cap` to 8 and the batch silently under-fills. A
reported free-slot count is derived from the cap in the same class and cannot disagree with it. It is
also strictly better than any constant when slots are partly full: with three transfers live, a batch
of four still resolves four to create one.

**(b) is worse where it matters.** The cost is one identifier fetch plus one resource read per asset,
so a batch of N is `1+N` round-trips and N lazy resolutions are `2N`:

| free slots | batch of 4 | lazy per row | reported capacity |
|---|---|---|---|
| 4 | 5 | 8 | 5 |
| 1 | 5 | 4 | 2 |
| 0 | 5 | 2 | **0** |

Lazy wins only when slots are scarce and loses in the post-wake case that dominates a drain.
Reporting capacity dominates every column, including the one no option but this costs nothing on: a
cycle with no free slots does no platform read at all.

### D2 — Zero free slots is truncation, not "no work"

Found by tracing the naive form, and it is a stall rather than a slowdown:

```
rowsNeedingJob(0) → empty → Enqueued(truncated = false) → CycleOutcome.Drained → COMPLETED
  → shouldSchedule(COMPLETED, scheduleOnProcessing = false, alwaysScheduleNext = false) → arms nothing
```

…while `DISCOVERED` rows sit in the ledger. A completion-triggered cycle would publish a drained cycle
over a non-empty backlog and re-arm nothing, which is the failure class `bound-background-session-handlers`
exists to prevent. The empty read must therefore be distinguished from "the ledger is empty" and
mapped to the truncated outcome the cycle already has, which publishes `PROCESSING`. This is not
defensive coding: it is a state the current code cannot reach (the batch is always ≥1) and that this
change introduces.

### D3 — The OS-driven tier reports the absence of a number, not a large one

Its limit is the OS's durable job queue, which it cannot read. A sentinel like `Int.MAX_VALUE` would
be a guess dressed as a fact, and the fixed batch already exists to bound an unbounded read. `null`
says exactly what is true.

This is the seam's established shape rather than a new asymmetry: the app-driven adapter already
answers `fetchRetryJobs` with a constant `emptyList()` ("no OS-sponsored free retry on this
platform") and `drainTerminals` likewise. A member one tier answers trivially is how this port
already expresses "the mechanism has nothing to give here".

### D4 — `enqueueBatchSize` survives, in its documented role

It is not replaced. Its KDoc states its job — *"a first walk on a large library records a row per
outstanding resource, and an unbounded read would try to resolve them all"* — and that job is exactly
what a platform reporting `null` needs. It stops being a compromise between two tiers and becomes a
genuine safety bound for the tier that has no number.

### D5 — The report is advisory, and both directions of staleness are safe

Capacity is read, then transfers complete or start, so the number is stale by the time it is used.
Both directions are already handled by existing behaviour:

- **stale low** — fewer rows resolved than could be; the remainder stays `DISCOVERED` and the next
  cycle reads it from the ledger. This is precisely what `fix-cap-truncation-loop` made safe.
- **stale high** — the platform refuses with its own limit signal, exactly as an unbounded read is
  refused today, and the cycle reports truncated.

There is no third outcome, so nothing needs a lock, a generation counter, or an atomic read.

## Risks / Trade-offs

- **[The saving is zero in steady state]** → Accepted, and stated in the proposal rather than hidden:
  the batch only reaches its bound on a backlogged device. That is the population this is for, and it
  costs nothing anywhere else.
- **[A new member on a shared port must be implemented by four implementers]** → Two adapters, the
  fake, and the world. The compiler names every one; that is the point of the shared seam.
- **[D2's stall is introduced by this change]** → It is covered by a scenario in the delta spec and is
  the one behaviour here that must be tested rather than reasoned about, because nothing in the
  compiler distinguishes an empty read from an empty ledger.
- **[Free capacity is derived from a cap the spec says does not bind across process death]** →
  Clamped at zero, with a scenario. A relaunched session holding more live tasks than the cap is a
  stated condition of the existing requirement, not a hypothetical.
- **[Measurements are one device, one OS point release]** → iPhone12,8 / iOS 26.6. The *ordering* they
  establish (16 keys costs several times what 1–3 keys cost, and dominates a backlog cycle) is robust
  to the absolute numbers moving; the decision does not rest on 54 ms being the right figure.
  ⏰ Re-measure at the next iOS major.

## Migration Plan

No schema migration, no wire change, no stored state. The new port member is read per enqueue pass and
persisted nowhere.

**Rollback** is free: a build without the member resolves the fixed batch exactly as today, and the
ledger contains nothing this change writes.

## Open Questions

- **The field rate of the suspended-drain freeze on a post-fix build is unmeasured**, and this change
  does not measure it. One occurrence was observed on the bench (2026-09-09, nine minutes, the app
  unresponsive and its control channel accepting connections without answering), but on a foreground
  app idling into suspension rather than a background wake overrunning its 20 s receipt. Whether the
  window this change shrinks is one that matters in the field remains open.
- **A slowest-PhotoKit-span field in the diagnostic dump's `state` context** would answer that
  question over time, needs no threshold, and is independent of everything here. Deliberately not
  bundled: it is `diagnostic-logging`, it would have been useful before this change, and a `Warn`-level
  detector — the first shape considered — was rejected because `Warn` rides to the crash reporter as a
  breadcrumb rather than an event, and the span's duration is already logged on every call by
  `Logger.invocation`.

## Addendum — what actually shipped

Between this change's approval and its merge, `main` reshaped `enqueue` (`a narrowing must reach the
bytes, not just the manifest`, and the reconciler move beside it): `LedgerStore.rowsNeedingJob()` lost
its bound entirely, and the cycle now reads unbounded, filters by `admittedAssetIds`, and bounds the
**admitted slice** with `.take(enqueueBatchSize)`.

That invalidates D1's mechanism as written here. Bounding the *read* — which this document proposed —
is not merely incompatible with the new shape, it is **wrong**, and `main` states why: rows come back
in a stable key order, so excluded rows sorting ahead of admitted ones would fill the slice on every
cycle and the admitted work further down would never be reached. Bounding the read starves.

The change's claim survives intact — the platform is still the only party that knows how many
transfers it will take, and the code still said "asking for more than it will accept costs a resolve,
never a write" — so `remainingCapacity()` is unchanged and simply moved: it bounds the `take` of the
admitted rows rather than the ledger read.

One thing got **better** for it. D2's saturated-read rule was a heuristic here (`rows.size >= bound`,
over-reporting by one cycle when the bound was met exactly). With an unbounded read the admitted set is
the whole remaining backlog, so truncation is now exact: `rows.size < eligible.size`.

The requirement text in `openspec/specs/ios-url-session-upload/spec.md` was corrected at merge time to
describe the slice rather than the read. This addendum is left rather than the body edited: the body
records what was decided, and this records what the decision met on its way in.
