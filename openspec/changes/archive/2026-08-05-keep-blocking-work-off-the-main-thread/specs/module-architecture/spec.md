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

#### Scenario: A mutable global appears in the core
- **WHEN** any top-level mutable state is declared under `:domain`
- **THEN** the core-purity gate fails, with no exception mechanism

#### Scenario: Authoritative state outside a port
- **WHEN** a core object holds state whose loss on process death loses a fact no port can restore
- **THEN** the design is corrected so the authority moves behind a port (review criterion,
  recorded in the law text)

## ADDED Requirements

### Requirement: Dispatcher lanes are fixed by the composition

Which thread work runs on SHALL be a property of the composition root, not of the adapter that
performs the work. The previous rule — that each synchronous-I/O port implementation owns its own
dispatcher hop — is withdrawn: its compliance test lives at the call site, in another module, and
often in another process, so the same adapter is correct in the extension and lethal in the app.
Measured compliance was 2 of 23 adapter files, and both compliant seams were written after an
incident rather than before one.

Three lanes SHALL exist, each with a stated purpose:

- the **main lane** is reserved for platform UI (UIKit presentation and the system prompts it
  drives) and SHALL carry nothing else;
- the **CPU lane** carries presentation-state reduction;
- the **composition lane** carries the live core's scope: blocking platform calls, network, and
  durable stores. It SHALL be a dispatcher of the composition's own, not a slice of the CPU lane,
  so that a blocked platform call cannot consume the pool that presentation-state reduction runs on.

The live core's composition scope SHALL NOT be UI-bound, in any binary that composes it — device
shell or harness alike. It SHALL be **serial**: the main thread it replaces is single-threaded, and
core code relies on that for mutual exclusion, so changing the thread SHALL NOT also change
concurrency semantics.

User-initiated commands SHALL declare their lane where they are built. The composition scope does
not govern them: the presentation container launches an intent on an unconfined dispatcher, so a
command's synchronous prefix runs on the thread that fired it. Every command SHALL therefore be
built through a lane-declaring decorator, and no decorator SHALL supply a default lane.

A dispatcher hop inside an adapter is permitted, but its meaning is **throughput** — allowing that
work to proceed concurrently with other work on the serial composition scope — and never safety.
A hop SHALL NOT be justified by keeping work off the main thread, because the composition already
does that.

#### Scenario: A blocking adapter is written with no dispatcher hop
- **WHEN** a new adapter performs a synchronous platform call and hops nowhere
- **THEN** it runs on the composition's I/O lane, off the main thread, because of where it was
  composed rather than what its author remembered

#### Scenario: A composition scope is bound to the UI thread
- **WHEN** any binary composes the live core on a UI-bound scope
- **THEN** the law is violated, whether that binary is the device shell or a harness

#### Scenario: A command is added without a lane
- **WHEN** a command is added to the user-command bundle without a lane-declaring decorator
- **THEN** it does not compile, because no decorator supplies a default

#### Scenario: A hop is justified as safety
- **WHEN** an adapter's dispatcher hop is documented as keeping work off the main thread
- **THEN** the justification is wrong and is corrected to name the concurrency it buys, or the hop
  is removed

### Requirement: A platform-capability claim is settled by a compile, not by a symbol table

A necessity claim about what a target platform provides SHALL be settled by compiling against the
API, not by inspecting a published artifact's symbols. A klib, jar, or framework records what
**ships**; it does not record what is **callable**. The two differ, and the difference is invisible
to inspection: `Dispatchers.IO` appears in the Kotlin/Native coroutines klib — as `IO`, `<get-IO>`
and `DefaultIoScheduler` — while being `internal`, so a design built on the symbol table's evidence
had to be withdrawn at the first compile.

This does not replace the existing rule that necessity claims carry forcing proofs; it names the
form of evidence that does not qualify.

#### Scenario: A capability is inferred from a published artifact
- **WHEN** a design depends on a platform API whose availability was established by reading symbols
  out of an artifact
- **THEN** the claim is re-established by compiling against that API before any other work depends
  on it, and the design records the compile rather than the symbol

#### Scenario: A claim of absence is stated more precisely than it was found
- **WHEN** a comment records that a platform lacks a facility
- **THEN** it states what is actually absent — a public API, a target, a version — rather than the
  facility as a whole, so a later reader can tell which change would falsify it
