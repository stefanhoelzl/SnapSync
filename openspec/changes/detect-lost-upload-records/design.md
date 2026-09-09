## Context

The reconcile that could catch this already exists and is gated shut on ordinary cycles:

```
UploadReconciler.reconcile(configuredEventId):
    if (configuredEventId == marked) return true      ← every settled cycle stops here
```

Behind that gate it does two things at once: it **seeds** rows from the listing (restoring dedup after a
reinstall or a switch) and it **re-baselines** wholesale via `resetTo`. Both are appropriate when the
ledger is known-divergent. Neither is appropriate on a settled device.

The download arm already has the shape this change wants. `Foreground.run()` calls
`downloadController.reconcile(…)` on every foreground — ask the backend what is true, correct the local
view. The upload arm has no equivalent.

The evidence position matters for scoping. A production query found zero discrepancies, but it is blind to
three things by construction: events are swept at 30 days, a rejoin repairs the divergence before anyone
looks, and a retraction removes the declaration that would have revealed it. A device-side check at the
moment of use sees past all three.

## Goals / Non-Goals

**Goals:**

- Establish whether the failure happens at all, on real devices, continuously, at no risk.
- Make the signal trustworthy enough to act on later without re-deriving it.
- Distinguish the tiers, since the structural hypothesis is that iOS ≥26.1 is where it breaks.

**Non-Goals:**

- **No repair.** Not demoting, not seeding, not re-uploading. The moment this writes, it inherits the
  whole design surface it is being shipped to avoid.
- No new backend endpoint, no `ETag`, no delta parameter, no pagination.
- No change to the marker-gated rejoin seed.
- No change to how often the upload cycle runs.

## Decisions

### D1 — Fetch the whole listing; it is small, and the premise that it is not is a fossil

`UploadReconciler`'s timeout constant justifies itself with "the listing can return tens of thousands of
entries on a big library". That was true when this app was a whole-library personal backup. It is not true
of an event-scoped product: uploads are bounded by the membership's capture-date range, at most 30 days.
Measured against production, the largest single device holds **321** resources — about 32 KB of JSON — and
the median holds 20.

So no new backend surface is needed. A count endpoint, an `ETag`, or a `?since=` parameter would all be
optimizations against a cost that is not there, and `?since=` is the wrong primitive besides: the check
needs to detect **absences**, which a delta of additions cannot express without tombstones.

### D2 — Compare the admitted, believed-landed set — not everything the ledger thinks landed

The comparison set is `policy admits the row` ∩ `the ledger records it as landed`. The policy filter is
load-bearing, not tidiness:

- **The sweep** deletes unreferenced bytes below the event's `starts_at` floor. Those are exactly the rows
  no manifest declares, so the policy excludes them. Without the filter the detector would report the
  nightly cleanup as a failure — and production currently holds 217 such resources, all of which would
  fire.
- **Prior events' rows** and out-of-window residue drop out for the same reason.

This is why the change depends on `stop-uploading-excluded-photos`: that change introduces the row-level
admission this comparison needs.

### D3 — Report at `Error`, in counts, because scrubbing forces it

`crash-reporting` routes `Error`/`Assert` Kermit lines to Bugsink as events and lower severities as
breadcrumbs, and scrubs every UUID-shaped token on the way out. So the report cannot carry asset ids or
storage keys even if it wanted to — and does not need to. It carries counts, the resolved upload
mechanism, and the sizes of both sets; Bugsink's own context supplies the OS version.

This is the established idiom for exactly this class of fact: the listing's decode failure already rides
at `Error` because it means "a device that has silently stopped uploading. That is a report, not a note."

### D4 — Two directions, counted separately, only one of them alarming

```
  ledger says landed, listing lacks it   →  THE failure. report.
  listing has it, ledger does not know   →  count, do not alarm.
```

The second is a different fact with a different cost: the walk rediscovers the asset, re-uploads one
resource to the same deterministic key, and the backend overwrites. Wasted bandwidth, never a lost photo.
It is worth counting — it would surface ledger-durability problems such as a destructive schema migration
— but conflating it with the first would bury the signal that matters under the one that does not.

### D5 — No debounce, because with a single primary there is no transient

The write ordering closes the loop: the byte route stores the bytes, writes the row **non-best-effort**
(answering `502` if it cannot), and only then returns `201`; the device records "landed" after that. So a
believed-landed row implies a committed row, and with a single primary and no read replica any later read
must see it.

`database`'s *Replica staleness is unmeasured from the edge* does not apply here: read-your-writes held in
every trial measured, and the caution was reasoned **by analogy from storage** — which genuinely is
asynchronously replicated — rather than from anything established about the database's topology. The
deployment is a single primary.

Therefore Direction 1 has **no transient cause**, no false-positive budget, and needs no two-strikes gate:
one occurrence is a finding. (If the topology ever gains replicas, a two-reads-apart gate is the cheap
mitigation and this decision is where to revisit.)

**Alternative considered — require a row to be missing on two separate fetches.** Rejected as insurance
against a hazard that is not present; it would halve the detection rate and complicate the first thing we
ship, for a risk the deployment does not carry.

### D6 — Skip when divergence is already known

No configured event → nothing to compare. `joinedEventId` marker mismatch → a rejoin is pending and will
re-baseline; comparing now reports a state already understood. Both skip silently.

## Risks / Trade-offs

- **A quiet device is never checked** → accepted. Foreground is a *use*-triggered sample, and a member who
  never opens the app also never notices. Cold launch reaches the Foreground flow too, so quiet devices
  still contribute occasionally.
- **A false `Error` would be worse than no detector** — it burns the operator's attention and trains them
  to ignore the channel → D2 and D5 are what earn the right to report at `Error` at all. The sweep-shaped
  case must be a test that asserts **silence**.
- **32 KB per foreground on cellular** → small against the photo uploads themselves, but an engaged user
  foregrounds often. A simple floor (at most once per launch, or once per N minutes) keeps it honest and
  costs nothing to add.
- **The listing is unpaged and has no conditional-request support** → true, and fine at this size. A very
  heavy photographer across a full 30-day event reaches a few hundred KB. If that ever matters, `ETag` is
  a header on a request that already exists, addable later with no client restructuring.
- **`AppPorts` grows a port on ≥26.1** → the adapter already exists and the app already builds its
  siblings; this is wiring, not a new seam.

## Open Questions

- Should the check also count **resources the backend holds that the current policy would not admit**?
  The device has both halves — the listing gives keys, its rows give capture dates — so it can answer the
  question the production database could not: how much has `stop-uploading-excluded-photos` actually cost
  in the field. Free on the same trip, and it would measure that bug's damage rather than only preventing
  its recurrence. Left open because it widens the report's contract.
- Where exactly the comparison lives — a small feature beside the reconciler, or a method on it. The
  reconciler currently owns the seam and the marker; a read-only pass shares the seam but neither the
  marker nor the writes.
