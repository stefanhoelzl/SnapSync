## Context

`UploadCycle.run()` has two early returns that mean *"the platform will not take another job right
now"*:

```
262:  if (capHit) return CycleResult.PROCESSING          // cursor NOT advanced
321:  LIMIT_EXCEEDED -> return CycleResult.PROCESSING    // cursor NOT advanced
```

Five things sit below them: the per-resource manifest-detail backfill (335), the `enumeration:`
audit line (341), the device-manifest write (371), the completion notify (380), and the discovery
cursor (384). None of them runs on a truncated cycle.

On the app-driven tier the trigger for that truncation is `IosUrlSessionUploadPlatform.cap = 4` — an
in-flight **concurrency** bound, measured since `fix-lost-upload-acks` against the session's live
tasks. `UploadCycle` was written against the PhotoKit meaning of `LIMIT_EXCEEDED` (the OS's durable
job queue is full, drained while the app is not running) and reads the app tier's four open sockets
the same way. Four concurrent transfers is the *normal* state of any backlog, so on that tier
`CycleResult.COMPLETED` is unreachable for as long as the device is behind.

That makes the cycle bistable, with `cap` as the basin boundary:

```
   ┌───────────────────────────────┐        ┌───────────────────────────────┐
   │ CAUGHT UP (stable)            │        │ BEHIND (stable)               │
   │ cursor set → incremental walk │  ───►  │ cursor null → FULL walk       │
   │ drains, publishes, stays      │  ◄───  │ every cycle, ≤4 uploads each, │
   │                               │ rare   │ never drains, never publishes │
   └───────────────────────────────┘        └───────────────────────────────┘
                     ▲                                    ▲
                     └──────────── cap = 4 ───────────────┘
```

At 4, three Live Photos cross the boundary, and nothing crosses back except attrition.

**Field evidence** (SNAPSYNC-16 diagnostic dump, build 0.3(605), iPhone11,2 / iOS 18.7.9, event
"Triglav", 2026-08-14 16:04:22 → 18:09:38 UTC):

- 26 cycles, `PROCESSING` × 26, `COMPLETED` × 0; `createJob` = `CREATED` 69 / `LIMIT_EXCEEDED` 26.
- Every walk a **full** enumeration (224 candidates, 6.1–7.2 s **on that device under that load** —
  see "The walk's cost, measured", where the same operation costs milliseconds on an idle one),
  because `saveToken` — the only cursor writer in production, at `UploadCycle.kt:384` — never ran.
- The candidate count grew 99 → 224 across the window while the admitted set stayed at 71: the
  download arm's own imports enter the library, so the walk gets more expensive as the event runs.
- 65 completions, 53 assets placed in the event album, and **zero** `PUT /events/<id>/devices/<id>`.
- The `enumeration:` audit line appears **0 times** in 4 531 lines, so the remaining backlog is not
  readable from the log at all.
- The live union confirms the shape rather than the strong reading: the device now lists 136 assets,
  of which exactly 71 have a capture date at or before the dump — matching that log's
  `admitted 71 of 224`. Nothing was lost. The failure is an unbounded silent **lag**.

**A second face of the same defect.** Since `fix-lost-upload-acks`, the app tier's
`drainTerminals()` returns `emptyList()` and the delegate writes `FAILED` directly, compensating
with *"the engine re-uploads a `FAILED` key from a later discovery."* An incremental walk returns
only changed assets, so a `FAILED` row whose asset has not changed is never re-derived. That change's
own design record rejected this shape — for the other tier — because *"the extension is invoked on
library changes, so a retry-spent failure with no library change behind it could sit `FAILED` for a
long time"*. The app tier is credited with a pump as the mitigation, but the pump only runs more
cycles and a cycle's only source of work is the walk. It does not bite today because a busy device's
cursor is never settled: **the bug this change removes is what masks it.**

## Goals / Non-Goals

**Goals:**

- A member's uploaded photos reach the event union while they are still uploading, not after they
  stop.
- A `FAILED` or not-yet-enqueued resource is re-enqueued without a full library enumeration.
- The discovery cursor advances whenever it is safe to, under a stated and checkable condition.
- The cycle's publication decisions are readable in one place, and a new exit cannot silently skip
  one.
- A device log states the remaining backlog on every cycle, drained or not.

**Non-Goals:**

- **Changing `cap`.** It stays 4. The July `add-url-session-upload` open question — *"exact
  concurrency cap … (working assumption ~4) — tune on device"* — stays open. This change removes the
  cap's architectural load-bearing role; whether 4 is the right throughput number is a separate,
  measurable question.
