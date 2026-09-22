## RENAMED Requirements

- FROM: `### Requirement: Sole writer, synchronous in-cycle upload`
- TO: `### Requirement: The upload cycle is the writer, synchronous in-cycle upload`

## MODIFIED Requirements

### Requirement: The upload cycle is the writer, synchronous in-cycle upload

The upload cycle SHALL be the **only** code that writes the *projected* device manifest, and it SHALL publish
the manifest **synchronously within the cycle** — no background `URLSession` transfer carries it. Both
processes' cycles publish: the app's on every iOS version, and the extension's on iOS ≥26.1, so on iOS ≥26.1
under a full grant both do. Each publish is the **full-state snapshot** its cycle projected from the one shared
ledger through the membership's one selection policy, and the backend applies it whole, so **the last write
wins**. A cycle MAY skip the publish when the projected snapshot is unchanged since the last **successful**
write. A kill mid-publish SHALL be tolerated: the write is atomic at the backend (capability `database`),
so a killed cycle leaves the previous state intact and the projection is recomputed next cycle.

**The word "successful" remains load-bearing**, for a reason that survives the move to v2 even though its
original justification does not. Under v1 it protected the byte route's best-effort upload record, which
the next manifest publish repaired. v2's byte route records the resource itself and is **not** best-effort,
and the manifest writes no resource row at all — so there is nothing left to repair. What remains is the
union: skipping a publish after a *failed* one would leave the backend holding an older asset set while the
device believed it had published, so photos already uploaded would stay absent from the event union with no
error anywhere. The rule is unchanged; only its reason is.

The upload cycle is the **only** producer of this document. Enrollment no longer writes a
register-only empty manifest (capability `join-event`), so joining SHALL NOT invalidate the skip-if-unchanged
record. A rejoin therefore leaves the membership's existing asset set intact and correctly skips a republish
of an unchanged projection — the union continues to list this device's photos with no blank window between the
join and the next cycle.

Two cycles publishing at once is **accepted, not prevented**. Both project the same ledger through the same
policy, so they agree whenever the ledger did not change between their projections. When it did, a **crossed
pair** — cycle A publishes snapshot S1 and cycle B snapshot S2, A's write lands last and B's skip record lands
last — can leave the backend one snapshot behind while the skip record claims it current. That staleness is
bounded and self-healing: the next ledger change produces a different projection, which no skip record
matches, so the next cycle republishes it. Decision record: `changes/both-uploaders-active`.

#### Scenario: Synchronous publish with skip-if-unchanged

- **WHEN** the upload cycle has produced the projected snapshot
- **THEN** the cycle publishes the manifest synchronously in-cycle, or skips it when the snapshot is
  unchanged since the last successful write

#### Scenario: A failed publish is retried rather than skipped

- **WHEN** a manifest publish fails and the next cycle's projection is unchanged
- **THEN** the next cycle publishes again rather than skipping, because the last write was not successful

#### Scenario: Re-joining an event never empties this device's manifest

- **WHEN** the device re-enrolls in an event it has already contributed to — after a leave, a durable
  state reset, or a reinstall — and the projected snapshot is unchanged from before
- **THEN** the join writes no manifest, the membership's asset set is untouched, the unchanged projection
  is correctly skipped, and the event union lists this device's uploaded photos throughout

#### Scenario: The producer is the only writer

- **WHEN** any join, re-join, provision or reconfigure occurs
- **THEN** no manifest is written by it, and the skip-if-unchanged record continues to describe the last
  projection an upload cycle successfully published

#### Scenario: Both processes' cycles publish the full-state snapshot

- **WHEN** on iOS ≥26.1 under a full grant the app's cycle and the extension's cycle each settle the ledger
  for the joined event
- **THEN** each publishes the full-state snapshot it projected (or skips an unchanged one), and the backend
  holds whichever write landed last

#### Scenario: A crossed pair heals at the next ledger change

- **WHEN** two cycles' publishes cross so that the backend holds the older snapshot while the skip record
  names the newer one
- **THEN** the next cycle after the ledger changes projects a snapshot the skip record does not match and
  publishes it, so the backend is current again

#### Scenario: Kill mid-publish leaves the previous state intact

- **WHEN** the process running the cycle is killed during a manifest publish
- **THEN** the backend applies none of that publish, and the manifest is recomputed and re-published on the
  next cycle
