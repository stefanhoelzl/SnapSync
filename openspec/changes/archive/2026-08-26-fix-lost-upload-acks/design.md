## Context

Both upload tiers reimplement the same shape: enqueue work with the OS, receive a terminal callback, record
what it said, do the follow-up work. The PhotoKit tier gets durability free — `PHAssetResourceUploadJob` is
declared `: PHObject` (klib `dump-metadata`, `ios_arm64` Photos, Kotlin 2.4.0), a row in the Photos library
database, fetched with `fetchJobsWithAction` and removed only by an `acknowledge()` change request. Process
death changes nothing there; `ios-photokit-upload` states the consequence in its own words — a drained cycle
reporting `completed` would leave already-succeeded jobs *"un-acknowledged until the next change"*. Late,
never lost.

The app-driven tier reimplemented that queue's **transport** and not its **durability**.
`IosUrlSessionUploadPlatform`'s KDoc calls itself *"the OS-owned durable queue the PhotoKit tier gets for
free, reimplemented in the app process"*; `terminal` is the `.acknowledge` set, held in RAM.

The forcing fact is documented, not inferred. `URLSessionTask.State.completed`: *"The task has completed
(without being canceled), and **the task's delegate receives no further callbacks**."*
`handleEventsForBackgroundURLSession` delivers *"events related to a URL session … **waiting to be
processed**"* — a queue of undelivered events, drained once, terminated by
`urlSessionDidFinishEvents(forBackgroundURLSession:)`. No API returns a historical completion; `getAllTasks`
is documented only as "all tasks in a session". So `reattach()` cannot help: it re-adopts the session so
*pending* events are delivered, and an event already delivered in a previous process is not pending.
**Expiry trigger:** the next iOS major, or any dump showing a `task terminal:` line for a key whose task was
created in an earlier process.

Field measurement corroborates it three times — SNAPSYNC-11 at 14:58:48 → next process 15:26:24 and at
16:33:21 → 08-08 10:54:07; SNAPSYNC-16 at 17:43:44–53 → 17:45:24. In each, the next process saw no live task
and received no callback.

Two measurements bound the design. Across 125 create→terminal pairs on two devices the **fastest** completion
after `createJob` was **0.93 s** (median 17 s), while the `createJob` → `recordRequested` gap has a **median
of 14–17 ms** — so a completion landing inside that gap is not a reachable case on device. And the drain's
delay is not incidental: it is bounded by the next `fetchAckJobs`, gated on a single-flight `UploadCycle`
measured at 27 min, 65 min, and 4h49m.

The download arm has already solved a near-identical problem and its conclusions are load-bearing precedent,
cited per decision below: the destination is *"asked of the owner, not remembered here … derivable from the
description alone"*; the durable act (`moveToStaging`) happens **synchronously inside the callback**; and
`photo-download` requires that *"every verdict SHALL be applied through a store write that is **guarded on
the row's current marker** … the guard SHALL live in the store's write rather than in a caller's preceding
read, because the two writers reach it with no shared lock and a read-then-write pair is not atomic against
the one that does not take it."*

## Goals / Non-Goals

**Goals:**

- A terminal upload fact survives process death, recorded at the moment the platform delivers it.
- One state machine and one rule across both tiers.
- The stranded classification stops mis-firing: `REQUESTED` only, and never able to clobber a fact that
  arrived underneath it.
- The change is provable without a device — the regression is asserted in `:test:integration` on JVM and
  simulator.

**Non-Goals:**

- Cycle latency. The multi-hour cycles that widened this window belong to SNAPSYNC-17/18 (`upload-latency`).
  Shrinking them narrows the hole; it never closes it.
- The status screen's `total=0` defect and `Foreground.run()` ordering (SNAPSYNC-16, owned by
  `sync-then-ongoing`). The ledger's `COMPLETED` state is durable and never regressed.
