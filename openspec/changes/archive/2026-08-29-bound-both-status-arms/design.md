## Context

The joined screen's health is a conjunction: `SyncHealth.InSync` is published exactly when **both** direction
arrows are hidden, and the download arrow hides when `downloaded >= total`. Three sources feed that conjunction —
the ledger aggregate, the download projection, and the own-device library enumeration.

`sync-status` already groups the first two as *"the cheap local sources"* and requires them read before the
enumeration. But only the ledger has a **staleness** bound: `LedgerCountsPoller` re-reads `aggregates()` every 2
seconds while foregrounded. The download projection is refreshed only by `StatusRefresh`, whose three callers are
`Foreground`, `Provision` and `ReconfigureEvent` — so within one foreground session it is read **once**.

Two defects follow, and they compound:

1. **The entry read races discovery.** `Foreground.run()` launches `refreshStatus()` and
   `downloadController.reconcile()` as siblings. The refresh reads `assetCount()` before the reconcile's union
   fetch (150–1673 ms measured) has returned and planned the burst, so the projection reports counts from before
   the work existed. Under a `LIMITED` grant the refresh skips the library walk and so reliably wins.
2. **Nothing re-reads it afterwards.** The poll covers the ledger only, so that stale value stands for the whole
   session — including a checkmarked "In sync" over an outstanding burst.

The related defect one property over — an *un-read* `(0, 0)` settling the screen — was fixed upstream
(`DownloadProgress.read` / `UNREAD`), explicitly on **both** arms, with the stated reason that *"without this the
defect would simply relocate to the other arm."* Staleness was left on one arm, and relocated exactly there.

**Constraint that shapes the whole design:** the obvious repair — ordering `refreshStatus()` after `reconcile()` —
is forbidden. `sync-status` requires the foreground refresh not to be sequenced behind its siblings, with a
measured 774 s discovery walk (`SNAPSYNC-16`) behind that requirement. So the race can only be repaired by a
**later re-read**, never by re-ordering.

This change came out of an investigation that started from a different hypothesis (an unbounded per-wake import
drain). On-device measurement dissolved that framing; the status staleness is the defect that survived it.

## Goals / Non-Goals

**Goals:**

- Bound the staleness of the **whole** conjunction the screen renders, not one of its two inputs.
- Repair the foreground entry race without violating the not-sequenced-behind-siblings requirement.
- Make the download projection's counts internally consistent by construction, as the ledger's already are.
- State the cadence honestly: what it bounds, that it is chosen rather than derived, and that it holds only
  while foregrounded.

**Non-Goals:**

- **Changing what the screen renders from a given projection.** The health rules, arrow derivation and the
  un-read/counted distinction are untouched. This change is about *when the numbers are re-read*.
- **Serialising photo-library imports.** That came from the same investigation and is a separate change with a
  separate justification (diagnostic honesty); it is not needed for this one and is not included.
- **Any harness change.** See D9.
- **Any iOS shell change.** The poll's lifecycle stays the `Foreground`/`Background` flows'.
- **Re-tuning `ReceiptDeadlines`,** bounding a per-wake import slice, or adding convergence machinery — all
  considered during the investigation and dropped as unevidenced.

## Decisions

### D1 — One bound over the pair, not one per source

A conjunction is only as fresh as its **stalest** operand, so bounding one arm does not half-bound the screen —
it leaves the screen unbounded through the other. Two independent bounds would still force a reader to reason
about `min(b₁, b₂)` to say anything about what a member sees, which is the pair again with extra steps.

*Alternative — a second poller in `feature/download`, each feature owning its cadence.* Rejected: the bound is
not either feature's property, it is the **screen's**, and it exists to bound a user-visible falsehood over a
value neither feature owns alone. Assigning it to one feature was defensible when one feature fed the line; two
do now. Two loops would also wake every 2 s on the same lane for a number only meaningful jointly.

### D2 — The pair becomes its own requirement, rather than widening the poll's

The "cheap local reads" grouping is currently an ordering convention restated in two requirements and
implemented in a third place. Making it a requirement states it once and attaches **both** joint properties —
ordering and freshness — to the thing they are actually properties of.

*Alternative — widen `Foreground-gated ledger-counts poll` in place and leave the ordering sentence where it is.*
Smaller (one requirement touched instead of three), but leaves the pair defined in two places, so a third cheap
source must be remembered twice. Rejected on that; the cost is one added requirement and a pointer in the
not-sequenced requirement, which keeps its measured rationale intact.

### D3 — Keep the 2 s poll; do not make the download projection reactive

An event-driven projection is genuinely **available** here, and for a reason that does not hold on the other arm:
the download store has a single writer, enforced at compile time (the extension links it only through the
narrowed read-only `SuppressionSource`). A SQLDelight reactive query would therefore be sound, exact, and free of
an invented cadence.

Rejected anyway, on two grounds:

- It **dissolves the group**. The freshness property stops being joint — one member live, one polled — which
  removes the very symmetry D1 and D2 are built on, and reduces the new requirement to ordering alone.
- It **changes the port shape**: `DownloadStore` would expose a Flow, with the in-memory fake and both driver
  contract tests in `:test:world` following, plus a damping constant to absorb the emission rate during a burst
  (~400 writes in ~30 s) — relocating the cadence somewhere less visible rather than removing it.

Explicitly **not** rejected for cost of background emissions. That objection was raised during exploration and
measured to be negligible (tens of milliseconds of CPU across a whole burst); it should not be cited as a reason.

