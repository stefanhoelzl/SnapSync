## Context

The status screen (`docs/design.md §2.4`) projects the engine's ledger: `pending`/`completed` counted
by photo, `total` from the live gallery. The ledger is written **exclusively** by the iOS upload
extension (single-writer, `docs/design.md §2.2 / §3.2`, sync-ledger). On iOS the system performs the
PUTs and marks each `PHAssetResourceUploadJob` **succeeded** continuously as bytes land, but the
extension's `process()` — the only thing that records `COMPLETED` — is invoked **coarsely and
batched** by the OS. There is no per-completion callback. Result: the screen sits at "X in progress"
while uploads finish invisibly, then jumps to complete when the extension finally runs.

An on-device spike (build #128, iPhone SE2) established the enabling facts:
- The **main app process** can call `PHAssetResourceUploadJob.fetchJobsWithAction(.acknowledge)` and
  see succeeded-but-unacknowledged jobs (observed counts 1→2→4, then 0 once the extension acked).
- The read is **side-effect-free**: jobs accumulated across app reads and were only consumed when the
  extension (the sole acknowledger) acked them. The extension still recorded `COMPLETED`.

So the app already holds the truth it needs; it just isn't projected. This change projects it.

## Goals / Non-Goals

**Goals:**
- Advance the "n of N" count and the "in progress" caption live, and reach COMPLETE, as uploads
  succeed — without waiting for the extension's coarse `process()`.
- Preserve the single-writer ledger invariant exactly: the extension remains the only `LedgerWriter`.
- Keep all merge logic pure and tested on JVM + iOS simulator (`commonTest`); confine the
  device-only PhotoKit read to the untested `:app:ios` shell.
- Degrade to today's behavior when no observations are available (empty observed set ⇒ identity).

**Non-Goals:**
- No multi-writer ledger; no app-side ledger writes.
- No S3/edge LIST endpoint; no new device credentials; no new device network calls.
- No change to discovery, job creation, retry, or upload mechanics.

## Decisions

### D1 — Read-side overlay, not a multi-writer ledger
The app reconciles observations into the **projection**, never into the ledger. Alternatives
considered: (a) make the app a second `LedgerWriter` keyed on a LIST/observation — rejected: forces
cross-process SQLite write safety and races with the extension's live cycle (retry, `retainAssets`,
version re-upload), and breaks the invariant the whole design leans on. The overlay gives the same
UX with zero write-path risk; the extension's later `COMPLETED` write is authoritative and the overlay
simply yields to it.

### D2 — Data source is PhotoKit jobs, not an edge LIST
Observation comes from `fetchJobsWithAction(.acknowledge)` filtered to `succeeded`, mapped to the
ledger key via `destination.URL.lastPathComponent` (the same mapping `IosUploadJobPlatform` uses; the
only field reliably present for every job state). Alternative: a new edge `GET` listing bucket keys —
rejected: needs a new endpoint and is **version-blind** (bucket presence can't tell a stale key from a
pending re-upload), whereas job success is the real per-attempt signal. PhotoKit also needs no
network call and no credentials.

