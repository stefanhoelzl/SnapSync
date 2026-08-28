## MODIFIED Requirements

### Requirement: The limited selection is a facts-only candidate source for the admitted set

Under `LIMITED`, the user's hand-picked selection SHALL be presented to the admission (capability
`photo-selection-policy`) as one **candidate source** — pre-filled with the current selection — so the
same single admission runs over it exactly as over a full-library walk under `GRANTED`. The admission and
the `EventPhotoSet` abstraction SHALL be permission-oblivious: the mode difference is one source impl, not
a branch in the policy or its consumers. No consumer SHALL select between a walking and a snapshot path
itself; the permission-aware source SHALL make that choice once (capability `permission-gate`'s grant
read). No native fetch narrowing applies under `LIMITED` (there is no walk to narrow); the authoritative
in-memory admission filters the captured selection.

**The source SHALL also answer whether an admitted set can be produced at all**, and a consumer SHALL NOT
keep a grant check of its own for that question (capability `gallery-status`, "Library resource enumeration
seam"). Splitting the two questions — the source choosing where candidates come from, each consumer
deciding whether a grant permits an answer — left the consumers restating the grant distinction the source
already owns, which is the restatement that lets two paths drift apart.

**A selection snapshot that has not been captured is not an empty selection.** Until the cold-launch
baseline or the first observer emission has been consumed, the source holds no selection and SHALL report
that the admitted set cannot be determined — never an empty candidate set. The two have opposite
consequences: an empty selection is a counted zero and settles the status screen as "everything shared",
which is true for a receive-only member and false for a member whose selection has simply not arrived yet.
Because the status projection publishes only a ready state (capability `sync-status`), a settled frame
cannot be retracted, so the honest count that follows reads as the screen going backwards — the reported
defect `SNAPSYNC-14` / `SNAPSYNC-16`, in the form it survived under a partial grant.

A read that cannot be answered SHALL be recorded at a severity that does not reach crash reporting: a
member who withheld access, or a snapshot that has not yet arrived, is not a defect (capability
`diagnostic-logging`).

The sanctioned-read discipline SHALL be scoped to **library fetches**, not to every PhotoKit call, and
SHALL live entirely in how the source is **constructed and fed**:

- A library **fetch/query** (`PHAsset.fetchAssets…`) SHALL NOT be issued autonomously. The selection is
  captured only at the cold-launch baseline and at photo-selection-change observer emissions, and the
  source is **fed** that snapshot — never pulled. This is the measured storm: off-flow fetches queue
  limited-access alerts that survive process death, and
  `PHPhotoLibraryPreventAutomaticLimitedAccessAlert` does **not** reliably suppress them (decision record
  `changes/archive/2026-07-20-accept-limited-photo-access`).
- A per-asset **resource read** (`assetResourcesForAsset`) of an already-selected asset MAY be issued
  off-flow. This was measured storm-free on device (SE2, iOS 26.5.2, `.limited`, alert-suppression on):
  six off-flow bursts over already-held baseline refs produced zero alerts, during the bursts and on the
  bare home screen after a `SIGKILL`.

The snapshot SHALL nonetheless continue to be read **eagerly, with resources**, at those sanctioned
points. The spike licenses a lazy per-asset read where the asset reference is still held; it does not
license one across the snapshot cell, because reaching those assets again later would mean either holding
platform references for an unbounded period — storm-safety resting on an invariant no type expresses — or
re-fetching by local identifier, which is the measured storm itself. The eager read is what keeps every
library **fetch** in-flow, and a limited selection is hand-picked and small, so the deferral would save
almost nothing for that risk. The lazy path belongs to the *walking* sources, where the reference never
leaves the call.

Consequently a candidate under `LIMITED` carries facts derived from the snapshot it was built from, and
its resources are already held rather than fetched on demand. That is not a special case in the admission
— a candidate source is free to have its resources in hand — and no rule reads resources to decide
(capability `photo-selection-policy`).

Reading the selection **eagerly** is also what bounds the un-answerable window: it lasts from the grant
turning partial until the first sanctioned read completes, and is closed by that read rather than by any
consumer waiting or retrying.

#### Scenario: The admitted set under LIMITED is the filtered selection

- **WHEN** permission is `LIMITED` and any consumer resolves the admitted set
- **THEN** the permission-aware source yields the snapshot's candidates and the one admission filters them
  exactly as it would a walk, with no autonomous library fetch and no branch in the consumer

#### Scenario: An un-captured snapshot is not an empty selection

- **WHEN** permission is `LIMITED`, the member has photos selected, and the status total is refreshed
  before the baseline read or any observer emission has been consumed
- **THEN** the source reports that the admitted set cannot be determined, the total stays un-counted, and
  the screen does not settle to "In sync"

#### Scenario: A snapshot that never arrives never becomes a zero

- **WHEN** permission is `LIMITED` and no selection snapshot is ever emitted for the lifetime of the
  process
- **THEN** the total remains un-counted for that lifetime, rather than resting on a counted zero that
  would read as "everything shared"

#### Scenario: An empty selection is still a counted zero

- **WHEN** permission is `LIMITED`, a snapshot has been captured, and it contains no photo the policy
  admits
- **THEN** the total is a counted `0` and the screen settles — the receive-only resting state is
  unaffected

#### Scenario: A consumer keeps no grant check of its own

- **WHEN** the status total or the join preview resolves its answer under any grant
- **THEN** it reads the source's result alone, and consults no photo-access grant to decide whether an
  answer was available

#### Scenario: The snapshot's resources are already in hand

- **WHEN** a consumer under `LIMITED` needs an admitted asset's resources
- **THEN** they are already held from the sanctioned read — nothing is fetched again, and in particular no
  fetch by local identifier is issued outside the sanctioned points

#### Scenario: No autonomous fetch is issued under LIMITED

- **WHEN** any consumer resolves the admitted set under `LIMITED`
- **THEN** no library fetch/query is issued outside the cold-launch baseline and the observer emissions —
  the selection always arrives as a fed snapshot

#### Scenario: The snapshot is read at the sanctioned points only

- **WHEN** the selection is captured
- **THEN** it is read at the cold-launch baseline or an observer emission, eagerly and with resources —
  never by a fetch issued at any other moment

#### Scenario: No consumer branches on the grant to pick a source

- **WHEN** the status total or the join preview resolves its answer
- **THEN** it calls one candidate source and never distinguishes `GRANTED` from `LIMITED` itself; only the
  source makes that choice

### Requirement: The read discipline is enforced at the mechanism, not at the trigger fan-out

The rule that no autonomous library read occurs under a partial grant SHALL be enforced by the upload
**mechanism** that would perform the read, not by the trigger fan-out that wakes it. A trigger SHALL be
delivered to the resolved mechanism unconditionally (`upload-lifecycle`, "Triggers are delivered to the
mechanism and declined explicitly"), and the mechanism SHALL decide whether responding would read the
library.

Placing the gate at the fan-out makes it an **invoker-gate**, and its soundness then depends on the
fan-out's enumeration of who might read — an enumeration invalidated silently by a new mechanism or a new
trigger. This is the same failure shape `upload-lifecycle` records for the direction gate ("The arm's
direction gate lives at the choke point, never at the invoker"), and the same remedy applies.

The mechanism is also the only component that **knows the answer**: whether a cycle walks the library or
consumes the in-memory selection snapshot (`SelectionScopedTransfer`) is a property of the mechanism, and
it differs between mechanisms on the same OS and the same grant.

Relocating this gate SHALL preserve the behaviour it currently produces. It SHALL NOT be widened as a
side effect of the move — if the relocated gate would admit a trigger the fan-out currently refuses, that
widening is a separate decision requiring its own evidence.

A selection-scoped discovery SHALL **preserve the walk cursor** — it SHALL return the change token it was
given, unchanged — and SHALL NOT report a full enumeration, so it drives no ledger pruning. This is what
makes the mechanism's own empty answer safe: where the count must distinguish an un-captured snapshot from
an empty one, discovery need not, **because its empty answer is retryable and the count's is not**. An
un-captured snapshot costs the upload arm one idle cycle, which the next observer emission re-runs; an
advanced cursor would instead step the change feed permanently past assets nobody enumerated, and no later
incremental walk would return them.

The reason this is stated as a requirement rather than left to the implementation is that the behaviour is
otherwise pinned only for its weaker justification — resuming incrementally after the partial grant ends,
which is a performance argument a future change could reasonably trade away.

#### Scenario: A trigger that would walk the library is declined under a partial grant

- **WHEN** a background trigger reaches an upload mechanism whose response would enumerate the photo
  library, and photo access is `LIMITED`
- **THEN** the mechanism performs no library read, and the decision is made in the mechanism rather than
  by the component that delivered the trigger

#### Scenario: A selection-scoped mechanism is not blocked by a gate meant for walks

- **WHEN** a trigger reaches a mechanism whose discovery consumes the selection snapshot rather than
  walking, under a `LIMITED` grant
- **THEN** whether it responds is decided by that mechanism's own reading of the discipline, not by a
  blanket refusal at the fan-out

#### Scenario: A scoped discovery does not advance the cursor

- **WHEN** a selection-scoped discovery runs with a change token it was given
- **THEN** it returns that same token, reports no full enumeration and no removals, so nothing is pruned
  and no asset is stepped over

#### Scenario: An un-captured snapshot costs an idle cycle, not lost photos

- **WHEN** an upload cycle runs under a partial grant before any selection snapshot has been captured
- **THEN** it enqueues nothing, the cursor is unchanged, and the next observer emission re-runs discovery
  over the real selection
