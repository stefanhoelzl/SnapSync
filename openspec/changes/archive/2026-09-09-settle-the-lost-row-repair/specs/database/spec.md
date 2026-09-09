## MODIFIED Requirements

### Requirement: The database holds only rebuildable state

Every row SHALL be reconstructible from the storage zone plus one full-state manifest publish per
device. No user-visible fact SHALL exist only in the database.

This bounds the consequence of losing the store — the union goes empty until devices republish, and no
photo is destroyed — and it is what makes the platform's stated limits acceptable: **public preview**, a
1 GB per-database ceiling, and a **10-second maximum data-loss window** on primary failover.

It is also what makes a **schema migration** an acceptable operation on live data: the worst outcome of a
failed migration is the same worst outcome as losing the store, and the recovery is the same — devices
republish. A migration SHALL therefore NOT require a reverse migration as its rollback plan.

Reconstructibility and **automatic repair** are different properties, and the version boundary separates
them. An acknowledged write lost to the failover window SHALL be repaired by the next manifest publish
where the publishing device still asserts the fact — for every fact the manifest publish writes. Under v2
the manifest publish writes **no `resources` row**: that table has a single writer, the byte route that
watched the bytes arrive (capability `api-endpoints`), so a lost **upload record** is not restored by a
publish. Its row stays reconstructible — the bytes are in the device's byte partition and that partition
can be listed — but the only mechanism that restores it is the **device re-performing the upload**, which
happens when the device does not believe the resource landed.

A lost upload record therefore has one uncovered case, and this requirement SHALL state it rather than
imply otherwise: while the **device believes the resource landed**, nothing repairs the row, the resource
is absent from every event union that would have served it, and no operator action or device round-trip is
triggered by anything. Whether that gap is closed, and by what, is not decided here.

There SHALL be no *implicit* reconciliation — no repair may be assumed to happen as a side effect of an
unrelated write. A dedicated reconciliation is neither required nor forbidden by this capability.

#### Scenario: A lost attestation record is repaired by re-attesting

- **WHEN** a device's attestation record is lost
- **THEN** its next renewal is refused, it completes a fresh attestation, and the record is restored with
  no operator action

#### Scenario: A lost upload record is not repaired by a v2 publish

- **WHEN** an acknowledged upload record is lost to a primary failover, so the resource's row is gone, and
  the device publishes a full-state manifest over the v2 device API
- **THEN** the publish does not restore the row, because the v2 manifest writes no `resources` row

#### Scenario: A lost upload record the device has not settled is repaired by re-uploading

- **WHEN** an upload record is lost and the device does not record that resource as landed
- **THEN** its next cycle uploads the resource again to the same deterministic key, and the record is
  restored with no operator action

#### Scenario: A lost upload record the device believes settled is not repaired

- **WHEN** an upload record is lost and the device records that resource as landed
- **THEN** nothing in the backend restores it and nothing in the device re-uploads it, and the resource
  stays absent from every event union that declared it

#### Scenario: A failed migration recovers by republication

- **WHEN** a schema migration leaves the store unusable or incomplete
- **THEN** recovery is republication by devices, not a reverse migration

### Requirement: Replica staleness is unmeasured from the edge, and the sweep decides on the primary

A destructive operation SHALL NOT act on a read whose freshness has not been established for the context it
runs in.

The deployed store is a **single primary with no read replica**. Read-your-writes held in every trial
measured, including from a separate read-only token (`PROBE-FINDINGS.md` §4.2) — but those trials ran from
a workstation against a test database, **not** from an Edge Script against the production one. The
caution recorded here was reasoned **by analogy from storage**, which is asynchronously replicated
(`config.ts`: "a stale replica read is the one failure mode that would delete live data"), rather than
from any established replica routing on the database. It is therefore a guard against a topology change,
not a description of today's deployment, and it SHALL NOT be cited as evidence that an ordinary read is
stale.

Because a stale read that missed a rejoin would let the sweep delete a live event, the sweep's deletion
decision SHALL be made inside an interactive transaction, which runs against the primary (capability
`scheduled-cleanup`). Ordinary request handling MAY use ordinary reads.

If the deployment ever gains read replicas or regional read routing, this guard binds in full: any change
that lets a **destructive** operation act on an ordinary read's word SHALL first re-confirm
read-your-writes **from the edge**.

#### Scenario: The sweep does not delete on a possibly-stale read

- **WHEN** the sweep evaluates an event for deletion
- **THEN** the decision is taken against the primary, not against whatever replica an ordinary read reaches

#### Scenario: A single-primary deployment is not cited as a staleness hazard

- **WHEN** a design argues that an ordinary read may be stale
- **THEN** it establishes that from the deployment's topology, not from this requirement or from the
  storage zone's replication behaviour
