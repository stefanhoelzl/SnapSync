## MODIFIED Requirements

### Requirement: The app shells are wiring-only and untested

No `:app:*` module — `:app:ios`, `:app:ios:extension`, `:app:ios:forge`, `:app:desktop` — SHALL
declare a test source set. Behaviour that warrants coverage SHALL be relocated into `:domain` or an
adapter module rather than covered in place.

The guarantee this rule rests on is **"nothing worth testing is there"**, produced by the shell
gates (`detektAppShell`, `KotlinShellGuardTest`, `SwiftShellGuardTest` — capability
`architecture-guards`), which forbid **decisions** in shell source. Their scope is the two
live-core shells — `:app:ios` and `:app:ios:extension`, plus any source contributed into them. The
other two `:app:*` modules are unscanned, and are untested for different reasons that SHALL NOT be
conflated with the gated one: `:app:ios:forge` links no live graph at all, and `:app:desktop`'s
harness panes are the named test-equipment zone, exempt from the shell laws (`module-architecture`)
and exercised non-gatingly through `:test:harness-driver`.

The rule's **residual risk SHALL be stated wherever it is relied upon**: the shell gates do not
catch **mis-transcription**. A zero-conditional forwarding that names the wrong collaborator holds
no decision, passes every gate, compiles, and is wrong. "Untested" here is a project choice about
what may live in a shell, not a claim that shell code is untestable.

That risk was realised — the tap table existed three times with differing omissions, and every OS callback
was hand-transcribed twice — and is closed by **relocation, not by testing in place**, as this rule
prescribes. The OS-callback transcription is the core's implementation of an inbound port
(`module-architecture`, "OS entry points cross an inbound port"), which the shell reaches by
compiler-generated delegation and which a port contract covers on JVM and the simulator (`port-contracts`);
the tap → intent table is one factory in `:ui:screens`, click-tested there (`sync-status-screen`). What
remains uncovered, and SHALL be stated wherever shell correctness is relied upon: **argument-level
forwarding in Swift** — the right entry called with a wrong but same-typed argument — and the shell's
hand-written entry points outside the inbound port (`onLaunch`, the event-link activity filter's call site,
and the log-only callbacks). A Swift call that crosses two entries is a compile error wherever their
signatures differ, and the one same-shaped pair (the background tasks) forwards the OS's own identifier to a
single entry.

#### Scenario: A test file is added under a shell module

- **WHEN** a test source set appears in any `:app:*` module
- **THEN** the behaviour it covers is relocated into `:domain` or an adapter, and the test moves
  with it

#### Scenario: A shell forwarding is wrong but decides nothing

- **WHEN** a shell forwards a platform callback to the wrong collaborator, with no conditional, in code
  the relocation does not reach (a Swift argument, a hand-written entry outside the inbound port)
- **THEN** every shell gate passes and the build is green; the defect is not covered by this rule, and a
  capability relying on shell correctness states that gap rather than assuming coverage

#### Scenario: An OS callback is routed to the wrong flow

- **WHEN** the inbound port's implementation sends an entry to the wrong collaborator
- **THEN** that port's contract fails on JVM and the simulator, because its clauses assert the entry's
  outcome in the world, and the shell holds no forwarding body in which the mistake could otherwise sit