- **The walk's own cost.** That the walk is synchronous XPC, stays outstanding across app suspension
  (760 s and 1 716 s observed), and can cost 200× more per candidate under concurrent import load
  than idle, belongs to `suspended-discovery-walk` and `upload-latency`. This change makes the walk
  happen once per library change instead of once per four uploads; it makes no single walk faster.
- **The status projection.** `DISCOVERED` rows will make the pending count reflect known-but-unstarted
  work for the first time. That consequence is accepted, not designed here.
- **Damping the notify fan-out.** See Risks.

## Decisions

### D1 — The ledger is the upload work source

A new `LedgerState.DISCOVERED` is written for every resource this cycle's walk admitted and the
engine judged to be new work, **before** any `createJob`. The producer then enqueues from the
ledger's rows rather than from the walk's return value, so a cycle makes progress on work it already
knows about whatever the change feed reports. It still consults that feed — see Open Questions, the
cursor is not a change oracle — but it no longer depends on the feed re-deriving work already seen.

The row's payload is not enough to upload — `createJob(request, resource)` needs a live
`PHAssetResource` and the ledger holds strings — so a port verb resolves ledger keys to uploadable
resources. The machinery already exists and is used id-scoped in four places
(`PHAsset.fetchAssetsWithLocalIdentifiers` → `PhotoKitCandidateSource.candidatesFrom` →
`.resources()`); what is new is the seam.

*Alternative rejected — a separate work-queue table.* It keeps `sync-ledger`'s purpose narrow, and it
is the shape this codebase has already removed once: `DeviceManifestProducer` maintained a
device-global accumulator beside the ledger, deleted because *"a second durable structure tracking
the same asset set with different columns … the ledger already had to be right about all of that, so
the accumulator was duplication that could only ever disagree."* Deletion, provenance, absence
marking and scope narrowing would all have to be kept in step across both.

*Alternative rejected — a residue file keyed by cursor.* Same objection, plus it is what
`UploadCycle`'s own KDoc rules out (*"no residue store"*) without addressing cost.

*Alternatives deferred — raise `cap`, or make `createJob` wait for a slot.* Both move the basin
boundary so the bad state becomes rare; neither removes it, and both leave the walk as the only
source of work, so neither fixes the `FAILED`-never-retried face. They become tuning knobs once D1
lands.

### D2 — The cursor advances on durable facts, not on job creation

The rule: **the cursor may advance once every fact the walk produced is durable.** `Discovery` has
four fields; three carry facts that exist nowhere else, and the fourth is read by nobody:

| walk output | captured by | |
|---|---|---|
| `candidates` → admitted → engine says `Work` | a `DISCOVERED` row | D1 |
| `candidates` → admitted → already uploaded, row bare | `backfillManifestDetail` | D6 |
| `removedAssetIds` | `markAbsent` | already unconditional |
| `nextToken` | `saveToken` | this decision |
| `fullEnumeration` | nothing reads it (vestigial) | — |

`saveToken` therefore moves **out of the publish tail and into the write stage**, immediately after
those three writes and before the first `createJob`. Truncation stops touching the cursor because
the cursor is no longer downstream of job creation.

**Ordering, not atomicity, is the safety property.** Rows first, then the token: a process death in
between costs one re-derivation, and the rows are idempotent. Only the reverse order loses work. No
transaction is required — this is the write-after-act discipline the engine already uses, applied
one level up.

