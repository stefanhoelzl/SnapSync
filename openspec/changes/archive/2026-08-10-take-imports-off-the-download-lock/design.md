## Context

`DownloadController` serializes its store-mutating flows with one mutex, and the per-asset import runs
inside it. The lock's *span* is doing two jobs at once: it makes the store writes atomic, and it provides
the mutual exclusion that stops two triggers both finding one asset importable. The second job is why the
platform call is inside it — and the platform call is a synchronous, thread-blocking, unabandonable one, so
a stalled photo library takes the whole controller with it. In the field (`SNAPSYNC-6`) the lock was held
from 09:03:37 until the process died; every reconcile, import, leave, switch and `onResourceStaged` in that
process queued behind it permanently.

`gate-absence-on-unreported-imports` raised the import deadline from 5 s to 30 s and kept the lock,
sixfolding the exposure, because the 5 s bound was firing on healthy imports (SE2: 1.0 s at 49 MB, 5.2 s at
197 MB; the reporting field device runs ~2× slower) and every expiry manufactures an unconfirmed row for
the adjudication guard to reason about. Its D6 named this change as the repair.

There is a withdrawn attempt at this on `parked/settle-imports-by-transaction`. It generalised the mechanics
into one registry serving selection, adjudication **and** wake quiescence, and its KDoc celebrated the
unification. The unification was the defect: membership began at the import's *claim*, so work
launched-but-not-yet-claimed was invisible to it, and a wake could report itself finished with imports
pending. Four reviewers missed it for four rounds, partly because the KDoc told every reader the design was
deliberate. Its mechanics are sound and are reused here; its arrangement is not.

Two facts already on `main` shape what this change does **not** have to build. `OsReceipt` bounds every OS
completion handler and releases it on expiry while letting the work run on, so the wake is already bounded
without a per-import clock. And the drain is *awaited inline* by every trigger, so "the wake's imports
complete before its handler is released" holds with no registry — which is why the parked design's third
reader has no counterpart here.

## Goals / Non-Goals

**Goals:**

- A stalled photo library blocks no reconcile, import, leave, switch or `onResourceStaged`.
- Two triggers can never both create an asset for one ref.
- A single non-reporting import strands no other ref, and a permanently-failing asset cannot live-lock the
  drain.
- No marker write depends on the lock's span to be safe.
- A marker write that lands on no row is visible from the field.

**Non-Goals:**

- Changing how the photo library is asked about presence, or the grant-dependent answer it gives.
- Holding OS wake handlers differently (`OsReceipt` and its deadlines are untouched).
- Cancelling in-flight imports on leave or switch.
- Bounding a single import in time. Nothing replaces the deleted deadline; the wake's bound is the only one.
- Serializing imports of distinct refs.

## Decisions

**D1 — The lock covers the DECISION, never the WORK.** Selection, the claim, the staged-resource read and
every store write run under the mutex; `PhotoLibraryImporter.import` is the only thing outside it. Reading
the staged resources *under the same acquisition as the claim* is deliberate: it leaves the platform call
alone outside, so there is no second cause to reason about when a coroutine leaves that region. The parked
branch read them outside and had to document a "deliberate collapse" absorbing a cancellation that arrived
before any transaction existed; this shape has nothing to collapse.

