## ADDED Requirements

### Requirement: A publish carries the manifest version

Every device manifest publish SHALL carry the **manifest version** its cycle read before anything else
(capability `upload-lifecycle`, "The upload cycle owns its entry decision"), as the body field `version` — a
non-negative integer (wire format: capability `api-endpoints`). The version is the device's ledger counter
(capability `sync-ledger`, "The manifest version orders the device's manifest snapshots"), which advances on
every change that could alter the projection. The backend stores a publish only when its version is not older
than the one it already holds, so the backend's copy only ever moves forward.

A publish the backend refuses as older SHALL be treated by the producer as **published**: a snapshot at least
as new is already stored. The producer SHALL record the refused publish's skip record as it would a stored one
— at worst the next cycle republishes once, because the skip record's version no longer matches — and SHALL
neither retry it nor report an error.

The version orders the projection's two inputs: the ledger rows and the membership's policy bounds, whose one
writer (the reconfigure save) advances it too (capability `reconfigure-membership`). The album denylist is
deliberately **not** versioned: its changes happen outside the app, and the next cycle's differing snapshot
republishes them under the current version. Decision record: `changes/manifest-versions`.

#### Scenario: The publish carries the version the cycle read first

- **WHEN** a cycle reads manifest version 42 and publishes its projection
- **THEN** the publish body carries `version: 42`

#### Scenario: A refused publish counts as published

- **WHEN** the backend refuses a publish because it holds a newer version
- **THEN** the producer reports no error, does not retry, and saves the skip record for the refused snapshot

## MODIFIED Requirements

### Requirement: The upload cycle is the writer, synchronous in-cycle upload

The upload cycle SHALL be the **only** code that writes the *projected* device manifest, and it SHALL publish
the manifest **synchronously within the cycle** — no background `URLSession` transfer carries it. Both
processes' cycles publish: the app's on every iOS version, and the extension's on iOS ≥26.1, so on iOS ≥26.1
under a full grant both do. Each publish is the **full-state snapshot** its cycle projected from the one shared
ledger through the membership's one selection policy, and the backend applies it whole — unless it carries a
manifest version **strictly older** than the one the backend already holds, in which case the backend keeps
what it has (see "A publish carries the manifest version"). A cycle MAY skip the publish when the projected
snapshot **and its manifest version** are unchanged since the last **successful** write: the skip record SHALL
hold the event id, the version, and the snapshot, and a skip SHALL require all three to match. A kill mid-publish SHALL be tolerated: the write is atomic at the backend (capability `database`),
so a killed cycle leaves the previous state intact and the projection is recomputed next cycle.

**The word "successful" remains load-bearing**, for a reason that survives the move to v2 even though its
original justification does not. Under v1 it protected the byte route's best-effort upload record, which
the next manifest publish repaired. v2's byte route records the resource itself and is **not** best-effort,
and the manifest writes no resource row at all — so there is nothing left to repair. What remains is the
union: skipping a publish after a *failed* one would leave the backend holding an older asset set while the
device believed it had published, so photos already uploaded would stay absent from the event union with no
error anywhere. The rule is unchanged; only its reason is.

The upload cycle is the **only** producer of this document. Enrollment no longer writes a
register-only empty manifest (capability `join-event`), so joining SHALL NOT itself invalidate the
skip-if-unchanged record. A rejoin leaves the membership's existing asset set intact — the union continues to
list this device's photos with no blank window between the join and the next cycle. The join-time ledger load
advances the manifest version (capability `sync-ledger`), so the next cycle republishes the projection under
that newer version even when its content is unchanged; that republish is what re-establishes the version the
join reset on the backend (capability `api-endpoints`).

Two cycles publishing at once is **accepted, and ordered by the backend**. Each publish carries the manifest
version its cycle read before anything else, and the backend refuses a strictly older one, so the backend can
never hold a snapshot older than one it has already accepted. A **crossed pair** — cycle A's older snapshot
lands after cycle B's newer one, or A's committed write loses its response while B's skip record stands — SHALL
NOT leave the device skipping against a stale backend: either the older write is refused, or the two carried
the same version, in which case the skip record's version no longer matches the next cycle's and the next
cycle republishes. The earlier acceptance of that staleness as "self-healing at the next ledger change" is
retired: it healed only if another change came, and after an event's last change none does. Decision records:
`changes/both-uploaders-active`, `changes/manifest-versions`.

#### Scenario: Synchronous publish with skip-if-unchanged

- **WHEN** the upload cycle has produced the projected snapshot
- **THEN** the cycle publishes the manifest synchronously in-cycle, or skips it when the event, the manifest
  version and the snapshot all equal those of the last successful write

#### Scenario: A failed publish is retried rather than skipped

- **WHEN** a manifest publish fails and the next cycle's projection is unchanged
- **THEN** the next cycle publishes again rather than skipping, because the last write was not successful

#### Scenario: Re-joining an event never empties this device's manifest

- **WHEN** the device re-enrolls in an event it has already contributed to — after a leave, a durable
  state reset, or a reinstall — and the projected snapshot is unchanged from before
- **THEN** the join writes no manifest, the membership's asset set is untouched, the next cycle republishes
  the unchanged projection under the newer manifest version, and the event union lists this device's
  uploaded photos throughout

#### Scenario: The producer is the only writer

- **WHEN** any join, re-join, provision or reconfigure occurs
- **THEN** no manifest is written by it, and the skip-if-unchanged record continues to describe the last
  projection an upload cycle successfully published

#### Scenario: Both processes' cycles publish the full-state snapshot

- **WHEN** on iOS ≥26.1 under a full grant the app's cycle and the extension's cycle each settle the ledger
  for the joined event
- **THEN** each publishes the full-state snapshot it projected (or skips an unchanged one), and the backend
  holds the write with the highest manifest version, whichever landed last

#### Scenario: A crossed pair never strands the backend

- **WHEN** cycle A read version N and projected snapshot S1, cycle B read a newer version and projected S2, and
  A's publish reaches the backend after B's
- **THEN** the backend refuses A's publish as older and keeps S2, with no further ledger change needed

#### Scenario: A same-version crossed pair is republished by the next cycle

- **WHEN** cycles A and B both read version N, a ledger change commits between their row reads, A's older
  snapshot lands on the backend after B's, and B's skip record is saved last
- **THEN** the next cycle reads a version above N, finds the skip record's version unequal, and republishes, so
  the backend holds the current snapshot

#### Scenario: Kill mid-publish leaves the previous state intact

- **WHEN** the process running the cycle is killed during a manifest publish
- **THEN** the backend applies none of that publish, and the manifest is recomputed and re-published on the
  next cycle

