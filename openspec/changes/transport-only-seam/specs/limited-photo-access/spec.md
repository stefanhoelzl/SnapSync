## MODIFIED Requirements

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
consumes the in-memory selection snapshot (`SelectionScopedDiscovery`, which wraps the cycle's
`UploadDiscovery`) is a property of the mechanism, and it differs between mechanisms on the same OS and the
same grant.

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