### D4 — A Darwin notification is not an alternative on either arm

For the **download** arm there is nothing to notify across: the store's single writer is the app process itself.

For the **ledger** arm, a cross-process Darwin ding is what the poll replaced. Its history is recorded: posted
per `put` (2026-06-19), reduced to once per `process()` cycle when the per-put volume became a storm
(2026-06-26, whose design notes *"missed dings are designed-harmless"*), then deleted with its
`CFNotificationCenter` observer, `staticCFunction` bridge and per-foreground register/unregister choreography.
The load-bearing property is that a Darwin notification is **best-effort**: it always required a re-read behind
it, so it can only sit in front of a poll, never replace one — leaving two mechanisms for one number.

### D5 — The projection's counts collapse into one read

`LedgerCounts` comes from a single `aggregates()` round-trip *"so they are mutually consistent"*. The download
projection took three separate reads. Grouping the two under one requirement makes that asymmetry a thing the
spec would have to explain.

*Alternative — state the invariant instead.* The torn read is provably safe today: `IMPORTED` is terminal, every
imported row is counted in the denominator, and every row that becomes imported was already counted there while
pending — so `importedCount() ≤ assetCount()` under any interleaving, in **either** read order, and a torn read
can only understate completeness. Rejected because that invariant is **unenforced**: nothing tests it, and D8 of
the terminal-unimportable change (excluding `UNIMPORTABLE` from the denominator) is exactly the class of edit
that could break it — surfacing as a wrong arrow with no test naming the cause. One read makes the question
unaskable rather than answered.

### D6 — The first tick waits one cadence, for the opposite reason

The rule is unchanged; its stated reason is now false. *"Because foreground entry already refreshes the status
sources"* assumes that entry read is good. Under the entry race it is precisely the bad one. The first tick is
what **repairs** it, and one cadence comfortably exceeds a typical union fetch; a slower fetch is caught by the
next tick.

An immediate first tick was considered and does not help: it would fire before the fetch returns and read the
same stale value.

### D7 — The cadence is chosen, not derived, and carries no expiry trigger

2 s is inherited from the ledger poll and nothing about downloads argues to change it. What it bounds is **how
long the screen may assert something false** — not counter smoothness, which would want ~200 ms during a burst
and is not a correctness property.

The number is stated as chosen for human perception. It carries **no expiry trigger**, unlike `ReceiptDeadlines`,
because no measurement would falsify it — and inventing a trigger nobody can act on is worse than admitting
there is none.

### D8 — Per-tick cost is stated by class, not by counting reads

The old text — *"Each tick is one local, read-only `aggregates()` read"* — justified the cadence as harmless by
counting. Under the group that count is wrong, and it would be wrong again for any future member. The
requirement states the class instead: local, read-only, no network, no storage LIST, never the enumeration.

### D9 — Coverage is an integration test; the harness gets no new control

The world harness calls `refreshStatusSources()` after **every** operator action, so the download line is never
stale there. It therefore cannot exhibit this defect class, and would have shown correct behaviour while the bug
was live. That is a consequence of *"the operator plays the OS, nothing auto-runs"*: time is the one OS input the
operator cannot currently supply.

Coverage therefore lands in `:test:integration`, on a `TestScope` so `delay(cadence)` is virtual: plan foreign
assets, do **not** refresh, advance time, assert `UiState` leaves `InSync`. That asserts the defect rather than
the mechanism, and is deterministic with no real wait.

*Alternative — a "tick the poll" control in the world harness,* letting the operator play time as they already
play `process()`. It would close the false-reassurance gap and fits the harness's philosophy, but it is a
`full-stack-harness` delta riding on a `sync-status` fix. Deliberately excluded; recorded here so the gap is
known rather than forgotten.

## Risks / Trade-offs

- **The harness still cannot demonstrate this defect class** → the integration test is mandatory rather than
  nice-to-have, and D9 records why the harness is silent here so the next reader does not mistake a green harness
  for coverage.
- **The entry race is repaired late rather than prevented** → bounded at one cadence, and prevention is
  unavailable: re-ordering is what `Foreground status refresh is not sequenced behind the upload pump` forbids,
  for a measured reason.
- **A requirement carrying a shipped regression is being edited** → the not-sequenced requirement keeps its
  774 s rationale and all its scenarios verbatim; only the ordering sentence moves to the group, with a pointer
  left in its place.
- **The poll now touches two stores per tick** → each member is one round-trip after D5, all local and read-only,
  and the cost is stated by class so a future member does not falsify the requirement.
- **Renaming `LedgerCountsPoller` and its requirement** touches a `commonTest` and the two flows that drive its
  lifecycle → mechanical, and the rename is what stops the name lying about what it ticks.

## Migration Plan

None. No schema change, no persisted state, no wire format. The download store gains a combined counts read that
replaces three existing reads; the rows it counts are unchanged. Rollback is reverting the change.

## Open Questions

- **Should the world harness invoke the real `Foreground` flow** rather than reaching past it to
  `refreshStatusSources()`? It would inherit the poll's lifecycle for free and close the gap in D9, but it is a
  fidelity question well beyond this change and would make the harness less deterministic.
- **Will a third cheap local source ever join the group?** D2's main benefit over the cheaper alternative is that
  such a source joins by definition. If none ever appears, the extra requirement bought only a single definition.
