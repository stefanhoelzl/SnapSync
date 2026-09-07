# Bound both status arms

## Why

The joined screen can render a checkmarked **"In sync" while a burst of foreign photos is outstanding**, and hold
that claim for as long as the member keeps the app open. The direction arrows are **conjunctive** — "In sync" is
shown exactly when both are hidden — so the composite is only as fresh as its *stalest* input, and only one of the
two inputs is bounded. The ledger aggregate is re-read every 2 seconds by the foreground-gated poll; the download
projection is read **once** per foreground entry, **concurrently with the reconcile that populates it**, and never
again for that session.

Both halves of that produce a wrong screen:

- **the entry read races discovery** — `StatusRefresh` and `DownloadController.reconcile` are sibling launches, so
  the projection is typically read *before* the union fetch returns and the burst is planned. The screen then
  renders counts from before the work existed. Under a `LIMITED` grant the refresh skips the library walk and so
  reliably wins the race.
- **nothing re-reads it afterwards** — the poll covers the ledger only, so the stale value stands for the session.

This is the same defect class the un-read/counted distinction closed one property over. That fix was applied to
**both** arms deliberately, and said why: *"an un-read download projection can carry the whole screen to a settled
checkmark on its own … without this the defect would simply relocate to the other arm."* Staleness was left
asymmetric, and it relocated exactly there.

Ordering the refresh after the reconcile is not available: `sync-status` forbids sequencing the foreground status
refresh behind its siblings, with a measured 774 s discovery walk behind that requirement. A race repaired only by
a later re-read is therefore the shape the fix has to take.

## What Changes

- **Name the cheap local status reads as one group.** The ledger `aggregates()` and the download projection are
  already read together and ordered together; this states them as a group and attaches its two properties —
  **ordering** (before the library enumeration) and **freshness** (bounded as a whole) — to the group rather than
  restating them per-caller. The own-device library enumeration is explicitly not a member.
- **Widen the foreground-gated poll from the ledger alone to the group**, keeping the 2-second cadence. A change
  to *any* member reaches the status projection within one cadence plus one read.
- **Restate the first tick's rationale.** The rule (wait one full cadence) is unchanged, but its stated reason —
  *"because foreground entry already refreshes the status sources"* — is now false in the case that matters: the
  entry refresh is the read that raced discovery. The first tick is what repairs it.
- **Present the cadence honestly.** 2 seconds is chosen for human perception, not derived from a measurement, and
  what it bounds is *how long the screen may assert something false*. It is conditional on the foreground.
- **Collapse the download projection's three counts into one consistent read**, matching the ledger's single
  `aggregates()` round-trip, so both group members are internally consistent by construction rather than one being
  safe by argument.
- **State the per-tick cost by class** — local, read-only, no network, no storage LIST, never the library
  enumeration — rather than by counting reads, which breaks the moment a further cheap source joins the group.

No change to what the screen renders from a given projection: the health rules, the arrow derivation and the
un-read/counted distinction are untouched. This change is about *when the numbers are re-read*, not what they mean.

## Capabilities

### New Capabilities

None. This change bounds an existing surface; it introduces no new capability.

### Modified Capabilities

- `sync-status`: adds the cheap-local-reads group as a requirement of its own (ordering + joint freshness);
  widens `Foreground-gated ledger-counts poll` to the group and renames it accordingly; removes the ordering
  sentence from `Foreground status refresh is not sequenced behind the upload pump`, which the group now owns;
  restates the first-tick rationale and the cadence's provenance.
- `download-store`: the `Unified download store, app-written` requirement names `inFlightCount()` as a distinct
  read; the projection's counts become one consistent read, so that requirement's counting surface changes.
- `ios-app-shell`: its `iOS live composition root` requirement names the poll by class (`LedgerCountsPoller`),
  which the rename retires. A stale reference to a type that no longer exists — caught by the archive's
  dead-types gate, not by anything this change set out to touch.

## Impact

- **`:domain feature/status`** — `StatusRefresh` grows a narrower entry point for the group so the poll and the
  full refresh share one definition of it; the poller ticks that instead of the ledger source directly, and is
  renamed off "ledger counts". Its `commonTest` follows.
- **`:domain feature/download`** — `StoreDownloadStatusSource` reads the projection in one call.
- **`:domain ports`** — `DownloadStore` gains the combined counts read; the three single-count reads it replaces
  are removed.
- **`:adapter:generic:app`** — `SqlDelightDownloadStore` and its `.sq` query.
- **`:adapter:generic:fake`** — `InMemoryDownloadStore`, held to the same contract by `FakeHonestyTest`.
- **`:test:world`** — the `DownloadStore` contract both driver implementations extend.
- **`:test:integration`** — the test that actually asserts the defect is gone: plan foreign assets, refresh
  nothing, and let the real poll at its real cadence correct the screen. `worldTest` runs on real time, so
  this waits out a cadence rather than advancing a virtual clock — which is the stronger test anyway, since
  it exercises the composed wiring end to end.
- **No harness change.** The world harness refreshes after every operator action and so cannot exhibit this
  defect class; that gap is recorded in `design.md` rather than closed here.
- **No iOS shell CODE change.** The poll's lifecycle stays the `Foreground`/`Background` flows'; no file under
  `app/ios/` is touched. The `ios-app-shell` delta above is a spec correction only — that spec named the poll
  by a class name the rename retires.