### D3 — Observation lands at the projection layer (`LedgerSyncStatusSource`), not in `LedgerWatcher`
The seam is injected into the status source, beside the existing live, non-ledger signal
`gallery.size`. Alternative: inject an "update provider" into `LedgerWatcher` so downstream is
untouched — rejected: it pushes a platform-observation seam into `:domain:engine` (defined as "no
platform deps / the ledger is the only state") and makes `LedgerAggregates` stop meaning ledger truth.
The codebase already established that live non-ledger observations are merged at the status layer
(`gallery.size`); observed-completions is the same species and belongs there.

### D4 — Aggregation splits; the watcher exposes one consistent `snapshot`
The overlay needs row-level data (which resources of which photos are still outstanding), which the
scalar `aggregates` cannot give. But materializing **all** rows in Kotlin to re-aggregate would read
the entire (large, static) completed set on every ding. So:
- `completed` and `newestCompletionAt` stay **SQL scalars** over the completed set (cheap, no
  materialization, scales).
- The **pending** side becomes a row read (`selectPending: assetId, key WHERE state != 'COMPLETED'`),
  grouped to `pendingByAsset: Map<assetId, Set<key>>` — only the backlog, which shrinks as sync drains.
- `LedgerWatcher` emits **one** `snapshot` flow that reads both halves per ding, so they are
  point-in-time consistent (two independent flows could pair a fresh count with a stale backlog). The
  watcher's `aggregates` flow (its only consumer was the status source) is **replaced** by `snapshot`;
  `backend.aggregates()`/`LedgerAggregates` are kept and reused for the scalars. Trimming the now-
  redundant `pending` scalar is left as optional follow-up — it would ripple into the storage-seam
  `clear` scenario and every backend fake for a cosmetic gain.

### D5 — The overlay merge (pure)
```
promoted   = pendingByAsset.count { (_, keys) -> keys.all { it in observed } }
completed' = snapshot.completed + promoted        // disjoint from pending assets — no double count
pending'   = pendingByAsset.size - promoted
newest'    = snapshot.newestCompletionAt          // the overlay NEVER fabricates a timestamp
```
`state != COMPLETED` covers `REQUESTED` and a stale `FAILED` uniformly (a retried-then-succeeded
resource is promoted). Empty `observed` ⇒ `promoted = 0` ⇒ identical to today. The overlay drives the
terminal COMPLETE state, because bytes-confirmed-uploaded = backed up; the extension's record is
bookkeeping catch-up. `state == COMPLETE && lastFinishedAt == null` is **already tolerated** by the
container (renders "just now").

### D6 — Sticky retention gated on the ledger's own pending set
To stop a key blinking backward during the observed→recorded handoff, retain observed keys:
```
S' = (S ∪ freshlyObserved) ∩ flatten(snapshot.pendingByAsset)
effectiveObserved = S'
```
A succeeded key stays in `pendingByAsset` (its row is still `REQUESTED`) until the snapshot shows it
`COMPLETED`; at that instant it leaves `pendingByAsset` (sticky drops it) **and** `snapshot.completed`
covers it — a seamless pivot on the same snapshot, no timers/TTLs, and `S` is bounded by the backlog
(self-empties on drain). Implemented as a stateful `scan` operator in `:domain:status` over
`(rawObserved, snapshot)`; the raw source stays dumb ("current succeeded keys").

### D7 — Cross-process ding coalesced to once per `process()` cycle
The cross-process Darwin notification is an extension→app signal (only the app watches). Today it is
posted per `put`; the overlay makes each app-side read heavier (it materializes `pendingByAsset`), so
the per-put storm is no longer "free". The `DarwinCrossProcessLedgerBackend` stops posting on `put`;
`UploadExtensionRoot` posts **once** after `cycle.run()`. The in-process `changes` flow stays per-put
(no extension-side consumer). A crash before the post merely defers the app's view to its next trigger
(foreground re-read / next poll) — missed dings are designed-harmless. This is why D6 (sticky) is
paired in: the wider gap is exactly the flicker window sticky closes.

### D8 — Poll cadence driven by the container; foreground as an injected `Flow<Boolean>`
Polling is mandatory: job success has no notification, so it is observable only by re-reading PhotoKit
between the extension's coarse runs. The `StatusContainerHost` runs the loop (it already owns a
`minuteTicker` precedent and has the merged `pending`): while `foreground && pending > 0`, call
`refresh()` every ~10s; stop on drained/backgrounded; refresh once on foreground. The foreground
signal is an **injected `Flow<Boolean>`** (resolved here; the alternative was `onForeground/onBackground`
intents). Rationale: it makes the whole loop a virtual-time-testable unit in `:domain:presentation`
and keeps the Swift scene a dumb pass-through pushing `true/false` — matching the testing strategy.
Desktop injects `flowOf(true)` and a no-op source, so the loop idles harmlessly.

## Risks / Trade-offs

- **Handoff flicker** (a just-acked key in neither observed nor recorded for a window, widened by D7)
  → D6 sticky closes it by construction.
- **Overlay claims complete, upload later proves not done** → not possible from this signal: `observed`
  is *job success*, and the extension's authoritative `COMPLETED` follows; the overlay only ever
  agrees-early, never contradicts.
- **PhotoKit read unavailable / returns nothing** (older OS, no jobs) → empty observed ⇒ overlay is a
  no-op; behavior is exactly today's. Guarded by the same `backgroundUploadSupported()` check.
- **First-sync read amplification** from `selectPending` materialization → bounded by the (shrinking)
  backlog and to once-per-cycle by D7; conflation collapses bursts; same order as today's aggregate
  scan.
- **App reading jobs accidentally consuming them** → ruled out by the spike (read-only; only the
  extension's explicit `acknowledge()` consumes). The impl must never call acknowledge/retry.

## Migration Plan

Pure addition; no data migration (the ledger schema is unchanged — `selectPending` is a new read over
the existing table). Roll out by: land the seam + overlay + snapshot + ding change together (the
overlay is inert with an empty source), wire the iOS source + scenePhase, then delete the spike
(`SnapSyncRoot.probeUploadJobs`, the `iOSApp.swift` scenePhase probe hook). Rollback is reverting the
change; the ledger and extension are untouched.

## Open Questions

- None blocking. Poll interval (~10s) and whether sticky ships in v1 vs as a fast-follow are tunable;
  current plan ships sticky in v1 (it is ~10 lines + a test and D7 depends on it).
