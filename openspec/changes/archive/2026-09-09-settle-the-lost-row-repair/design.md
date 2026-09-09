## Context

`database`'s rebuildable-state requirement is the licence for everything else the capability accepts: a
public-preview platform, a 1 GB ceiling, a 10-second failover data-loss window, and migrations without
reverse migrations. All of it rests on "losing a row costs a device round-trip, never a photo".

For three of the five tables that is still exactly true. For `resources` it stopped being *automatically*
true when v2 shipped:

```
   v1 manifest publish ──▶ publishStatements(..., { legacy: true })
                            └─ upserts a resources row per listed resource   ← the repair
   v2 manifest publish ──▶ publishStatements(..., { legacy: false })
                            └─ `if (!opts.legacy) continue;`                 ← no row written
```

`api-endpoints` already records the consequence correctly, in two places. `database` does not, and it is
the document a reader consults to find out what losing a row costs.

The neighbouring replica requirement has a different kind of staleness: its normative force is right, its
stated basis is not. The probe it cites (`PROBE-FINDINGS.md` §4.2) found read-your-writes held in every
trial and then cautioned by explicit analogy to storage — *"`config.ts` already records the same hazard
for storage"* — which is a real hazard for an asynchronously-replicated object store and an assumption
about a database whose topology is a single primary.

## Goals / Non-Goals

**Goals:**

- Make `database` state what actually repairs a lost upload record under v2.
- Stop the contract forbidding a mechanism that is now the only candidate.
- Give the replica guard a truthful premise without weakening it.

**Non-Goals:**

- **Not restoring the v1 repair.** Making v2's manifest write `resources` rows would undo a deliberate v2
  decision — that `resources` has exactly one writer, the byte route that watched the bytes arrive — and
  reintroduce the shape where "a document describing what a device SHARES also records what it uploaded".
- Not designing the replacement repair. That belongs to the reconciliation work; this change only stops
  the contract ruling it out.
- Not weakening the sweep's primary-transaction discipline, or the requirement that a destructive
  operation on an ordinary read re-confirm read-your-writes from the edge.
- No behaviour change. The only non-spec edit is a comment.

## Decisions

### D1 — Keep "reconstructible", correct "repaired"

The requirement's opening claim survives intact: a `resources` row **is** reconstructible from the storage
zone, because the bytes are there and the device's byte partition can be listed — that is precisely what
the nightly sweep already walks. What changed is that nothing does it *automatically* any more.

So the correction is narrow and preserves the licence the requirement grants: reconstructibility is
unchanged, and the *repair path* for an upload record is restated — the device re-performs the upload when
it does not believe the resource landed, and nothing repairs it while it does.

Naming that gap plainly is the point. It is the honest statement of why a reconciliation is being
considered at all, and it is more useful in the contract than a repair that does not happen.

### D2 — Remove the prohibition rather than invert it

"There SHALL be no dedicated reconciliation pass" was a *consequence* of the v1 repair — with a repair
that ran on every publish, a separate pass would have been redundant. With no repair, the sentence
forbids the only mechanism that could replace it.

It is removed, not replaced with a mandate. The contract should not rule a reconciliation out, and it
should not rule one in before anyone has established the failure occurs.

**Alternative considered — rewrite it as "a reconciliation pass SHALL exist".** Rejected: that would
specify a mechanism from the wrong capability, ahead of the evidence, and `detect-lost-upload-records`
exists precisely to find out whether it is warranted.

### D3 — The replica guard keeps its force and loses its false premise

Unchanged: the sweep decides inside an interactive transaction against the primary; a future change
letting a destructive operation act on an ordinary read must first re-confirm read-your-writes from the
edge.

Corrected: the trials measured held; the caution was reasoned by analogy from storage; the deployment is a
single primary. The guard binds **if** the topology gains read replicas.

The single-primary claim is not established anywhere in the repository — `PROBE-FINDINGS.md` §4.2 measured
read-your-writes and then reasoned by analogy, and nothing else records a topology. It rests on the
**operator's confirmation, given while applying this change (2026-09-09)**: the deployed relational store
is a single primary with no read replica. That is the citation; if the requirement is ever questioned,
this is what it stands on.

That keeps the protection for the case it was written for while removing an inherited hazard that has
already propagated once — `UploadReconciler` still argues from the storage `LIST`'s consistency for a read
that is now a database query.

## Risks / Trade-offs

- **Removing a SHALL reads as weakening the contract** → the prohibition being removed protects nothing;
  it describes a redundancy that no longer exists. The rebuildable-state property, which is the actual
  protection, is untouched.
- **Stating "nothing repairs it today" is uncomfortable in a contract** → it is the true statement, and
  a reader who needs to know what a failover costs is exactly the reader who must not be told otherwise.
  The alternative is a spec that reassures where the system does not.
- **Someone later reads the corrected replica requirement as "staleness is not a concern"** → the guard
  is kept in force and its trigger condition stated, so the requirement still fires the moment the
  topology changes. Wording should make the conditional prominent rather than a footnote.

## Note for the reconciliation work

Whoever picks up `detect-lost-upload-records` — or any repair that follows it — should cite this change
rather than re-derive it. Two things changed here that the work depends on:

- `database` **no longer forbids a dedicated reconciliation pass**. The prohibition was a consequence of
  the v1 repair; with that repair gone it forbade the only remaining mechanism. What survives is narrower:
  no repair may be assumed to happen as a *side effect of an unrelated write*. A dedicated pass is now
  neither required nor forbidden — the evidence decides.
- The **uncovered case is named in the contract**: while the device believes the resource landed, nothing
  repairs the row, and the resource is absent from every union that would have served it. That sentence is
  the citation for why a detection is worth building at all; it does not have to be argued again.

What this change deliberately did **not** decide: whether the gap occurs often enough to close, and by
what mechanism. It also did not restore the v1 repair — see Non-Goals.

