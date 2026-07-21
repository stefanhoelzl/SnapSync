# upload-lifecycle Specification

## Purpose

> Full replacement of the Purpose section (one closing paragraph added on the single-membership
> contract; no requirement changes). Apply by hand at archive time and diff.

The **tier-neutral upload arm**: which producer verb fires on which membership transition (provision,
event switch, permission grant, direction change, leave), and the invariant that **no transition ever
destroys durable dedup state**. Each upload tier supplies the mechanism behind a two-verb `UploadProducer`
seam (`start` / `stop`); this capability owns the decision, and it owns it in one tested, platform-free
place.

It exists because the upload lifecycle previously had **no owner**. It was smeared across the two tier
specs and the iOS composition root — a module the project's own hard rule declares wiring-only and
untested — so no contract described it and no test could reach it. When a second upload tier arrived, the
app-driven tier (iOS 18–26.0) inherited a PhotoKit-shaped "disable→enable" re-registration ritual on every
provision. Its *disable* half resolved to a full leave (cancelling transfers and the `BGProcessingTask`
heartbeat, wiping the ledger **and** the discovery cursor) while its *enable* half was a no-op below iOS
26.1. Joining an event therefore tore the upload arm down, started nothing, and re-uploaded the user's
whole post-cutoff library on the next cycle — on the tier every current user runs.

The two-verb seam is the fix, and it is a **structural** one. With no destructive verb to reach, there is
no edge from *provision* to *destruction* to get wrong: the bug is unrepresentable rather than merely
absent. Durable state is device-global dedup (`sync-ledger`, "Event-independent key") and stays true across
a leave, a switch, and a re-join; only a triggered reconciliation's `resetTo` ever re-baselines it
(`event-rejoin-reconciliation`). Selecting exactly one producer per process likewise makes the two tiers'
mutual exclusion structural — the non-selected tier's mechanism is never constructed, so it cannot run and
cannot become a second `LedgerWriter`.

The transition table is written against the **current single-active-membership contract** (capability
`join-event`): *provision* and *switch* assume one configured event, and no membership means no arm.
Concurrent multi-event membership is a named future direction; the durable pieces already compose with
it (the ledger key is event-independent, bytes are device-partitioned), so that future reworks the
arm's decision table, not the dedup state — and until then, new work SHALL NOT deepen the
single-membership assumption beyond what this table already encodes.

Decision record: `changes/archive/2026-07-12-fix-app-driven-upload-lifecycle`.
