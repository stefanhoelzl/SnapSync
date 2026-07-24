## ADDED Requirements

### Requirement: The fed selection is a candidate source, and its eager read is what keeps fetches in-flow

The selection snapshot SHALL be exposed to the admission as one **candidate source** impl (capability
`gallery-status`), pre-filled with the current selection — so the one admission runs over it exactly as over
a full-library walk, and the mode difference is a source impl rather than a branch in the policy or in any
consumer. No consumer SHALL select between a walking and a snapshot path itself; the permission-aware source
SHALL make that choice once (capability `permission-gate`'s grant read), leaving each consumer to handle
only whether a grant permits an answer at all.

The snapshot SHALL continue to be read **eagerly, with resources**, at the sanctioned read points — the
cold-launch baseline and the photo-selection-change observer emissions. This is deliberate and is the
opposite of the lazy per-asset read the walking sources use:

- The alert storm the decision record measures is caused by an autonomous library **fetch**, not by reading
  an already-fetched asset's resources. The eager read is what guarantees every fetch happens in-flow: a
  candidate whose resources were deferred would, at upload time, either have to hold platform asset
  references across the snapshot cell — making storm-safety depend on an invariant no type expresses — or
  re-fetch by local identifier off-flow, which is precisely the measured storm.
- The saving laziness would buy here is negligible: a limited selection is hand-picked and small, so the
  policy rejects few of its assets and the resource reads would be paid anyway.

Consequently a candidate under `LIMITED` carries facts derived from the snapshot it was built from, and its
resources are already held rather than fetched on demand. That is not a special case in the admission — a
candidate source is free to have its resources in hand — and no rule reads resources to decide (capability
`photo-selection-policy`).

#### Scenario: The admitted set under LIMITED is the filtered selection

- **WHEN** permission is `LIMITED` and any consumer resolves the admitted set
- **THEN** the permission-aware source yields the snapshot's candidates and the one admission filters them
  exactly as it would a walk, with no autonomous library fetch and no branch in the consumer

#### Scenario: The snapshot is read at the sanctioned points only

- **WHEN** the selection is captured
- **THEN** it is read at the cold-launch baseline or an observer emission, eagerly and with resources — never
  deferred to a later, off-flow moment that would require a fresh fetch

#### Scenario: No consumer branches on the grant to pick a source

- **WHEN** the status total or the join preview resolves its answer
- **THEN** it calls one candidate source and never distinguishes `GRANTED` from `LIMITED` itself; only the
  question of whether any answer is available (`DENIED` / `NOT_DETERMINED`) remains with the consumer

## MODIFIED Requirements

### Requirement: One discovery serves both the status total and the enqueue

Under `LIMITED`, the own-device status total `N` SHALL be derived from the same selection snapshot that
enqueues upload work — the baseline read or a selection-change consumption — rather than from a separate
autonomous gallery walk. This preserves the policy identity (`photo-selection-policy`: the total and the
upload walk resolve the same admitted set) with **one** library read per event instead of two. Under
`GRANTED` the existing separate gallery refresh is unchanged.

The total SHALL obtain that snapshot through the permission-aware candidate source rather than by being
handed a resource list through a second, snapshot-specific entry point. A consumer with two entry points —
one for walking, one for a pushed snapshot — restates the mode difference the source already owns, and it is
that restatement, not the reading itself, that lets the two paths drift apart.

#### Scenario: A selection change updates N and the queue together

- **WHEN** permission is `LIMITED` and a selection change adds two in-scope photos
- **THEN** one read both raises `N` by two and enqueues the two uploads — no second library read occurs

#### Scenario: The total has one entry point regardless of grant

- **WHEN** the status total is refreshed under `GRANTED` and under `LIMITED`
- **THEN** the same single refresh entry point serves both, differing only in which source backs it