- The download arm's equivalent window. Real, but ~10⁶ times narrower, with 217 of 217 field staging events
  clean. Left for evidence rather than suspicion.
- Making the status settle *instantly* on the callback. See D3.

## Decisions

### D1 — A new ledger **state**, not a `settled` column

`UPLOADED` means the bytes are stored and the announce/album step has not run. `COMPLETED` keeps its current
meaning of fully settled.

*Alternative rejected:* a `settled INTEGER NOT NULL DEFAULT 0` column. It requires a `6.sqm` migration
**and** a backfill (`UPDATE ledgerRow SET settled = 1 WHERE state = 'COMPLETED'`) whose omission would
re-announce and re-album-place the user's entire library on first launch after upgrade. Adding an enum value
to `state TEXT AS LedgerState` has no such failure mode: old databases simply contain no rows in the new
state, no schema changes, and the `verify` task's migrated-vs-created comparison has nothing to match.

The cost, stated: the Kotlin readers fail **loudly** (`SyncEngine.decide` is a `when` over `LedgerState?`
with no `else`), while three SQL predicates compare `state` to string literals and would silently mis-file
the new value. D4 is the answer to that, and it is not optional.

### D2 — The durable write is a **guarded, non-suspending** `UPDATE`, called synchronously from the callback

```sql
markTerminal:
UPDATE ledgerRow SET state = :state WHERE key = :key AND state = 'REQUESTED';
```

Atomic in SQLite, preserves every other column by construction (`assetId`, `attempt`, `eventId` and all four
manifest columns — no preservation logic to get wrong), and its `AND state = 'REQUESTED'` clause is a
compare-and-set: only a genuinely in-flight row can be flipped by a completion.

It is **non-suspending** and reports whether it applied, exactly like the download store's three callback
writes: *"The three callback writes cannot suspend … and two of them are guarded, so 'did this take effect?'
is a real question with two different consequences."* `SqlDelightLedgerStore.get`/`put` contain no
`withContext` and no dispatcher hop, so the write executes on the delegate queue's own thread.

*Alternatives rejected:*

- **`LedgerWriter.recordCompleted` under a mutex.** `LedgerWriter.record` is a read-modify-write
  (`backend.get` for manifest-detail preservation), so it needs a lock under two writers. A single guarded
  `UPDATE` does not, and a lock guarding nothing is worse than no lock.
- **`scope.launch { markTerminal(…) }`**, matching the download arm's `markStaged`. After the callback
  returns, continued runtime is not guaranteed — the app is only reliably running inside the callback or
  while a `BackgroundEventsReceipts` handler is held, and SNAPSYNC-16 shows that protection expiring:
  *"OS handler released on its 20s deadline — the session never reported its events drained."* A launched
  write then races the OS. Note that `markStaged` sitting on the launched side is **inconsistent with its own
  sibling** `moveToStaging` (a multi-MB file move done synchronously right there) — and that inconsistency
  *is* the download arm's residual window. The thing to copy is `moveToStaging`.
- **Hopping to the composition lane and blocking until done.** That lane is where `UploadCycle` runs; the
  delegate queue would block behind multi-hour cycles.

Blocking the session's serial delegate queue for one small `UPDATE` is accepted: at cap 4 it delays at most
three sibling completions by milliseconds, and the download transport already does far more work
synchronously on its own delegate queue.

### D2b — The promotion is a guarded update too, and `absent` is why we know

