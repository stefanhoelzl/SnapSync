## Context

The adjudication guard already exists and is otherwise correct: before creating an asset for a ref that
carries a marker, the client asks the photo library whether that asset exists and acts on the answer. What
it lacks is a reason to distrust **absent**.

Under a full grant the spec calls absent authoritative. That is true of the library and false of the
question we are asking it. `PHPhotoLibrary` answers about **committed** state, so while a
`performChanges` transaction is open the asset genuinely is not there — and an import whose wait was
abandoned on its deadline is exactly that case. `download-store` already protects the marker from the
*importer* on that path ("A marker SHALL NOT be cleared when the import's wait is abandoned"), but the
adjudicator clears the same marker seconds later on the answer the open transaction produces.

Two device facts make it routine. Imports legitimately take 1.0 s at 49 MB and 5.2 s at 197 MB (SE2; the
reporting device runs ~2× slower), so the 5 s bound fires on healthy work. And iOS suspends the process for
arbitrary spans between a change block and its completion — 116 s and 254 s measured — so the bound expires
against transactions that are alive: in the field it fired 3–43 ms after resume, having been due 116 s
earlier.

There is a superseded attempt at this on `parked/settle-imports-by-transaction`. It generalised the fix
into a registry serving selection, adjudication and quiescence at once. Review established that one set
cannot answer the third question — membership begins at the import's claim, so work that has been launched
but not yet claimed is invisible to it — and that its flagship test could not observe the duplicate it
claimed to prevent. That history is why this design fixes the reported defect and nothing else.

## Goals / Non-Goals

**Goals:**

- An *absent* verdict is never acted on while the ref's import outcome is unreported.
- A *present* verdict cannot overwrite a marker the row no longer holds.
- An import whose wait was abandoned settles itself when the library eventually reports, without needing a
  later adjudication pass.
- The import deadline stops abandoning healthy work.

**Non-Goals:**

- Removing the controller's lock from around the platform call (the `SNAPSYNC-6` field hang). The lock
  stays, and the deadline that bounds it stays with it.
- Holding OS wake handlers until in-process imports finish.
- Reclaiming the staged bytes of settled rows.
- Distinguishing "a transaction is genuinely open" from "we stopped waiting". We record only the second,
  which is a superset, and a superset only ever makes adjudication more cautious.

## Decisions

**D1 — The gate is a reported/unreported fact, never a clock.** A ref is unreported from the moment its
wait is abandoned until the library reports its outcome. Rejected: bounding the distrust by elapsed time.
A wall-clock bound does not bound work in a process the OS can freeze — that is the same mistake the 5 s
deadline made, and applying it to the guard would reproduce the defect one level up.

**D2 — The record is in memory, and its erasure is a feature.** A durable flag would survive the process
that owned the transaction, and a ref marked unreported by a process that no longer exists would be
distrusted forever, so its photo would never arrive. A transaction cannot outlive its process, so after a
relaunch every absent verdict is trustworthy again — which is precisely when the guard should do its
original job. Rejected: persisting it, for that reason.

**D3 — One reader, deliberately.** The record answers exactly one question: *may this ref's outcome still
arrive?* It is not consulted for import selection — a row carrying a marker is already excluded from
importable work, which is an independent and durable fact — and it is not consulted for wake quiescence.
The parked design's one-set-three-readers arrangement is what let a set whose membership begins at claim be
used to answer a question about work that had not been claimed. Naming the type for its single reader
(`UnreportedImports`) is part of the decision, not decoration: the previous name invited the second reader.

**D4 — The controller records; the adapter clears.** The controller observes the wait being abandoned
(`ImportResult.TimedOut`) and lives in tested `feature/` code, so it records. Only the platform's
completion block learns the outcome afterwards, so it clears, through an injected lambda mirroring
`recordCreatedLocalId`. Each side writes only what it can observe. Rejected: putting both writes in the
adapter, which would hide the fact behind a port from its only reader; and both in the controller, which
cannot observe a completion that arrives after it stopped waiting.

**D5 — The completion writes the row's terminal state.** It is the party that learns the outcome and it
runs whether or not anything is still awaiting it, so an abandoned wait settles itself. Without this, such a
row is settled only by a later adjudication pass, which costs a synchronous, thread-blocking library
round-trip. The write is guarded on the marker the row still holds, so a completion arriving after the row
moved on cannot settle it against an identifier it no longer describes.

**D6 — The deadline rises to 30 s, and its cost is stated.** ~10 s is the measured legitimate worst case, so
30 s is ~3× headroom while still bounding the lock. It bounds the *lock*, so the pathological case now
blocks reconcile, leave and switch for 30 s instead of 5 s. Accepted because the alternative — a 5 s bound
that fires on healthy imports — is what manufactures the unconfirmed rows this defect mis-adjudicates.
Removing the lock instead is the follow-up change, not this one.

**D7 — *Present* is re-checked before it is applied.** Verdicts are computed outside the lock, deliberately,
and applied under it; a row can settle in between. Recording an import against a marker the row no longer
holds overwrites a live suppression handle with a stale one — the same harm by a different route.

## Risks / Trade-offs

- **A completion that never arrives leaves a ref unreported for the process's life** → That photo is not
  re-imported until the next launch. Conservative in the right direction: the alternative is uploading
  someone's photo back into their event. A *present* verdict still settles the row, so the ordinary
  recovery path is unaffected.
- **A 30 s lock hold blocks reconcile, leave and switch** → Only when the library stalls, and the wake's own
  budget is comparable. The follow-up change removes the lock from the platform call.
- **The test fakes can hide the defect they exist to prove** → The world importer must mint a **distinct**
  created identifier for every asset it creates, on every path including after a failure, and the tests must
  assert on the number of assets created rather than only on the marker. The parked branch's flagship test
  passed while the duplicate was being created, because its fake reused one identifier.
- **The device is the only oracle that has not lied here** → Acceptance requires an on-device reproduction
  of an abandoned wait, produced by a throwaway probe that is never committed.