This completes a symmetry the codebase already maintains on one side. The dual — *clear the cursor
whenever a durable fact behind it is destroyed* — is stated and honoured in all three places that
destroy rows: `ResetDeviceState` (*"Without this the ledger clear achieves nothing: no change token
means no enumeration"*), `ExtensionReconciler`, and `OsDrivenUploadMechanism.stop()` (*"a settled
cursor would scan incrementally and never re-surface them"*). The advance side has never been
stated; the cycle uses a proxy — *every job was created* — that is far stricter than necessary and
that a busy device never satisfies.

### D3 — Two state classifications, both compile-forced

`isDone` already exists as the single decision behind every state-scoped read, with the reasoning
that a literal in a query would let a new state land silently on one side. The producer's question
is a second, independent axis, and it gets the same treatment:

| | `isDone` | `needsJob` | what the state records |
|---|---|---|---|
| `DISCOVERED` | no | **yes** | the walk found it; nothing attempted |
| `REQUESTED` | no | no | a job exists (write-after-act) |
| `UPLOADED` | no | no | bytes landed; owes promotion |
| `COMPLETED` | **yes** | no | the completion work ran |
| `FAILED` | no | **yes** | the platform reported a failure |

Both are `when` expressions in `model/` with no `else`, so a sixth state must be classified on both
axes or the build fails. `requestedKeys()` is effectively a third such read and stays as it is.

The table is also the change's central claim: **`DISCOVERED` and `FAILED` are the same thing to the
producer.** That is why the invisibility and the never-retried failure are one fix.

### D4 — The state is called `DISCOVERED`

Every existing state is named for the event that put the row there — a job was created, the platform
told us, the completion work ran, the platform reported a failure. `DISCOVERED` fits that pattern:
the walk found it.

*Rejected — `PENDING`.* `pendingResources()` returns every non-done row and `LedgerAggregates.pending`
counts every photo with one, so the word already means *not done* at a wider scope. A
`PendingResource` would usually not be `PENDING`.

*Rejected — `ADMITTED`.* It matches the code's own vocabulary (`val admitted = EventPhotoSet(policy)
.assets()`, and the comment `THE ADMISSION`), but admission is **policy-dependent** and the ledger is
deliberately policy-independent — device-global, projected per membership. The name would encode a
judgement the row must outlive.

### D5 — The pull applies no selection policy

A `DISCOVERED` row is enqueued without re-checking the policy, and this is correct rather than
lax:

- **Origin exclusions** (screenshot, screen recording, GIF, sub-floor received media, denylisted
  album, echo) are event-independent and applied at admission, so an excluded asset never earns a
  row — `device-manifest`: *"an origin-excluded asset therefore never earns a `COMPLETED` row."*
- **Capture-date bounds** are per-membership and belong to the projection — same spec: *"the ledger
  must retain an out-of-range row because another event's range may admit it."*
- Bytes are already defined as policy-independent (`sync-ledger`): what is on the backend is *"a
  fact about BYTES … independent of the selection policy."*

So a row that narrowed out of scope after a reconfigure may upload harmlessly: the manifest
projection excludes it, so it never enters the union. The cost is some wasted bytes on a narrowing
reconfigure — already the accepted behaviour, since `reconfigure-membership` deliberately lets
in-flight uploads drain rather than cancelling them. `photo-selection-policy` keeps its one-place
property untouched.

### D6 — `run()` becomes four stages, and `publish()` is the only producer of a `CycleResult`

```
run():
    settled = settle()            // gate · policy · reconcile · drain returned jobs
    decided = settled.decide()    // walk · admit · per-resource engine decisions   READS ONLY
    updated = decided.update()    // DISCOVERED rows · markAbsent · backfill · SAVE CURSOR
                                  //   · then create jobs up to the cap        OWN WRITES
    return updated.publish()      // album · audit line · manifest · notify · promote   OUTWARD
```

Stages are **member extension functions**, so the stage results stay pure data with no
back-reference to the cycle. Each is sealed over `Proceeding | Short(outcome)`; a `Short` forwards
in one line. Because only `publish()` returns a `CycleResult`, **no path can return without
publishing** — the entire bug class this change addresses becomes inexpressible rather than merely
visible.

The split is licensed by an invariant that must be stated in KDoc on `decide()`: a decision taken
there is still valid in `update()` because `LedgerWriter` is the ledger's only writer, the cycle is
its only entry, and the pump is single-flight; the `URLSession` delegate appends to storage through
the guarded `markTerminal`, never through a read-then-write. If either property stops holding, this
split creates a duplicate-upload path.

`drainTerminals()` stays inside `settle()` rather than splitting across `decide`/`update`: it is a
destructive read, and separating it from its own recording would widen the window
`fix-lost-upload-acks` just closed. It also needs the engine, which needs the gate's config — so the
obligation is *"once we have a config, drain, no matter what else is true"*, which is one job with an
internal order.

*Rejected — a context-manager / `try-finally` scope for the cursor.* It is the shape that was asked
for and the worst fit for the thing it was asked for: `__exit__` runs on **every** exit, and the
cursor must be written on **some**. It also makes the scope a mutable accumulator (what gets
published depends on how far you got — the same property being removed, relocated into a variable),
runs network writes while unwinding a `CancellationException`, and cannot carry an exhaustive `when`.

*Rejected — naming stage one `settle` without renaming.* `settle` was overloaded three ways;
`settleTerminalJobs` is already gone, and the remaining collisions (`ledgerSettled`, and
`event-rejoin-reconciliation`'s "defers without settling", which means the ledger **seed**) are
renamed so one word means one thing. Neither name appears in any spec; both are confined to
`UploadCycle.kt`.

### D7 — The notify fires when the manifest projection changed

`DeviceManifestProducer.produce` reports whether it wrote, and the notify rides that, replacing
*drained cycle with ≥1 completion*.

The current gate is not merely mis-scoped, it is **consumed**. `promoteUploaded()` runs before both
truncation returns: it promotes `UPLOADED` → `COMPLETED` and returns the count the notify is gated
on. A truncated cycle promotes and cannot announce; the next cycle's `uploadedRows()` is empty, so a
cycle that *does* drain can find nothing left to notify about. The last completions of a backlog are
the most likely to be orphaned — exactly the batch other members are waiting for.

"The projection changed" is derived from durable state, so it cannot be consumed by a cycle that
could not act on it. It is also the semantically exact predicate: the notify exists to say *the
union now lists something new*. It needs no new durable state, and it closes an existing spurious
push (today the cycle notifies on `completedThisCycle > 0` even when `produce()` skipped its PUT
because the projection was unchanged).

*Rejected — a min-interval throttle.* It is a heuristic that can suppress a genuinely-new-content
push, and it needs a durable last-notified stamp with its own kill-test, reset and rejoin semantics.

### D8 — A deferred re-join reconciliation settles with the platform

`drainTerminals()`/`recreateRetrySpent` moves above both the `contributes` and `mayUpload` branches,
into `settle()`. The manifest suppression on a deferred reconcile is unchanged — that is specified
and correct.

Today the two branches disagree, and both are pinned by tests 75 lines apart:

```
UploadCycleTest:488  assertTrue(platform.drained,
    "a declined cycle still settles with the platform — an un-acknowledged presented job
     errors the system 50008 and the OS discards the outstanding jobs")

UploadCycleTest:563  assertTrue(!platform.drained, "a deferred cycle must not settle jobs either")
```

**No spec requires the second.** `event-rejoin-reconciliation`'s scenarios titled *"defers without
settling"* mean the ledger **seed** (`resetTo`), and their bodies say only *"no upload jobs are
created, the marker stays unset, the next cycle retries."* `upload-lifecycle` states the opposite
obligation outright, with the device measurement, and closes: *"A requirement whose safety rests on a
premise that is false on a shipped path is the failure this clause removes."* Making the drain a
stage that runs before either branch makes the asymmetry unrepresentable.

### D9 — What this unlocks but does not do

With `DISCOVERED` available, `clearRequested()` — the repair for jobs the OS wipes on extension
disable — could become a **demotion** (`REQUESTED` → `DISCOVERED`) rather than a deletion. The paired
`clearToken()` would then be unnecessary and the wiped jobs would be re-enqueued from the ledger on
the next cycle with no library walk, turning that tier's most expensive repair into one `UPDATE`.
Out of scope here, recorded because two independent mechanisms wanting the same state is evidence it
is a missing concept rather than a bolt-on.

## Risks / Trade-offs

- **[D1 and D2 are not separable — shipping the cursor change without the ledger work source loses
  the entire backlog, silently]** → They are one change, which is why this is not split into
  sequential PRs. The bare-row backfill joins them: advance the cursor without it and a re-joined
  member's capture dates are never learned, so their photos stay out of every projection with no
  error anywhere. All three land together or none does.
- **[The ledger's purpose widens from a record of uploads to a record of uploads and outstanding
  work]** → Argued rather than waved past: `REQUESTED` is already *"a hope; the engine cannot prove
  it was executed"*, so the seam already holds non-facts. `DISCOVERED` moves that line one step
  earlier along the same axis rather than crossing a new one. The alternative — a second structure —
  has already been tried and deleted here.
- **[A first walk on a large library writes one row per outstanding resource]** → One-time per
  backlog: the cursor advances immediately after, so subsequent walks are incremental and produce
  ~none. `resetTo` already establishes the bulk-insert-in-one-transaction shape. The state-scoped
  read takes a bound so a single cycle cannot try to enqueue thousands.
- **[Freeing the notify makes a live event mutually exciting: A completes → notifies → B wakes → B
  completes → notifies → A wakes]** → **Accepted, not solved.** It terminates — it sustains only
  while devices have real backlog, which is finite — so it is amplification, not runaway. But every
  wake costs a library walk, so it compounds with what `suspended-discovery-walk` and
  `upload-latency` own. In the observed window it would have been ~25 notifies in 2 h 05 m against
  today's 0.
- **[More device-manifest PUTs]** → ~25 in the observed window rather than 0, each a small JSON body,
  blunted by `produce()`'s skip-if-unchanged on cycles that completed nothing. On the extension tier
  the write is bounded at 12 s out of a ~3-minute budget, unchanged.
- **[The battery and latency win is smaller than the field log suggests]** → **Measured, and the
  framing was wrong** — see "The walk's cost, measured" below. The per-cycle waste is real but its
  size is situational, not a property of full enumeration. The correctness fixes never depended on
  it and stand unchanged.
- **[The status pending count changes meaning]** → It starts reflecting known-but-unstarted work,
  which it structurally cannot today (in the dump: 71 admitted photos, 57 completed, 3 pending —
  eleven photos with no row at all). This is a truthfulness improvement, but it lands in
  `sync-status`'s projection and should be verified against it rather than assumed benign.

## The walk's cost, measured

Measured 2026-08-27 on the connected device — **iPhone12,8 (SE2) / iOS 26.6**, from its own
`debug.log` covering 2026-08-25 → 08-27, 25 discovery walks:

| walk | candidates | duration | n |
|---|---|---|---|
| **incremental, nothing changed** | 0 | **5–17 ms** | 6 |
| full enumeration | 66 | 59–80 ms | 18 |
| full enumeration | **1084** | **145 ms** | 1 |

Two conclusions, and the second corrects this document.

**The change buys what it claimed, qualitatively.** An incremental walk that finds nothing costs
~10× less than the smallest full enumeration on the same device, and the gap widens with library
size: the incremental walk is bounded by what changed, the full one by what exists. A device that
never advances its cursor pays the full price on every cycle, forever.

**But a full enumeration is not intrinsically expensive, and this document said it was.** 1084
candidates in 145 ms is ~0.13 ms per candidate — while SNAPSYNC-16 recorded 224 candidates in
6.1–7.2 s, ~28 ms per candidate, **200× slower per candidate on a smaller set**. Candidate count
therefore does not explain the field measurement. What differs is the situation: an older device
(iPhone11,2 / A12 vs SE2 / A13), and — far more likely to dominate — 104 foreign assets being
imported concurrently, so every PhotoKit round-trip in the walk was contending with `assetsd` for
the same XPC service.

So the honest statement of the waste is: **a device that cannot advance its cursor re-walks its
library on every cycle, and pays whatever that costs on that device under that load — which the
field shows can be seconds, and this measurement shows is normally milliseconds.** The "6.5 s per
four uploads" figure is a real observation of one device in one state, not a property of the
mechanism. It is quoted in Context because it is what the affected member actually experienced.

That the two differ by 200× is itself a finding, and it is **not this change's to explain**: it
belongs with the walk's own behaviour under load and across suspension, which `upload-latency` and
`suspended-discovery-walk` own. This change removes the *repetition*; it does not make any one walk
faster.

⏰ Re-measure at the next iOS major, or on a device whose library is an order of magnitude larger.
Caveats: one device, one point release, an idle library, and n=1 at the largest size.

## Verified on device

Built with `-Psnapsync.rig=true`, installed on **iPhone12,8 / iOS 26.6**, tier pinned to
`url_session` (`cap = 4`), 1536-asset library, a freshly-minted event, 20 policy-probe assets seeded
(10 admitted by the 3 MP floor). 2026-08-27.

The whole mechanism is visible in one pair of consecutive cycles:

```
07:57:48.885  ← discoverResources = 20 candidate(s) (46ms)        the walk
07:57:49.014  selection policy admitted 10 of 20 → 10 resource(s)
              …4 jobs created, then LIMIT_EXCEEDED…
07:57:49.106  enumeration: 10 seen, 10 new, 0 already-uploaded — TRUNCATED
07:57:49.107  ← runCycle = PROCESSING (288ms)

07:57:50.148  → platform.resourcesFor(6 key(s))                   ← THE REMAINDER, from the LEDGER
07:57:50.183  ← platform.resourcesFor = 6 resource(s) (34ms)
07:57:50.194  ← createJob = CREATED  ×2, then LIMIT_EXCEEDED
07:57:50.203  enumeration: 0 seen, 0 new, 0 already-uploaded — TRUNCATED   ← the walk found NOTHING
07:57:50.204  promoted 2 uploaded row(s) to COMPLETED
07:57:50.448  PUT  /events/<id>/devices/<id>                      ← PUBLISHED on a truncated cycle
07:57:51.159  POST /events/<id>/notify → 202                      ← NOTIFIED on a truncated cycle
07:57:51.167  ← runCycle = PROCESSING (1047ms)
```

Five things this settles that no test could:

1. **`resourcesFor` works against real PhotoKit** — 6 keys resolved in 34 ms, 4 in 27 ms. This was
   the one piece of new code a simulator or a fake could not validate.
2. **A truncated cycle publishes.** The `PUT` and the `notify` above both sit inside a cycle that
   returned `PROCESSING`. In SNAPSYNC-16 that cycle shape occurred 26 times and published nothing.
3. **A cycle whose walk finds nothing still makes progress.** `enumeration: 0 seen` and two jobs
   created in the same cycle — the ledger, not the change feed, supplied the work.
4. **The cursor advances**, so every later walk is incremental: 6–10 ms against a 1536-asset library.
5. **The audit line exists.** It appears zero times in the SNAPSYNC-16 log and on every cycle here,
   stating truncation where it happened.

**End to end:** `GET /events/<id>/files` returned **10 assets** — the full admitted set, in the event
union, published across truncated cycles while the device was still uploading. That is the failure
this change exists to remove, observed not to happen.

## Migration Plan

**Forward.** No schema migration: `state` is a SQLDelight typed enum column (`TEXT AS LedgerState`),
so a fifth value is a value, and the three `.sq` predicates already bind `:doneStates` from Kotlin.
Existing rows are untouched. The first cycle after the update walks, writes `DISCOVERED` rows for
whatever is outstanding, advances the cursor, and proceeds.

**Rollback.** A build without `DISCOVERED` cannot decode a `DISCOVERED` row (the SQLDelight enum
adapter throws on an unknown value), so a rollback is not free. The stated recovery, mirroring
`fix-lost-upload-acks`' `UPLOADED` → `REQUESTED` mapping: **delete every `DISCOVERED` row and clear
the discovery cursor.** The old code then performs one full enumeration and re-derives them, and the
retained `COMPLETED` rows suppress re-upload — which is exactly the cost
`event-rejoin-reconciliation` already names: *"a cleared cursor costs a re-enumeration, not a
re-upload."*

## Open Questions

- ~~**What does an incremental walk actually cost on a device?**~~ **Measured 2026-08-27** — and the
  answer corrects one of this document's own framings. See "The walk's cost, measured" below.

- **The cursor is not a change oracle, and the design briefly assumed it was.** An early task asked
  the cycle to skip the walk when the cursor reported no change. It cannot: `discoverResources(token)`
  **is** the question, and `fetchPersistentChangesSinceToken` returning an empty change set is the
  answer. There is no cheaper way to ask, and the codebase's only change *observer*
  (`PhotoSelectionChangeSource`) exists for limited mode alone. So every cycle still consults the
  feed; what this change buys is that the consultation is incremental rather than a full
  enumeration — which is why the unmeasured cost above is load-bearing for the *latency* claim, even
  though the correctness claims stand without it.

  The alternative considered and rejected: skip the walk while the ledger already holds a full batch
  of work, on the grounds that nothing a walk found could be enqueued anyway. It would have made the
  "no library read on a top-up" promise literally true, at the cost of not noticing new photos or
  deletions until the backlog drained. Rejected as buying a promise rather than a behaviour.
- **How many `DISCOVERED` rows should one cycle enqueue from?** The read needs a bound; the right
  number depends on the resolve verb's per-key cost, which is measured only indirectly (0.3 s for 71
  assets' resources in the field log).
- **Is `cap = 4` right?** Left open deliberately. `add-url-session-upload` recorded it as *"working
  assumption ~4 — tune on device"* and it was never tuned. After this change it bounds concurrent
  transfers and staged temp-file disk, nothing else.
- **A silent path to a permanently-useless cursor.** If `NSKeyedArchiver.archivedDataWithRootObject`
  returns nil **without throwing**, `IosDiscovery.archiveToken` logs nothing and stores
  `ByteArray(0)`; `loadToken()` then returns a non-null empty array forever and every cycle
  full-enumerates with no line saying why. No evidence it has fired, and it is not fixed here — but
  it is an "absence is never silent" hole in the one place where silence looks identical to the bug
  being removed.