The `UPLOADED → COMPLETED` write is the same shape as D2's: `UPDATE … SET state = 'COMPLETED' WHERE key =
:key AND state = 'UPLOADED'`.

It was not, at first. `LedgerWriter.promote` re-stated the whole row from the entry the promotion pass had
read, which is correct only for as long as the author knows every column. Rebasing onto `main` produced the
counter-example within a day: `main` added an `absent` column (an asset that has left the library, replacing
a destructive prune), and the re-stating version would have silently dropped it — resetting `absent` to
`false` at the exact moment a row became eligible for the device manifest, re-listing a photo the library no
longer holds.

The delegate's write was never exposed to that, because it was already a one-column `UPDATE`. So the same
argument that chose the guarded write over an upsert (D2) applies to the promotion, and the two transitions
now have one shape. Nothing competes for an `UPLOADED` row — `markTerminal` is guarded on `REQUESTED` — so
the guard buys symmetry rather than safety here, and it costs nothing.

**Integration note.** `main`'s `absent` threads through the three predicates D4 rebinds; they compose as
conjunctions (`state NOT IN :doneStates AND absent = 0`). `selectUploaded` is deliberately **not** filtered
by `absent`: a row whose asset left the library after the bytes landed is still true, and excluding it would
leave it resting `UPLOADED` for good — invisible to every other read, since they all filter `absent`, and
never settled.

### D3 — `UPLOADED` counts as **pending** everywhere except the cycle's work list

Status counts it outstanding; the device manifest excludes it; only the promotion pass treats it as work.

*Alternative rejected:* counting it done for status and the manifest, which would settle the status screen
the instant the delegate writes. Rejected for conservatism — `UPLOADED` is a `REQUESTED` that has landed, and
a photo that is not yet announced is not yet fully contributed. The consequence is explicit: after a crash
the screen still reads "uploading" until the next cycle promotes. The **re-upload** stops immediately either
way, which is the larger half, and the visible timing is identical to what the removed storage check (D12)
would have given.

### D4 — The done-state set is bound from Kotlin, not written as SQL literals

`selectPending`, `aggregates` and `selectCompletedManifestRows` take `:doneStates`; one exhaustive `when` in
`model/` decides:

```kotlin
val LedgerState.isDone: Boolean get() = when (this) {
    LedgerState.COMPLETED -> true
    LedgerState.UPLOADED, LedgerState.REQUESTED, LedgerState.FAILED -> false
}   // no else — a fifth state stops the compile
```

`doneStates = {COMPLETED}` today, so every predicate keeps its current meaning. What is bought is that the
next state must be classified once, in one compile-checked place, instead of landing silently on one side of
three string comparisons.

*Alternatives rejected:* hand-editing the three predicates and pinning their text in `:test:architecture`
(pins text, not meaning — it catches a regression but not a fourth state added correctly-but-elsewhere); or
hand-editing with review only, which is the exact shape of the `pendingKeys` defect this change also fixes.

### D5 — `pendingKeys` narrows to `REQUESTED` — a prerequisite, not a cleanup

`UrlSessionUploadController` wires `pendingKeys = { ledgerStore.pendingResources()… }`, and
`pendingResources()` is documented as *"the non-`COMPLETED` rows"*. `strandedKeys`' KDoc, the platform KDoc
and `ios-url-session-upload` all say `REQUESTED`.

Two consequences. Today, every `FAILED` row re-surfaces as newly stranded on every cycle until it completes —
SNAPSYNC-16 shows one key stranded 12 times inside a single process, seven within 16 seconds, inflating
`attempt`, re-writing the row, signalling `changes`, and destroying the log line's diagnostic value. And
after D1, an `UPLOADED` row sits inside `selectPending`, so it would be handed to `strandedKeys`, found to
have no live task, and written back to `FAILED` — the new state destroyed by the very mechanism being fixed.

### D6 — The stranded pass reuses `markTerminal(key, FAILED)`

Both writers then use one conditional verb with one guard, so a row that moved to `UPLOADED` underneath a
stale pending read is never clobbered.

The engine's `retry()` was contributing an `attempt + 1` bump that nothing reads (policy is *"retry forever
— no attempt budget, no give-up"*) and a log line that is kept explicitly at the call site. Net
simplification.

*Alternative rejected:* teaching `SyncEngine` a conditional write path so every `FAILED` write keeps flowing
through it. Truer to "rules in features", but it adds a guarded-write concept to the engine and the writer
for one narrow race.

### D7 — `fetchAckJobs` becomes `drainTerminals()`; only retry-spent failures cross the port

The adapter records terminal facts into the ledger and acknowledges in place; the port returns only jobs the
cycle must **act** on. `PlatformUploadJob` shrinks to `key`, `contentType`, `data`; `state`, `error` and
`handle` lose their readers (`handle` because the adapter acknowledges in place, `state` because only one
kind of job comes back, `error` because `UploadError` is already flattened to `Unknown` and nothing
branches on it) and `PlatformJobState` is deleted with them.

**Amended while applying (3 of 3):**

1. `error` is **kept** on `PlatformUploadJob`. Dropping it was
justified by "nothing branches on it", which overlooked that `SyncEngine.handle` logs it — and the cycle
still needs one to reach the minting path. `state` and `handle` are dropped as stated.

2. **`PlatformJobState` is moved, not deleted.** It still has one real reader — `photoKitJobState`, the
   tested `PHAssetResourceUploadJobState` mapping whose declared set `:test:architecture`'s platform-
   vocabulary pin guards. What changed is that it has no *neutral* reader any more, since terminal facts
   stop crossing the port. So it moves into `PhotoKitJobMapping.kt` and is renamed `PhotoKitJobState`:
   PhotoKit vocabulary, living in the adapter named for that technology, which is where the module law
   puts technology branching. Deleting it would have meant deleting a tested mapping and un-pinning a
   platform enum to save a file.

3. **The adapters take `LedgerStore` as a constructor parameter, not through `UploadPorts`.** D-wiring
   (round 1 of the design interview) assumed the adapter could not reach the store because `uploadCore`
   builds the `LedgerWriter`. That is true of the *writer* and irrelevant here: every root already holds
   the `LedgerStore` itself — `UrlSessionUploadController` takes one, `UploadExtensionRoot` opens one —
   and hands it to `uploadCore` as `ports.ledger`. A required constructor parameter still forces every
   root to answer at the compile, which was the property that mattered, without widening the shared
   bundle or introducing any late binding.

The succeeded/failed mapping is **already** an adapter decision — that is what `PhotoKitJobMapping.kt` and
`UrlSessionOutcome.kt` were extracted for — so what moves is the write, not the judgement. And
`:adapter:ios:ext-safe` already links `LedgerStore` (`iosLedgerStore` lives there), so no new module
dependency appears.

*Alternative rejected:* returning nothing at all and letting discovery re-drive both tiers. Uniform, but the
extension is invoked on library **changes**, so a retry-spent failure with no library change behind it could
sit `FAILED` for a long time — a real latency regression on the tier with no pump to fall back on.

### D8 — `inFlight` and `terminal` are deleted

Every field is recoverable: the staging path is a pure function of the key (the download transport reached
this conclusion first — *"the destination must be derivable from the description alone"*), `contentType` is a
ledger column, tasks come from `getAllTasks()` matched on `taskDescription`, and the resource from PhotoKit
by the row's `assetId` + `role`.

This also fixes a latent bug. `createJob` gates the concurrency cap on `inFlight.size`, which is **empty
after a relaunch** while the OS still holds live tasks — so a relaunch can run 8 transfers against a cap of
4. `liveTaskKeys()` is the true count and survives process death.

Accepted cost: an XPC round-trip per `createJob` for the cap, on a path that already measures 77–150 ms per
job.

### D9 — Both tiers, one rule

PhotoKit writes `UPLOADED` and promotes within the same drain, so its behaviour is unchanged; what it gains
is that the ledger has one state machine, one predicate set, and one definition of settled. Per-tier
divergence is this project's named recurring failure mode (`UrlSessionOutcome.kt`: the rejoin reconciliation,
the direction gate and the membership read each shipped on one tier and not the other).

### D10 — Promotion does not wait on notify or album

Both stay best-effort, exactly as today (logged on failure, 8 s notify timeout). Because `UPLOADED` counts as
pending (D3), gating promotion on them would invent a new permanently-stuck state: a device whose notify
endpoint keeps failing would read "uploading" forever over photos that are already stored.

A property falls out: duplicate-notify suppression becomes **structural**. Today it is a `wasCompleted` read
taken before the write; now a re-presented success simply matches zero rows in `markTerminal` and cannot
re-enter `UPLOADED`.

### D11 — Failures get the same durable treatment as successes

`markTerminal(key, FAILED)` from the delegate, symmetric with the success path. `attempt` is left unchanged
where `SyncEngine.retry` would have written `attempt + 1`; with no attempt budget this is inert, and while
the process lives the cycle's own drain converges it. The only lasting effect is a crash-surviving row one
attempt low — stated here rather than discovered.

### D12 — The storage clause is **removed**, not implemented

`ios-url-session-upload` requires a stranded row to be *"not present in storage"* before being surfaced
`FAILED`, and its D5 decision record says the same; `strandedKeys(pending, live, drained)` has never
implemented it.

It existed to compensate for a missing durable record. With the record present, the remaining stranded
population is force-quits and dropped transfers — where iOS cancels the task and delivers **no callback at
all**, so the bytes genuinely did not land — and the check would pay a full per-device LIST (30 s-bounded,
potentially tens of thousands of entries) to be told "no". Re-PUTting one photo idempotently on the rare
occasion it *did* land is cheaper.

**This reasoning is mechanistic, not measured**, and the field data cannot settle it: every genuine stranding
in both dumps (23 of 23 and 5 of 5) was a lost acknowledgement, because the pre-fix population is entirely
dominated by the defect being removed. The listing mechanism stays where the ledger genuinely has no
memory — `ExtensionReconciler` on a rejoin.

### D13 — The single-record-writer invariant is restated as **process-level**

The adapter now records a per-key fact through `LedgerStore`, which `sync-ledger`'s type-level rule
("components that must not record are simply never handed a writer") forbids. The invariant's own Purpose
already reads *"exactly one record-writer; process placement is a platform binding"*, and on this tier the
app process **is** that writer — delegate queue included. The type-level codification is the mechanism, and
it is deliberately weakened here; the spec should say so rather than let two readings drift.

*Alternative rejected:* classifying `markTerminal` as reset-family so it can sit on `LedgerStore` beside
`clearRequested`. That verb qualifies because it is a bulk unstick interpreting nothing; `markTerminal`
records a specific per-key outcome. Calling it reset-family to fit the existing hole would make the spec say
something untrue.

### D14 — The state-and-authority law is corrected in scope, and gains one obligation

Its scenario reads *"a **core object** holds state whose loss on process death loses a fact no port can
restore"*. This defect lives in an **adapter**, which satisfies *"authority SHALL live behind ports"*
vacuously: the fact is behind a port, and the port cannot restore it. The scope becomes "a core object **or a
port implementation**".

The obligation added:

> An entry point that receives a delivery the platform makes **once** SHALL persist it before returning, and
> SHALL cite the proof that the delivery is once-only.

The forcing-proof clause makes it usable: `URLSession`'s proof is documented (`State.completed` → no further
callbacks); PhotoKit's runs the other way (jobs persist until acknowledged). So the rule names which
callbacks it binds instead of leaving each author to guess. It is the same sentence `diagnostic-logging`
already carries at this boundary with a different object — a fact the platform delivered must not vanish
without a **trace**; now, nor without a **record**.

*Alternative rejected:* a mechanical gate. The OS-callback population is seven functions, exactly one of
which violates this, and no syntactic rule separates `terminal += Terminal(…)` (the bug) from
`outstandingImports += scope.launch { … }` (correct coordination). A forced per-callback declaration
(`@DurableAtEntry` / `@NoDurableFact("…")`) would become copy-paste on the eighth callback and then assert
"somebody checked" when nobody did — a green run that means nothing, which this repo has already paid for
once (the archive placeholder gate deleted by `update --force`). The class stays a review criterion; **this
instance** is gated by the integration test, and the **next** one announces itself because `markTerminal`
reports when a guarded write matches nothing.

### D15 — Rejected outright: writing `COMPLETED` from the delegate

Considered and rejected before D1. `settleTerminalJobs` fires two effects off a false→true transition
(`completedThisCycle` gates the notify fan-out; `completedAssetIds` gates album placement), so a delegate
that wrote `COMPLETED` would make `wasCompleted` already true and **neither would fire** — not in the crash
case, in the normal one. Additionally: `SyncEngine` states it tolerates one `handle` call in flight; the
adapter has no `eventId` or `attempt`; and `IosUrlSessionUploadPlatform` is constructed *before* `uploadCore`
builds the `LedgerWriter`, so reaching it needs a late-bound mutable field of exactly the shape a
`:test:architecture` guard confines to `BackgroundEventsReceipts`.

### D16 — Rejected: a marker file as the durable artifact

Attractive because it needs no ledger handle and mirrors the download arm's `moveToStaging` literally. It
cannot carry the CAS guard: the write and the state it is conditioned on would be in different systems, so it
degrades to the read-then-write pair `photo-download` explicitly forbids.

### D17 — `UPLOADED` rows on a leave or a direction-declined cycle are left alone

Such a cycle must not notify or place in an album, so it must not promote. Rows stay `UPLOADED` until the
device rejoins, at which point `resetTo` seeds them `COMPLETED` from the device listing — the bytes did land,
so the listing reports them. Status only means anything while joined, so a dangling row on a left device
costs a non-zero `photos_pending` in a diagnostic dump and nothing else.

*Alternatives rejected:* promoting without announcing (silently drops the announcement for photos that
genuinely uploaded), or clearing them on leave (discards the only durable record that the bytes are stored,
so a rejoin re-uploads until the listing seed catches up).

## Risks / Trade-offs

- **[A completion inside the `createJob` → `recordRequested` gap is lost]** The guarded `UPDATE` matches zero
  rows and the fact is gone → one re-upload. → Accepted and bounded: measured median 14–17 ms against a
  fastest-ever completion of 0.93 s across 125 field pairs, so it is not a reachable case on device. It *is*
  plausibly reachable on a simulator, where `nsurlsessiond` rejects the task and `NSURLErrorDomain/-1`
  returns immediately — but a simulator performs no background transfers at all, so that is a harness
  concern. The zero-row result is **logged, never silent**. Not closed by writing `REQUESTED` before
  `createJob`: that inverts write-after-act, and a `createJob` returning `FAILED`/`LIMIT_EXCEEDED` would
  leave a phantom in-flight row the engine skips forever.
- **[The stranded read/write interleaving]** A cycle can read the pending set, see a row `REQUESTED`, and
  have the delegate flip it before the cycle writes `FAILED`. → D6's shared CAS guard closes the clobber;
  what remains is that the cycle may surface a job for a key that just settled, costing one re-upload —
  today's behaviour, not a new one.
- **[A crash between album placement and promotion re-places the asset]** ~~Blocked on the open question
  below.~~ **Retired 2026-08-25:** `addAssets` is measured idempotent (simulator, iOS 26.5 / Xcode 26.6 —
  three adds, one member, no throw; see Open Questions), so a repeat add after a crash is a no-op and this
  costs nothing. Re-open at the next iOS major, or if the event album's collection type changes.
- **[Status settles at the next cycle, not at the callback]** Consequence of D3, accepted deliberately. The
  re-upload — the expensive and user-visible half — stops immediately.
- **[The PhotoKit tier is touched for a defect it does not have]** D9 changes a tier with no reported bug. →
  Its drain writes and promotes in the same pass, so no observable behaviour changes; the risk is
  regression, not design, and it is covered by the existing PhotoKit mapping tests plus the new integration
  test.
- **[Removing the storage clause rests on reasoning, not measurement]** D12 is explicit about this. → If the
  post-fix stranded population turns out to be dominated by keys that *did* land, the clause is
  reinstatable on its own; the port (`DeviceFilesSource`) and its composition remain in place for
  `ExtensionReconciler`.
- **[Deleting `inFlight` costs an XPC round-trip per `createJob`]** → Accepted: the path already measures
  77–150 ms per job, and the round-trip buys a cap that is correct across a relaunch.

## On-device verification

Measured on the connected SE2 (**iPhone12,8, iOS 26.6 / 23G71**), app-driven tier pinned over the control
channel, a self-created event, `UploadOnly`:

```
22:41:20.933 [upload.didComplete]   task terminal: 072AA639-…-primary.jpg -> UPLOADED
22:41:20.955 [url-session.runCycle] promoted 1 uploaded row(s) to COMPLETED
22:41:21.744                        task terminal: F6C13218-…-primary.jpg -> UPLOADED
22:41:22.170 [url-session.runCycle] promoted 2 uploaded row(s) to COMPLETED
```

**Confirmed:** the real background-`URLSession` delegate callback reaches `recordTerminal` on a device; the
guarded write lands `UPLOADED` at that moment, under the `[upload.didComplete]` entry point; the cycle's
promotion pass settles it. 30 photos uploaded across the session with **no** `stranded` line and **no**
duplicate `createJob` for any key. This is the half no simulator can reach — a simulator performs no
background `URLSession` transfer at all.

**Not reproduced on device:** a force-quit landing *between* the terminal write and the promotion. That
window is ~20 ms against ~1 s uploads, and four attempts missed it (two landed during discovery, one before
uploads started, one after the batch had drained). It is covered deterministically by
`LostUploadAckIntegrationTest`, which builds a second `World` over the same ledger backend — a process
boundary with no race to win.

**A rig limitation worth recording**, and the reason repeating the attempt is expensive: the upload-mechanism
pin is **in-memory**. A relaunched process resolves its tier — and starts that producer — before any control-channel
request can arrive, so every kill returns the device to the PhotoKit tier and the pin has to be re-applied
*and* a membership transition forced before the app-driven tier runs again. `rig-channel`'s own note
anticipates this: a durable input is needed "a process the OS relaunches … resolves its tier before any
request can arrive".

⏰ Re-measure the delegate behaviour at the next iOS major, alongside the once-only premise in Context.

## Migration Plan

**No database migration.** Adding an enum value to `state TEXT AS LedgerState` changes no schema, so no
`6.sqm` is written and the `verify` task's migrated-vs-created comparison has nothing to match. Old databases
contain no `UPLOADED` rows; the first delegate write creates the first one.

**Rollback** is a plain revert. A build carrying `UPLOADED` rows that is rolled back to a build without the
state would decode an unknown enum value — so a revert after field exposure must ship a one-line migration
mapping `UPLOADED` → `REQUESTED` (safe: the key re-uploads idempotently). Stated here so a rollback is not
attempted without it.

**Ordering.** D5 (`pendingKeys` → `REQUESTED`-only) lands first and is independently reviewable — it is a
standalone bug fix and a hard prerequisite for D1. D4's predicate binding lands before D1, so no predicate is
ever briefly wrong. The port reshape (D7) and the adapter deletions (D8) follow, and the integration test
lands with them.

## Open Questions

- **Does `PHAssetCollectionChangeRequest.addAssets` duplicate an asset already in the album?** Settled by a
  measurement, per "a platform-capability claim is settled by a compile, not by a symbol table" — neither the
  klib nor our expectation answers it. A headless simulator check (place, place again, count) decides whether
  D10's crash window needs any mitigation at all.

  **Checked 2026-08-25, unresolved.** Apple's documentation for `addAssets(_:)` says only *"Adds the
  specified assets to the asset collection"*, plus notes on ordering, My Photo Stream / iCloud Shared Album
  / iTunes-synced assets, and transient collections. **It says nothing about an asset already in the
  collection** — no duplicate, no-op, or error. `openspec/specs/event-album/spec.md` records no measurement
  either. So the question stands and needs a real one; a simulator requires macOS, so it costs an
  `ssh-mac` round-trip.

  Worth noting what makes this question **new**: today the placement set is built from `!wasCompleted`
  transitions, so a re-handed job never re-places, and the current design has never needed the answer.
  D10 introduces the first path on which a repeat is reachable.

  **SETTLED 2026-08-25 by measurement: `addAssets` is IDEMPOTENT.** Three consecutive
  `PHAssetCollectionChangeRequest(for:)?.addAssets([asset])` calls against a regular user album, each in
  its own `performChangesAndWait`, left the album with **exactly one** member and threw nothing:

  ```
  count before any add = 0
  add#1: add returned, count=1
  add#2: add returned, count=1
  add#3: add returned, count=1
  final members=["A0F2F08F-…/L0/001"]
  ```

  Measured on a booted simulator — **iOS 26.5 (23F77), Xcode 26.6, macOS 26.5.2** — with a throwaway
  single-purpose app (`app.snapsync.addassetsprobe`) granted `photos=YES` via `applesimutils`, since
  `simctl privacy` does not work for PhotoKit (`ios-simulator`) and a bare Kotlin/Native test binary is
  not a grantable bundle. The album was created with `creationRequestForAssetCollection(withTitle:)` —
  the same call `IosAlbumManager.ensureCreated` makes — so the collection is the shape this app actually
  uses. Nothing was added to the repo; the probe lived only on the runner.

  **Consequence: D10 needs no mitigation.** A crash between the album placement and the promotion is
  free — the next cycle's repeat add is a no-op. The risk row below is retired, and task 6.2 carries no
  conditional.

  ⏰ **Expiry trigger:** the next iOS major, or a change to the collection type the event album is
  created as. Caveats stated: simulator not device, n=1 runtime, and regular user albums only (Apple
  documents that smart albums and transient collections do not support adding at all).
- **How does the cycle mint a retry request now that `UploadError` no longer crosses the port?** Today the
  fresh presigned request arrives via `engine.handle(UploadFailed)` → `retry()` → `SyncDecision.Retry`, whose
  event carries the error. With D7 the adapter logs the error where it is known and the cycle only needs a
  fresh mint for a key. Implementation-level, but it should be settled before the port change is written
  rather than discovered inside it.

  **Settled 2026-08-25, and it revises D7.** The minting path is reachable unchanged: the cycle keeps
  calling `engine.handle(SyncEvent.UploadFailed(job, error))`, which records `FAILED` at `attempt + 1` and
  answers `SyncDecision.Retry` with a freshly minted request — exactly as today. The row is already `FAILED`
  from the adapter's guarded write, and the engine's write is an idempotent upsert that additionally
  advances the attempt, which a re-created job should do. No new engine verb, no `mint` query, no new
  `SyncEvent`.

  What that needs is an `UploadError`, so **`error` stays on `PlatformUploadJob`** — reverting that one
  field of D7. D7's stated reason for dropping it ("nothing branches on it") was wrong: `SyncEngine.handle`
  **logs** it (`failed key=… error=…`), and logging is a use. Substituting a constant would move the real
  domain/code into the adapter's line and leave the engine's line saying `Unknown(detail=retry-spent)` for
  every failure — two lines to correlate where there is now one, on the tier whose diagnosis depends on
  `debug.log`. `state` and `handle` are still dropped, and `PlatformJobState` still goes with `state`.
