## MODIFIED Requirements

### Requirement: State and authority
`:domain` SHALL contain no top-level or global mutable state (no allowlist). Instance state in
the core SHALL be limited to derived caches (projections recomputable from ports) and
coordination primitives. Authority SHALL live behind ports. The test: after process kill,
relaunch, and recomposition from ports, every fact SHALL be recoverable from durable stores or
from the external system through its port, keyed only by identifiers the external system
persisted. Which thread a port call runs on SHALL NOT be a port implementation's concern — it is
fixed by the composition (see "Dispatcher lanes are fixed by the composition"), and no port call
may assume the caller's thread.

The kill-test binds **port implementations as well as core objects**. An adapter satisfies "authority lives
behind ports" *vacuously* — the fact is behind a port, because the adapter is the port — while the port
cannot restore it. That reading is what let an upload tier hold every delivered completion in an
`ArrayList` for a later cycle to collect, with the law satisfied at review.

An entry point that receives a delivery the platform makes **once** SHALL persist it before returning, and
SHALL cite the proof that the delivery is once-only. The proof is an API contract, a vendor document, or a
measurement — never the current code — and it names which callbacks the obligation binds:
`URLSessionTask.State.completed` is documented as *"the task's delegate receives no further callbacks"*, so
a background-`URLSession` completion is bound by it; a `PHAssetResourceUploadJob` persists in the Photos
database until acknowledged, so it is not. Scheduling the write instead of performing it does not satisfy
this: after the callback returns the process's continued runtime is not guaranteed.

This obligation is the same sentence `diagnostic-logging` already carries at this boundary, with a different
object — a fact the platform delivered must not vanish without a **trace**, nor without a **record**.

Both halves of this requirement below the kill-test sentence are **review criteria**, not mechanical gates.
The population they apply to is small and derived (`architecture-guards`, the entry-point guard), but
whether a given callback holds a once-only fact is a judgement no syntactic rule separates from legitimate
coordination state. A per-callback declaration was considered and rejected: it records an assertion rather
than proving one, and a green check that only means "someone pasted a string" is worse than an honest
review criterion.

#### Scenario: A mutable global appears in the core
- **WHEN** any top-level mutable state is declared under `:domain`
- **THEN** the core-purity gate fails, with no exception mechanism

#### Scenario: Authoritative state outside a port
- **WHEN** a core object **or a port implementation** holds state whose loss on process death loses a fact
  no port can restore
- **THEN** the design is corrected so the fact is made durable, or becomes recoverable through a port
  (review criterion, recorded in the law text)

#### Scenario: A once-only delivery is held in memory
- **WHEN** a platform entry point receives a fact the platform will not deliver again and returns without
  persisting it
- **THEN** the design is rejected: the fact is unrecoverable after process death, whatever holds it
  (review criterion, recorded in the law text)

#### Scenario: A once-only claim without a proof
- **WHEN** a design asserts that a platform delivery is repeatable, or that it is not, citing only the
  current code
- **THEN** the claim is rejected until it cites an API contract, a vendor document, or a measurement, and
  names its expiry trigger