**D2 — Mutual exclusion is a per-ref claim, private to the controller.** A `MutableSet<AssetRef>` field,
mutated only under the mutex. Rejected: a named class (`ImportTransactions`, the parked branch's shape) — a
public type is a thing a future change can inject somewhere new, and the containment here is the point.
Rejected: a durable claim in the store — a claim that outlives the process that owned the transaction is
never released, and that photo never arrives (the same reasoning as the prior change's D2).

Because no platform callback writes it, it needs no `MutableStateFlow` and no CAS — unlike
`UnreportedImports`, which was written from the OS's queue. That is a simplification, not an oversight.

**D3 — Three readers, all asking about THIS ref; the fourth is excluded by construction.** Selection ("may I
start an import for this ref?"), adjudication's *absent* gate ("may this ref's outcome still arrive?"), and
`pruneNonTerminal`'s `protecting` ("may this row's marker write still land?"). Each is safe on a
**superset** of "a transaction is genuinely open": a claim taken before the change block runs only ever
makes selection stricter, adjudication more cautious, and the prune more protective.

The parked design's fourth reader — quiescence — asked about *the wake*, not about any ref, and no superset
argument covers it: work launched but not yet claimed is outside the set while being exactly what the
question is about. It cannot appear here. The field is private, so no other module can reach it; and its
question already has an owner, in the inline await of the drain plus `OsReceipt`. That ownership is pinned
by a test, so nobody has to rediscover where the answer lives.

Rejected: naming the type for one reader and adding a second set for the others. With no deadline the two
sets' memberships are near-identical (claim → the library reports), so they could only drift apart, never
usefully disagree — which is the failure the prior D3 warns about for duplicated in-memory gates.

**D4 — Claim ONE ref at a time, with an attempted-set.** Claiming the whole importable batch up front makes
a single non-reporting import strand every other ref in it: they stay claimed, so no later pass selects
them, and recovery moves from "the next wake" to "the next process launch". The attempted-set is required
separately: a `Failed` import leaves its row importable *and* releases its claim, so without it the loop
re-selects the same ref forever, spinning on any permanently bad resource. The batch form could not reach
that, because it iterated a fixed list; the per-ref form must say so.

**D5 — The claim releases when the library reports, and is HELD on cancellation.** Released after
`import()` returns — on every outcome — and in adjudication's *present* branch, which is what recovers a ref
whose completion was never delivered (without it, a lost completion pins the ref until relaunch; the
library saying the asset exists is proof the transaction landed). Held on cancellation: with no deadline,
cancellation is the only way a coroutine leaves with a possibly-open transaction, and releasing there
re-opens exactly the window this change closes. No caller cancels these today, so the case is a structural
backstop rather than a live path.

The release sits **after** the post-import store writes, in the same acquisition. Releasing first would let
the row move on between the release and the write, which is precisely what D9 relies on not happening.

**D6 — The import deadline is deleted.** Its own KDoc states its job: *"What this really bounds is the
LOCK."* Once the lock does not span the platform call, the remaining consumer would be the wake — and the
wake is already bounded by `OsReceipt`, which releases the handler on expiry and deliberately does not
cancel the work. Keeping a 30 s per-import clock on top would re-state the mistake D1 of the prior change
named: a wall-clock bound expires against transactions that are alive, because the process is suspended for
arbitrary spans between a change block and its completion (measured 116 s and 254 s). Rejected: lowering the
value, which re-manufactures the unconfirmed rows the raise to 30 s existed to stop.

`ImportResult.TimedOut` goes with it. A stalled import now blocks its own trigger's remaining drain and
nothing else, which is strictly better than the rule it replaces ("stop the drain") — that rule existed to
avoid abandoning one transaction per remaining asset, and nothing is abandoned any more.

**D7 — `UnreportedImports` retires; the claim answers its question.** The deadline was its only writer.
What made *absent* untrustworthy had two disjoint causes — a wait we abandoned, and an import running in
this process whose change block wrote a marker the commit has not landed. Deleting the deadline removes the
first; the second is exactly claim membership, and it is also the field signature: `SNAPSYNC-9`'s clears
landed **9–44 ms after creation**, which is a live transaction, not an expired wait.

This supersedes four of the prior change's decisions, named rather than left to be inferred: **D3** (one
reader), **D6** (the deadline), **D4** (the controller records, the adapter clears — both writers die with
the type), and **D7** (*present* is re-checked before it is applied — D8 below replaces that caller-side
re-check with a guarded write, and this change REMOVES the store requirement D7 created). Its **D1** (the
gate is a fact, never a clock) and **D2** (in memory, and its erasure load-bearing) survive intact, now
carried by the claim; **D5** (the completion writes the row's terminal state) is untouched.

⚠️ **D2's inherited premise is FALSE, and it was measured rather than argued.** The prior change stated
that *a `performChanges` transaction cannot outlive the process that opened it*, with no forcing proof.
Measured on the SE2 (iOS 26.5.2, 2026-08-09, 48 MB / 36 MP asset): a probe scheduled a SIGKILL 200 ms after
the change block returned — the transaction submitted to out-of-process `photolibraryd`, the commit in
flight, no completion delivered, the app's log ending mid-import. **The asset was in the library
afterwards.** A commit survives the death of the process that opened it.

The safety of this design therefore does **not** rest on that premise, and the reasoning is restated here
rather than left to inherit a false one. What actually protects the post-relaunch path is the *present*
branch: the relaunched process found the row unconfirmed, asked the library, got **PRESENT**, and settled
the row against the marker it already held — `adjudicated BIGASSET…: asset 362E1E97… exists — settled, not
re-imported`, with zero second imports and zero duplicates. A surviving commit is not a hazard; it is the
case adjudication was built for.

The residual hazard is narrower and precisely stateable, and it is now **pinned by a test** rather than
described: a relaunch adjudicating **while a surviving commit is still in flight** sees *absent*, clears the
marker, re-imports, and leaves the first copy in the library unsuppressed — where upload discovery sends it
back into the event (`a_surviving_commit_still_in_flight_at_relaunch_is_the_accepted_residual`). That is
`SNAPSYNC-9`'s harm, reached through the guard built to prevent it.

It is accepted rather than fixed because **no sound fix exists at this layer**. After a relaunch, "the
commit failed" and "the commit is still coming" are the same observation; distinguishing them requires
either waiting (a clock, which D1 forbids, and which expires against live transactions — the mistake this
whole capability exists to undo) or never acting on *absent* at all (which loses a photo whose commit
genuinely failed, permanently, since cross-event dedup blocks every later attempt). The bounded duplicate is
the same trade `photo-download` already takes for a deleted photo, which it "resurrects at most once". A
real fix would need the store to hold **more than one** created-asset handle per ref, so a cleared marker
could be restored when a later *present* reveals it — a schema change, and out of scope here. That needs commit duration to exceed
relaunch-to-adjudication latency. Measured here: kill→adjudication was 64 s against a ~1 s commit, and the
floor for that latency on this hardware is ~5 s (≈4 s `dvt launch` + ~0.3 s to the first reconcile), so a
48 MB asset cannot reach it. ⏰ Re-measure with a resource large enough to commit for >10 s (several hundred
MB), which is the only shape that could close the gap. Attempted 2026-08-09 with a 199 MB asset and
blocked by the dev rig rather than by the design: the local backend serves objects whole (no Range) and a
cloudflared quick tunnel cannot deliver that to the device, so the import never started. What is untested
is the **exposure** — how often a real event carries an asset committing longer than a relaunch — not the
behaviour, which `a_surviving_commit_still_in_flight_at_relaunch_is_the_accepted_residual` pins.

**D8 — Marker writes are addressed by what they expect to find; check-then-act is removed.**
`confirmCreatedLocalId` is already guarded on the marker, and its own comment says why: *"in the `WHERE`
clause rather than in a caller's `if`, because two writers reach this with no shared lock."*
`clearCreatedLocalId` — reached from the same OS-queue callback — is not. It gains the expected marker, a
`state != 'IMPORTED'` guard and a result.

With both writes guarded, adjudication's two under-lock re-checks have nothing left to do, and
`isUnconfirmedWith` loses every caller and is deleted. This is the same thesis as D1, one layer down: the
controller stops making the lock cover the platform call, and the store stops depending on the lock to make
its writes safe. Adjudication's correctness currently rests on "the lock is held between the check and the
write", which was already false with respect to the platform callback — the reason the prior change needed
two hard-won re-checks and a device run to find the third.

**D8a — a throw leaves the claim held, and there is no catch-all.** `importOne` catches nothing. A throw or
a cancellation means this coroutine is gone with no evidence about whether a transaction was submitted, so
the claim is retained and the throw propagates. Rejected: releasing on a non-cancellation throw on the
reasoning "no change block was reached" — the port's contract does not promise that, and an importer that
raised *after* `performChanges` would have its live marker cleared by the next adjudication, which is
`SNAPSYNC-9` through the guard built to prevent it. It is also the same inference this design refuses one
line earlier for cancellation. A catch-all was written first and measured to swallow the test fake's
live-lock assertion, so removing the drain's attempted-set hung the suite instead of failing it and the
mutation could not be revert-proofed at all.

**D9 — `recordCreatedLocalId` stays unguarded, and becomes loud.** It **creates** the addressing the other
two match; there is nothing to guard against. Enumerated: a row that is `IMPORTED` is unreachable (selection
excludes those, and the claim stops a second concurrent import; a change block from a dead process cannot
run), and a row carrying an older marker is unreachable as harm (such a row is not importable until
adjudication clears that marker, and clearing it *is* the decision that no asset exists for it). A guard
would protect against neither and would read as load-bearing to the next person.

The real defect is the silence. If the update matches no row — the failure `protecting` exists to prevent —
the marker is never written, the created asset has no suppression handle, and it is uploaded back into the
event days later with nothing anywhere recording why. The store therefore reports whether the update landed,
and the adapter logs `Error` when it did not, which reaches Bugsink. That makes `protecting` a safety gate
whose failure is observable in the field rather than inferred from its consequence.

**D10 — `markImported` stays.** It is not redundant with `confirmCreatedLocalId`: it can establish the
marker *and* terminalise a row in one write. Delete it and an importer that skips the in-block marker write
stops being harmless and becomes an unbounded duplicate generator — `confirm` matches nothing, the row stays
importable, and every wake creates another library asset while logging success. That path is live today:
`FakePhotoLibraryImporter.recordCreatedLocalId` **defaults to a no-op**. Its unguarded write is safe for a
reason that is crisp rather than hand-waved: the caller still holds the claim, so nothing can have moved the
row. (That default is removed regardless — it is the last permissive default on a safety-relevant lambda in
the tree, and D10 is what it was hiding.)

**D11 — The prune protects claimed rows, returns what it stranded, and the reset goes through the lock.**
`pruneNonTerminal(protecting): List<String>` in one transaction. `protecting` is needed only now: a ref can
be claimed *before* its change block runs, so rows exist with no marker yet — today a prune takes the lock so
it cannot race an import, and an abandoned wait's row always carries a marker, which the prune already
spares. One transaction rather than read-then-prune because the store is mutated between two reads by
writers that structurally cannot take the caller's lock (the marker writes are non-suspending because
PhotoKit's blocks cannot call a suspending function), and a marker cleared in that gap turns a protected row
into a deleted one whose files are then orphaned unreclaimably across launches. `protecting` carries no
default: a permissive default on a safety gate is how a caller ships without one, which is how the reset path
shipped unprotected on the parked branch while the parameter existed.

`ResetDeviceState` therefore takes an injected `resetDownloads` suspend effect, bound in `compose/` to a
controller method that holds the lock — reading the claimed set as a *value* and pruning later leaves a
window for a claim in between. Taking the critical section rather than the value also keeps the membership
feature blind to its sibling.

**D12 — Fixtures must be able to observe the harm.** The world importer already mints a distinct created
identifier per created asset, on every path including after a failure, and tests assert on how many assets
exist rather than only on a marker's value — the bar the parked branch's flagship test failed. Two additions:
a lever that **suspends** after writing its marker and resumes with a test-chosen outcome, replacing the
`TimedOut`-shaped abandonment levers (it models the live transaction rather than a report about one, so a
test can drive concurrent triggers while the transaction is genuinely open); and an attempt cap that
**throws**, so the attempted-set's removal fails with "imported 50 times, expected 1" rather than hanging —
a hang is not a proof.

**D13 — Leave and switch do not cancel a claimed import.** The transaction may still commit, and cancelling
it re-opens D5's window. Its row survives the prune via `protecting`, settles afterwards, and remains as a
permanent suppression handle. The consequence is accepted and stated: a leave no longer fully cleans, and one
row outlives it. That is correct — the photo *is* in the library, and the handle is the only thing keeping it
out of the upload universe.

**D14 — the claim's read placement is belt-and-braces here, and that is a change from the prior design.**
The gate is read under the lock — the placement the prior change's device run established — but for a
different reason. `UnreportedImports`' membership began LATE (at the timeout), so a read taken before the
lock could be stale-*false* and permit a clear; that is the defect the device found. A claim's membership
begins EARLY, before the change block writes the marker, so by the time a row is unconfirmed its ref is
already claimed and the gate can only go true→false during adjudication. A stale read is therefore
conservative, and the staleness hazard is structurally absent. The under-lock read is retained because the
claim is a plain `MutableSet` governed by that lock, so reading it from outside is a data race — a
memory-safety property, not a behavioural one, and one no JVM test can demonstrate. Stated because a
mutation moving the read out of the lock **survives the suite**, and a reader who found that later would
reasonably conclude the placement was unmotivated.

**D15 — a requirement that landed mid-flight is corrected, not worked around.** `fix(ios): put both
background-session handlers back inside a bounded receipt` shipped to `main` while this change was open and
added a `photo-download` requirement citing the import deadline as what stops a single import holding the
controller's lock. This change deletes both the deadline and that lock, so the sentence is false on merge.
It is rewritten in place rather than left to rot: the requirement is otherwise complementary, and a
rebase that merely kept both would have compiled, passed every gate, and left a contradiction in the
contract of record — which is exactly the drift the archive gates exist to catch and which git reports only
as "two requirements appended at the same spot".

## Risks / Trade-offs

- **No per-import bound exists anywhere afterwards** → A library that never reports costs that ref one
  leaked continuation and one permanent claim per process, recoverable by relaunch or by a *present* verdict.
  Accepted: the bound's stated job was protecting the lock, and the handler is already bounded by
  `OsReceipt`. Visibility is preserved by `Logger.invocation` around the import, so a dump shows an import
  that entered and never exited.
- **The claim is a multi-reader set, which is the withdrawn design's shape** → The defence is structural
  (private field, no type, no public API) plus a test that pins where the excluded reader's question is
  actually answered — deliberately not an argument, because the argument is what failed last time.
- **Imports of distinct refs now run concurrently** → No test can validate PhotoKit under that; it is why the
  device acceptance includes an unprobed large-import burst alongside the stall probe.
- **A requirement shipped one day earlier is deleted** → `UnreportedImports` and the bounded-import
  requirement go. Stated as supersession of specific decisions (prior D3, D6) rather than of the whole
  change, because prior D1 and D2 are load-bearing here.
- **The change touches four capabilities at once** → Kept as one change because `protecting` has no
  meaningful caller until the claim exists; splitting it out would ship a required parameter every caller
  satisfies vacuously, which is the same defect wearing a stricter type. The store half's mutations are all
  provable by deterministic contract tests, so review effort concentrates where the risk is.
- **The test fakes can hide the defect they exist to prove** → D12, and the mutation bar: every behavioural
  fix is revert-proofed in an isolated worktree and a kill must name a failing test, never a compile error
  and never a hang.
- **A never-returning trigger leaves the ambient log prefix set for the life of the process** → `LogScope`
  is "outermost wins" over a process-global, so the trigger that owns the context (`importReady`,
  `onResourceStaged`) never runs its `exit` if its drain never returns, and every later line in the dump is
  prefixed with that entry point. A consequence of removing the deadline rather than of any code here, and
  it degrades exactly the dump you would read to diagnose a stall. Accepted for now — the process is
  normally suspended or killed shortly after — and worth a follow-up that scopes the context per coroutine.
- **`recordCreatedLocalId` returning `false` is reported and then the import proceeds** → The asset is
  created with no row and no suppression handle, and the `Error` line is the only record. Defensible only
  because `protecting` makes it unreachable; if it ever fires, the log is the whole safety net.
- **A surviving commit racing a fast relaunch is untested** → The one path where adjudication could still
  clear a live marker. Bounded by commit-vs-relaunch latency, negative for a 48 MB asset on an SE2, and
  unmeasured for the several-hundred-MB case that could invert it.
- **The device is the only oracle that has not lied here** → The merged change's *first* device run
  reproduced the original defect against its own fix, because a gate was read outside the lock and went stale
  while waiting for it. Acceptance requires an on-device reproduction driven by a throwaway probe that is
  never committed.

## Migration Plan

No data migration: `DownloadStore.sq`'s `CREATE TABLE` statements are unchanged and only queries are
added, guarded or removed, so no SQLDelight schema version is involved. Deployment is an ordinary
branch → PR → `/ship`; rollback is a revert, and no persisted state written by this change needs
interpreting by an older build.

## Open Questions

- Whether the loud no-op in `recordCreatedLocalId` should carry the ref in its message. It is scrubbed of
  UUID-shaped tokens before send, so a source assetId may survive scrubbing and reach Bugsink; the line is
  much less useful without it.
- Whether `ReceiptDeadlines.URL_SESSION_EVENTS` (20 s) still wants revisiting once no import can outlive it
  by design. Out of scope here, but the constant's "provisional, re-set from the first field dump" note now
  has one fewer confound.
