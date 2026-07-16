## Context

One line of code, four specs, two answers:

```
  ios-photokit-upload   "reset the discovery cursor … BOTH are required"   ← the code obeys this
  upload-lifecycle      "stop() SHALL NOT clear the discovery cursor"      ┐
  event-rejoin-recon.   "the ledger and discovery cursor are NEVER cleared"├─ all three born of one
  sync-ledger           (same clause)                                      ┘  over-broad sentence
```

All of it — the prohibition, the requirement, and the code — traces to `40a6ee2`
(`fix-app-driven-upload-lifecycle`), which created `upload-lifecycle/spec.md` and
`PhotoKitUploadProducer.kt` in the same commit. Its D1 mandates the cursor clear as "the repair"; its D2
forbids it while reasoning only about the ledger. Neither noticed the other.

## Goals / Non-Goals

**Goals**
- State what `stop()` must actually defend, and let the shipped repair be legal.
- Keep every ledger and stored-bytes clause exactly as strong as it is today.
- Leave `ios-photokit-upload` untouched — it is the artifact that was right.

**Non-Goals**
- **Changing behaviour.** No producer, arm, or cycle changes. Comments only.
- **Mechanizing the prohibition.** Nothing tests it today (see D3); this change does not add a guard,
  because the guard worth having is a different argument (`architecture-guards`) and a different shape.
- **Re-litigating the app-driven tier.** `UrlSessionUploadController.leave()`'s ledger-and-cursor wipe was
  a real bug and is fixed. The prohibition that killed it stays, narrowed to what killed it.

## Decisions

### D1. The invariant is dedup state, not "durable state"

The prohibition lists three nouns — ledger, cursor, stored bytes — as if they were one category. They are
not. Two of them are the *proof that a photo is already uploaded*; the third is a *performance hint about
where to resume scanning*.

| | destroyed → | cost |
|---|---|---|
| `COMPLETED` ledger rows | dedup proof gone | re-uploads the member's whole post-cutoff library into the event |
| stored bytes | the upload itself gone | the photo is lost from the event |
| discovery cursor | resume point gone | **one full re-enumeration**, which finds `0 new` |

The first two are why this prohibition exists — the project's opening premise is that "what was 'back up
everything of mine' becomes 'upload a guest's whole camera roll to a stranger's event'". The third is a
scan. Grouping them made the rule easy to write and wrong.

So the rule now names the property rather than the nouns: `stop()` SHALL NOT destroy **dedup state**. The
cursor is carved out *because* it is not dedup state, and the carve-out carries that reason with it, so the
next reader can tell whether a new piece of state falls inside or outside.

### D2. The exception is conditioned on the invariant, not on the tier

The obvious carve-out is "except the PhotoKit tier". Rejected: it makes the rule a list of names, and the
next tier with the same OS-shaped problem has to amend the spec to do the same correct thing.

The condition is what actually makes the PhotoKit clear safe:

> A tier's `stop()` MAY clear its discovery cursor **only** as a repair for damage its own mechanism causes,
> and **only** where `COMPLETED` rows survive so nothing already stored re-uploads.

Both clauses do work. The first excludes clearing a cursor for convenience — the app-driven tier's wipe was
not repairing anything, it was tidying, and it stays forbidden. The second is the safety property: the clear
is cheap *because* dedup lives elsewhere, so a tier where that stopped being true could not invoke this.

### D3. Nothing tested this, and this change does not fix that

`UploadArmTest:150` asserts "The seam exposes no way to clear the ledger or the discovery cursor" — a test
of the *seam's shape*, over a `FakeProducer`. It passes whether or not a real producer clears anything, so
it never contradicted the code and never will. The prohibition was false for four days and nothing went red.

That is worth recording but not worth fixing here. A guard would have to reach into a tier's producer and
assert what it does to App-Group state — `:test:architecture`'s Konsist can see calls, so a rule like "no
`removeObjectForKey(DISCOVERY_TOKEN_KEY)` outside a producer's `stop()`" is conceivable. It is a different
capability and a different argument, and pinning the *old* rule would have pinned the wrong thing anyway.

### D4. Why not fix the code instead

Deleting the cursor clear from `PhotoKitUploadProducer.stop()` would make three specs true without touching
a word. It would also reintroduce the bug `clear-requested-on-disable` fixed, which that spec spells out:

> `clearRequested()` only makes the keys *absent*, but a settled cursor scans incrementally and would never
> re-surface them — so without the cursor reset the cleared photos are re-discovered only when the library
> next changes.

So the "fix" would leave a member's photos undiscovered until they happened to take another one. The specs
are the artifact that is wrong, and they are wrong in a way that reads as authoritative: a bare "SHALL NOT"
with no stated reason, which is exactly the kind of sentence a later reader obeys without checking.
